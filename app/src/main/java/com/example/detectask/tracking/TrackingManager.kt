package com.example.detectask.tracking

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.example.detectask.ui.views.DetectionBox
import kotlin.math.*

/**
 * Central object tracking system using Kalman filters and Hungarian matching.
 *
 * Tracks multiple objects across frames with optional appearance histogram
 * matching and basic re-identification of recently lost objects.
 */
object TrackingManager {

    private var nextId = 1
    private val tracked = mutableListOf<EnhancedTrackedObject>()
    private val recentlyLost = mutableListOf<EnhancedTrackedObject>()

    private const val MAX_CENTER_DISTANCE = 100f
    private const val MAX_LOST_FRAMES = 15

    /**
     * Resets the tracking state and internal counters.
     */
    fun reset() {
        tracked.clear()
        recentlyLost.clear()
        nextId = 1
        TrackingNameManager.reset()
    }

    /**
     * Updates the tracking state for the current frame.
     *
     * @param detections List of detected objects.
     * @param frameIndex Current frame index.
     * @param rotationVector Optional device rotation vector (used to correct position).
     * @param frame Optional bitmap of the current frame (used for color histograms).
     * @return List of currently tracked objects after update.
     */
    fun update(
        detections: List<DetectionBox>,
        frameIndex: Int,
        rotationVector: FloatArray? = null,
        frame: Bitmap? = null
    ): List<EnhancedTrackedObject> {
        if (detections.isEmpty()) {
            Log.w("TrackingManager", "⚠️ No detections – skipping frame $frameIndex")
            return emptyList()
        }

        Log.d("TrackingManager", "🔍 Frame $frameIndex: ${detections.size} detections")

        // Predict all currently tracked objects
        for (track in tracked) {
            track.kalmanPosition.predict()
            track.kalmanWidth.predict()
            track.kalmanHeight.predict()
        }

        val costMatrix = Array(tracked.size) { FloatArray(detections.size) { Float.MAX_VALUE } }

        // Compute assignment costs
        for ((i, track) in tracked.withIndex()) {
            val predicted = track.predictRect()
            for ((j, detection) in detections.withIndex()) {
                if (track.label != detection.label) continue

                val centerDist = centerDistance(predicted, detection.rect)
                if (centerDist > MAX_CENTER_DISTANCE) continue

                val iou = computeIOU(predicted, detection.rect)
                val histScore = if (frame != null) {
                    val rect = Rect(
                        detection.rect.left.toInt(),
                        detection.rect.top.toInt(),
                        detection.rect.right.toInt(),
                        detection.rect.bottom.toInt()
                    )
                    val hist = ColorHistogramExtractor.extractHistogram(frame, rect)
                    ColorHistogramExtractor.compare(track.appearanceHist, hist)
                } else 0f

                val score = (1 - (centerDist / MAX_CENTER_DISTANCE)) * 0.4f +
                        (iou * 0.4f) +
                        (histScore * 0.2f)

                costMatrix[i][j] = 1f - score
            }
        }

        val assignments = HungarianMatcher.match(costMatrix)
        val assignedDetections = mutableSetOf<Int>()
        val updated = mutableListOf<EnhancedTrackedObject>()

        // Handle assigned tracks
        for ((trackIdx, detIdx) in assignments) {
            if (costMatrix[trackIdx][detIdx] >= 0.9f) {
                Log.d("TrackingManager", "❌ Assignment skipped for Track $trackIdx and Det $detIdx (cost=${costMatrix[trackIdx][detIdx]})")
                continue
            }

            val track = tracked[trackIdx]
            val match = detections[detIdx]
            assignedDetections.add(detIdx)

            val centerX = match.rect.centerX()
            val centerY = match.rect.centerY()
            val correctedX = centerX - (rotationVector?.getOrNull(1) ?: 0f) * 60f
            val correctedY = centerY - (rotationVector?.getOrNull(0) ?: 0f) * 60f

            val hist = if (frame != null) {
                val rect = Rect(
                    match.rect.left.toInt(),
                    match.rect.top.toInt(),
                    match.rect.right.toInt(),
                    match.rect.bottom.toInt()
                )
                ColorHistogramExtractor.extractHistogram(frame, rect)
            } else FloatArray(256)

            track.kalmanPosition.correct(correctedX, correctedY)
            track.kalmanWidth.correct(match.rect.width())
            track.kalmanHeight.correct(match.rect.height())
            track.appearanceHist = hist
            track.lastSeenFrame = frameIndex
            track.frameSeenCount++
            updated.add(track)

            Log.d("TrackingManager", "✅ Matched Track ${track.id} (${track.label}) to detection $detIdx")
        }

        // Handle unmatched detections
        for ((j, det) in detections.withIndex()) {
            if (j in assignedDetections) continue

            val centerX = det.rect.centerX()
            val centerY = det.rect.centerY()
            val correctedX = centerX - (rotationVector?.getOrNull(1) ?: 0f) * 60f
            val correctedY = centerY - (rotationVector?.getOrNull(0) ?: 0f) * 60f

            val hist = if (frame != null) {
                val rect = Rect(
                    det.rect.left.toInt(),
                    det.rect.top.toInt(),
                    det.rect.right.toInt(),
                    det.rect.bottom.toInt()
                )
                ColorHistogramExtractor.extractHistogram(frame, rect)
            } else FloatArray(256)

            val reident = recentlyLost.firstOrNull { lost ->
                lost.label == det.label &&
                        centerDistance(lost.predictRect(), det.rect) < MAX_CENTER_DISTANCE &&
                        ColorHistogramExtractor.compare(lost.appearanceHist, hist) > 0.8f
            }

            val newTrack = if (reident != null) {
                recentlyLost.remove(reident)
                EnhancedTrackedObject(
                    id = reident.id,
                    label = reident.label,
                    kalmanPosition = KalmanFilter2D().apply { initialize(correctedX, correctedY) },
                    kalmanWidth = KalmanFilter1D().apply { initialize(det.rect.width()) },
                    kalmanHeight = KalmanFilter1D().apply { initialize(det.rect.height()) },
                    appearanceHist = hist,
                    lastSeenFrame = frameIndex,
                    firstSeenFrame = reident.firstSeenFrame,
                    frameSeenCount = reident.frameSeenCount + 1
                ).also {
                    Log.d("TrackingManager", "🔁 Re-identified lost Track ${it.id} (${it.label})")
                }
            } else {
                EnhancedTrackedObject(
                    id = nextId++,
                    label = det.label,
                    kalmanPosition = KalmanFilter2D().apply { initialize(correctedX, correctedY) },
                    kalmanWidth = KalmanFilter1D().apply { initialize(det.rect.width()) },
                    kalmanHeight = KalmanFilter1D().apply { initialize(det.rect.height()) },
                    appearanceHist = hist,
                    lastSeenFrame = frameIndex,
                    firstSeenFrame = frameIndex,
                    frameSeenCount = 1
                ).also {
                    Log.d("TrackingManager", "🆕 New Track ID ${it.id} (${it.label})")
                }
            }

            tracked.add(newTrack)
            updated.add(newTrack)
        }

        // Remove lost tracks
        val lost = tracked.filter { frameIndex - it.lastSeenFrame > MAX_LOST_FRAMES }
        if (lost.isNotEmpty()) {
            Log.d("TrackingManager", "🗑️ Removing ${lost.size} lost tracks at frame $frameIndex")
        }

        recentlyLost.addAll(lost)
        tracked.removeAll(lost)
        recentlyLost.removeAll { frameIndex - it.lastSeenFrame > 60 }

        return updated
    }

    /**
     * Returns both currently tracked and recently lost objects.
     */
    fun getAllTrackedAndRecentlyLost(): List<EnhancedTrackedObject> = tracked + recentlyLost

    private fun centerDistance(a: RectF, b: RectF): Float {
        val ax = (a.left + a.right) / 2f
        val ay = (a.top + a.bottom) / 2f
        val bx = (b.left + b.right) / 2f
        val by = (b.top + b.bottom) / 2f
        return sqrt((ax - bx).pow(2) + (ay - by).pow(2))
    }

    private fun computeIOU(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
