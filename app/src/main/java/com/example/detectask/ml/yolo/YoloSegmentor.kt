package com.example.detectask.ml.yolo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.example.detectask.ui.views.DetectionBox
import com.example.detectask.ui.views.DetectionMask
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Wrapper around a YOLOv8-segmentation TFLite model with mask support.
 *
 * Performs image preprocessing, inference, and postprocessing including
 * Non-Maximum Suppression (NMS) and mask reconstruction.
 *
 * @param context Android context used to load assets and model.
 */
class YoloSegmentor(context: Context) {

    private val inputSize = 640
    private val interpreter: Interpreter
    private val labels = FileUtil.loadLabels(context, "coco.txt")

    private val scoreThreshold = 0.5f
    private val maskThreshold = 0.5f
    private val iouThreshold = 0.5f

    init {
        val model = FileUtil.loadMappedFile(context, "model.tflite")

        val options = Interpreter.Options().apply {
            try {
                addDelegate(NnApiDelegate())
                Log.d("TFLite", "NNAPI Delegate aktiviert")
            } catch (e: Exception) {
                try {
                    addDelegate(GpuDelegate())
                    Log.d("TFLite", "GPU Delegate aktiviert")
                } catch (ex: Exception) {
                    Log.d("TFLite", "Fallback auf CPU")
                }
            }
        }

        interpreter = Interpreter(model, options)
    }

    /**
     * Runs inference on the given bitmap and returns detected boxes and masks.
     *
     * @param bitmap The input image.
     * @param targetLabel Optional: if specified, filters output to only include this label.
     * @return A flat list of DetectionBox and DetectionMask objects (interleaved).
     */
    fun detect(bitmap: Bitmap, targetLabel: String? = null): List<Any> {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = convertBitmapToByteBuffer(scaledBitmap)

        val output = Array(1) { Array(116) { FloatArray(8400) } }
        val proto = Array(1) { Array(160) { Array(160) { FloatArray(32) } } }
        val outputMap = mapOf(0 to output, 1 to proto)

        val startTime = System.nanoTime()
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputMap)
        val duration = (System.nanoTime() - startTime) / 1_000_000
        Log.d("TFLite", "Inferenzzeit: ${duration}ms")

        val rawBoxes = mutableListOf<DetectionBox>()
        val rawMasks = mutableListOf<DetectionMask>()

        val data = output[0]
        val protos = proto[0]

        for (i in 0 until 8400) {
            val scores = FloatArray(labels.size) { c -> data[4 + c][i] }
            val maxScore = scores.maxOrNull() ?: 0f
            if (maxScore < scoreThreshold) continue

            val classId = scores.indices.maxByOrNull { scores[it] } ?: continue
            val label = labels.getOrNull(classId) ?: "Objekt"
            if (targetLabel != null && label.lowercase() != targetLabel.lowercase()) continue

            val cx = data[0][i] * bitmap.width
            val cy = data[1][i] * bitmap.height
            val w = data[2][i] * bitmap.width
            val h = data[3][i] * bitmap.height
            val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            rawBoxes.add(DetectionBox(label, maxScore, rect))

            val coeffs = FloatArray(32) { j -> data[84 + j][i] }

            val mask = Array(160) { FloatArray(160) }
            for (y in 0 until 160) {
                for (x in 0 until 160) {
                    var sum = 0f
                    for (m in 0 until 32) {
                        sum += coeffs[m] * protos[y][x][m]
                    }
                    mask[y][x] = 1f / (1f + exp(-sum))
                }
            }

            val points = mutableListOf<PointF>()
            val scaleX = bitmap.width / 160f
            val scaleY = bitmap.height / 160f

            for (y in 0 until 160) {
                for (x in 0 until 160) {
                    if (mask[y][x] > maskThreshold) {
                        points.add(PointF(x * scaleX, y * scaleY))
                    }
                }
            }

            rawMasks.add(DetectionMask(label, maxScore, points))
        }

        val filteredBoxes = applyNms(rawBoxes, iouThreshold)

        return filteredBoxes.flatMap { box ->
            val mask = rawMasks.find {
                it.label == box.label && abs(it.score - box.score) < 0.01
            }
            listOfNotNull(box, mask)
        }
    }

    /**
     * Converts a bitmap to a normalized input ByteBuffer for TFLite.
     *
     * @param bitmap A scaled [inputSize] x [inputSize] RGB image.
     * @return ByteBuffer formatted for TFLite input.
     */
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixel in intValues) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
        }

        buffer.rewind()
        return buffer
    }

    /**
     * Applies Non-Maximum Suppression (NMS) to reduce overlapping boxes.
     *
     * @param detections List of detection candidates.
     * @param iouThreshold Threshold above which boxes are considered overlapping.
     * @return Filtered list of [DetectionBox].
     */
    private fun applyNms(detections: List<DetectionBox>, iouThreshold: Float): List<DetectionBox> {
        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val selected = mutableListOf<DetectionBox>()

        while (sorted.isNotEmpty()) {
            val current = sorted.removeAt(0)
            selected.add(current)

            val toRemove = sorted.filter {
                calculateIoU(current.rect, it.rect) > iouThreshold
            }

            sorted.removeAll(toRemove)
        }

        return selected
    }

    /**
     * Calculates the Intersection over Union (IoU) of two rectangles.
     *
     * @param a First rectangle.
     * @param b Second rectangle.
     * @return IoU ratio in [0, 1].
     */
    private fun calculateIoU(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersectionArea = max(0f, right - left) * max(0f, bottom - top)
        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = aArea + bArea - intersectionArea
        return if (unionArea == 0f) 0f else intersectionArea / unionArea
    }
}
