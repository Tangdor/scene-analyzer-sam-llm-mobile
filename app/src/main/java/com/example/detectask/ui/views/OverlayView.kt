package com.example.detectask.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * Datenklasse für erkannte Objekte als Bounding Box.
 *
 * @property label Klassenbezeichnung des Objekts
 * @property score Erkennungswahrscheinlichkeit (Confidence)
 * @property rect Position und Größe als Rechteck
 */
data class DetectionBox(
    val label: String,
    val score: Float,
    val rect: RectF
)

/**
 * Datenklasse für erkannte Segmentmasken.
 *
 * @property label Klassenbezeichnung des Objekts
 * @property score Erkennungswahrscheinlichkeit (Confidence)
 * @property points Liste von Punkten, die die Maske beschreiben
 */
data class DetectionMask(
    val label: String,
    val score: Float,
    val points: List<PointF>
)

/**
 * View zum Darstellen von Bounding Boxes und Segmentmasken als Overlay.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    /** Aktuelle Bounding Boxes */
    var boxes: List<DetectionBox> = emptyList()

    /** Aktuelle Segmentmasken */
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
     * Aktualisiert die angezeigten Erkennungen und fordert ein Neuzeichnen an.
     *
     * @param newBoxes Neue Liste von Bounding Boxes
     * @param newMasks Neue Liste von Segmentmasken
     */
    fun updateDetections(newBoxes: List<DetectionBox>, newMasks: List<DetectionMask>) {
        boxes = newBoxes
        masks = newMasks
        Log.d("OverlayView", "Update detections: ${boxes.size} boxes, ${masks.size} masks")
        invalidate()
    }

    /**
     * Zeichnet die Bounding Boxes und Masken auf die Canvas.
     *
     * @param canvas Canvas zum Zeichnen
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
