package com.example.detectask.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * Data class representing a detected object with a bounding box.
 *
 * @property label The class label of the object.
 * @property score The confidence score of the detection.
 * @property rect The bounding rectangle of the object.
 */
data class DetectionBox(
    val label: String,
    val score: Float,
    val rect: RectF
)

/**
 * Data class representing a detected segmentation mask.
 *
 * @property label The class label of the object.
 * @property score The confidence score of the detection.
 * @property points List of polygon points outlining the mask shape.
 */
data class DetectionMask(
    val label: String,
    val score: Float,
    val points: List<PointF>
)

/**
 * Custom view for drawing bounding boxes and segmentation masks as an overlay.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** Current list of bounding boxes. */
    var boxes: List<DetectionBox> = emptyList()

    /** Current list of segmentation masks. */
    var masks: List<DetectionMask> = emptyList()

    private val boxPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val maskPaint = Paint().apply {
        color = Color.argb(100, 138, 43, 226)
        style = Paint.Style.FILL
    }

    /**
     * Updates the list of detections and triggers a redraw.
     *
     * @param newBoxes The new list of bounding boxes.
     * @param newMasks The new list of segmentation masks.
     */
    fun updateDetections(newBoxes: List<DetectionBox>, newMasks: List<DetectionMask>) {
        boxes = newBoxes
        masks = newMasks
        Log.d("OverlayView", "Update detections: ${boxes.size} boxes, ${masks.size} masks")
        invalidate()
    }

    /**
     * Draws all bounding boxes and masks on the canvas.
     *
     * @param canvas The canvas used for drawing.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        Log.d("OverlayView", "onDraw called with ${boxes.size} boxes and ${masks.size} masks")

        for (mask in masks) {
            val path = Path()
            mask.points.firstOrNull()?.let { path.moveTo(it.x, it.y) }
            mask.points.drop(1).forEach { path.lineTo(it.x, it.y) }
            path.close()
            canvas.drawPath(path, maskPaint)
        }

        for (box in boxes) {
            canvas.drawRect(box.rect, boxPaint)
            canvas.drawText(
                "${box.label} ${"%.2f".format(box.score)}",
                box.rect.left,
                box.rect.top - 10,
                textPaint
            )
        }
    }
}
