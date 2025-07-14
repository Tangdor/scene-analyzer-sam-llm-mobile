package com.example.detectask.tracking

/**
 * Simple 1D Kalman filter for position tracking.
 *
 * Estimates position and velocity based on noisy measurements.
 */
class KalmanFilter1D {

    private var x = 0f  // state: position
    private var v = 0f  // state: velocity
    private var p = 100f // estimation error covariance
    private val q = 1f   // process noise covariance
    private val r = 10f  // measurement noise covariance

    /**
     * Initializes the filter state with a starting position.
     *
     * @param position Initial position value.
     */
    fun initialize(position: Float) {
        x = position
        v = 0f
        p = 100f
    }

    /**
     * Predicts the next state (prior) based on current state and velocity.
     */
    fun predict() {
        x += v
        p += q
    }

    /**
     * Corrects the prediction using the given measurement.
     *
     * @param measurement New observed position.
     */
    fun correct(measurement: Float) {
        val residual = measurement - x
        val s = p + r
        val k = p / s

        x += k * residual
        v += k * residual * 0.5f
        p *= (1 - k)
    }

    /**
     * Returns the current estimated position.
     *
     * @return The most recent position estimate.
     */
    fun get(): Float = x
}
