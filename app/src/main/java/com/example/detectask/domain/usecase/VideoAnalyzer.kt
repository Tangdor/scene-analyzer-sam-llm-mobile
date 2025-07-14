package com.example.detectask.domain.usecase

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.example.detectask.data.SensorSample
import com.example.detectask.domain.SceneDescriber
import com.example.detectask.ml.llm.LlmChatClient
import com.example.detectask.ml.yolo.YoloSegmentor
import com.example.detectask.tracking.EnhancedTrackedObject
import com.example.detectask.tracking.TrackingManager
import com.example.detectask.ui.assistant.AssistantActivity
import com.example.detectask.ui.views.DetectionBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Analyzes a video file to extract tracked objects and generate a scene description.
 *
 * The analysis uses YOLO segmentation and sensor data (if available) to track objects
 * across frames. Results are saved as annotated images and a JSON summary.
 *
 * @constructor Creates a [VideoAnalyzer] bound to the given [context].
 */
class VideoAnalyzer(private val context: Context) : BaseSceneAnalyzer(), SceneAnalyzer {

    private val yolo = YoloSegmentor(context)
    internal val llm = LlmChatClient(context)

    companion object {
        private const val MIN_TRACKED_FRAMES = 2
    }

    /**
     * Public coroutine entrypoint to analyze a video in background.
     */
    suspend fun analyze(
        uri: Uri,
        mode: AssistantActivity.AnalysisMode,
        sensorSamples: List<SensorSample> = emptyList()
    ): SceneAnalysisResult = withContext(Dispatchers.IO) {
        analyzeInternal(uri, sensorSamples, mode)
    }

    /**
     * Analyzes the given video [uri] and returns a [SceneAnalysisResult].
     *
     * @param uri The video file location.
     * @param sensorSamples Optional gyroscope data for improving tracking.
     * @param mode The analysis mode (e.g., detailed, narrative, counts).
     * @return Scene summary, detection boxes, and export file path.
     * @throws IllegalArgumentException if video metadata can't be read.
     */
    private fun analyzeInternal(
        uri: Uri,
        sensorSamples: List<SensorSample>,
        mode: AssistantActivity.AnalysisMode
    ): SceneAnalysisResult {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)

        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: throw IllegalArgumentException("Could not determine video duration.")

        val stepMicros = 100_000L // Sampling every 100ms
        val totalSteps = durationMs * 1000 / stepMicros

        TrackingManager.reset()

