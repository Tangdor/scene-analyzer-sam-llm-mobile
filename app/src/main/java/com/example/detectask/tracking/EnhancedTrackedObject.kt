package com.example.detectask.tracking

import android.graphics.RectF

/**
 * Represents a single tracked object with Kalman-based position and size estimation.
 *
 * @property id Unique identifier for the tracked object.
 * @property label Class label (e.g. "chair", "person").
 * @property kalmanPosition 2D Kalman filter tracking the object center (x, y).
 * @property kalmanWidth Kalman filter tracking the object width.
 * @property kalmanHeight Kalman filter tracking the object height.
 * @property appearanceHist Normalized histogram of appearance features (e.g. red channel).
 * @property lastSeenFrame Index of the last frame this object was detected in.
 * @property firstSeenFrame Index of the first frame this object appeared in.
 * @property frameSeenCount Total number of frames this object has been seen in.
 */
data class EnhancedTrackedObject(
    val id: Int,
    val label: String,
    val kalmanPosition: KalmanFilter2D,
    val kalmanWidth: KalmanFilter1D,
    val kalmanHeight: KalmanFilter1D,
    var appearanceHist: FloatArray,
    var lastSeenFrame: Int,
    val firstSeenFrame: Int,
    var frameSeenCount: Int = 1
) {

    /**
     * Predicts the current bounding box based on the Kalman filters.
     *
     * @return Estimated [RectF] of the object at the current frame.
     */
    fun predictRect(): RectF {
        val cx = kalmanPosition.getX()
        val cy = kalmanPosition.getY()
        val w = kalmanWidth.get()
        val h = kalmanHeight.get()
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EnhancedTrackedObject

        if (id != other.id) return false
        if (lastSeenFrame != other.lastSeenFrame) return false
        if (firstSeenFrame != other.firstSeenFrame) return false
        if (frameSeenCount != other.frameSeenCount) return false
        if (label != other.label) return false
        if (kalmanPosition != other.kalmanPosition) return false
        if (kalmanWidth != other.kalmanWidth) return false
        if (kalmanHeight != other.kalmanHeight) return false
        if (!appearanceHist.contentEquals(other.appearanceHist)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + lastSeenFrame
        result = 31 * result + firstSeenFrame
        result = 31 * result + frameSeenCount
        result = 31 * result + label.hashCode()
        result = 31 * result + kalmanPosition.hashCode()
        result = 31 * result + kalmanWidth.hashCode()
        result = 31 * result + kalmanHeight.hashCode()
        result = 31 * result + appearanceHist.contentHashCode()
        return result
    }
}