        val trackedDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "TrackedFrames"
        ).apply { mkdirs() }

        val colorMap = mutableMapOf<String, Int>()
        val colorPalette = listOf(
            Color.RED, Color.GREEN, Color.BLUE, Color.CYAN,
            Color.MAGENTA, Color.YELLOW, Color.WHITE, Color.LTGRAY
        )

        var lastFrameWidth = 1f
        var lastFrameHeight = 1f

        for (i in 0 until totalSteps.toInt()) {
            val frameTimeUs = i * stepMicros
            val frame = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: continue

            lastFrameWidth = frame.width.toFloat()
            lastFrameHeight = frame.height.toFloat()

            val timestampMs = frameTimeUs / 1000
            val detections = yolo.detect(frame).filterIsInstance<DetectionBox>()
            if (detections.isEmpty()) continue

            val rotation = sensorSamples.minByOrNull { abs(it.timestamp - timestampMs) }?.gyro
                ?: floatArrayOf(0f, 0f, 0f)

            val updatedTracks = TrackingManager.update(detections, i, rotation, frame)
            if (updatedTracks.isEmpty()) continue

            // Annotate frame with tracked boxes
            val drawBitmap = frame.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(drawBitmap)

            updatedTracks.forEach { box ->
                val color = colorMap.getOrPut(box.label) {
                    colorPalette[colorMap.size % colorPalette.size]
                }

                val boxPaint = Paint().apply {
                    this.color = color
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }

                val textPaint = Paint().apply {
                    this.color = color
                    textSize = 30f
                    isAntiAlias = true
                }

                val labelText = "${box.label}_${box.id}"
                canvas.drawRect(box.predictRect(), boxPaint)
                canvas.drawText(labelText, box.predictRect().left, box.predictRect().top - 10f, textPaint)
            }

            // Save annotated frame to disk
            File(trackedDir, "frame_${timestampMs}.jpg").also { file ->
                try {
                    FileOutputStream(file).use { out ->
                        drawBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                } catch (_: Exception) { /* ignore */ }
            }
        }

        retriever.release()

        val allTracks = TrackingManager.getAllTrackedAndRecentlyLost()
        val trackedRaw = allTracks.filter { it.frameSeenCount >= MIN_TRACKED_FRAMES }
        val tracked = mergeDuplicateTracks(trackedRaw)

        if (tracked.isEmpty()) {
            return SceneAnalysisResult(
                summary = "⚠️ No objects could be tracked in the video.",
                filePath = "none"
            )
        }

        val boxes = tracked.map {
            DetectionBox(it.label, 1.0f, it.predictRect())
        }

        val scene = when (mode) {
            AssistantActivity.AnalysisMode.DETAILED,
            AssistantActivity.AnalysisMode.NARRATIVE ->
                SceneDescriber.describeNarrativeMinimalJson(tracked, lastFrameWidth, lastFrameHeight)

            AssistantActivity.AnalysisMode.COUNTS_ONLY ->
                SceneDescriber.describeCountsOnlyMinimal(boxes.groupingBy { it.label }.eachCount())
        }

        lastSceneSummary = safelyTrim(scene, 800)
        Log.d("LLM", "✅ Final Scene Summary:\n$lastSceneSummary")

        val json = JSONObject().apply {
            val array = JSONArray()
            for (obj in tracked) {
                val entry = JSONObject().apply {
                    put("id", obj.id)
                    put("label", obj.label)
                    put("firstSeenFrame", obj.firstSeenFrame)
                    put("lastSeenFrame", obj.lastSeenFrame)

                    val box = obj.predictRect()
                    put("box", JSONObject().apply {
                        put("x", box.left)
                        put("y", box.top)
                        put("width", box.width())
                        put("height", box.height())
                    })
                }
                array.put(entry)
            }
            put("objects", array)
            put("scene", lastSceneSummary)
        }

        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "video_analysis_${System.currentTimeMillis()}.json"
        ).apply {
            writeText(json.toString(2))
        }

        return SceneAnalysisResult(
            summary = lastSceneSummary,
            boxes = boxes,
            json = json.toString(2),
            filePath = file.absolutePath
        )
    }

    /**
     * Merges duplicate object tracks that were never visible at the same time but overlap spatially.
     */
    private fun mergeDuplicateTracks(tracks: List<EnhancedTrackedObject>): List<EnhancedTrackedObject> {
        val merged = mutableListOf<EnhancedTrackedObject>()
        val used = mutableSetOf<Int>()

        for (i in tracks.indices) {
            if (i in used) continue
            val a = tracks[i]
            val group = mutableListOf(a)
            used.add(i)

            for (j in i + 1 until tracks.size) {
                if (j in used) continue
                val b = tracks[j]

                val iou = computeIOU(a.predictRect(), b.predictRect())
                val timeGap = abs(a.firstSeenFrame - b.firstSeenFrame)
                val sameSpace = iou > 0.7f
                val neverSimultaneous = a.lastSeenFrame < b.firstSeenFrame || b.lastSeenFrame < a.firstSeenFrame

                if (sameSpace && neverSimultaneous && timeGap < 50) {
                    group.add(b)
                    used.add(j)
                }
            }

            val dominantLabel = group
                .map { it.label }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }?.key ?: a.label

            merged.add(a.copy(label = dominantLabel))
        }

        return merged
    }

    /**
     * Computes Intersection over Union (IoU) between two rectangles.
     */
    private fun computeIOU(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)

        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    /**
     * Generates a response from the LLM using the given [userInput].
     */
    override fun generateResponse(userInput: String, mode: AssistantActivity.AnalysisMode): String {
        return com.example.detectask.ml.llm.LLMManager.generate(userInput)
    }

    /**
     * Generates a narrative-style scene summary for the video.
     */
    override fun generateNarrativeDescription(): String {
        return generateResponse(
            "What does the video show?",
            AssistantActivity.AnalysisMode.NARRATIVE
        )
    }
}
