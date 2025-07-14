package com.example.detectask.tracking

/**
 * 2D Kalman filter for tracking position (x, y) and velocity (vx, vy).
 *
 * Used to estimate the state of an object in two-dimensional space over time.
 */
class KalmanFilter2D {

    private val state = FloatArray(4) // [x, y, vx, vy]

    private val P = Array(4) { FloatArray(4) } // Covariance matrix
    private val Q = Array(4) { FloatArray(4) { if (it % 2 == 0) 1f else 0f } } // Process noise
    private val R = arrayOf( // Measurement noise
        floatArrayOf(10f, 0f),
        floatArrayOf(0f, 10f)
    )

    private val H = arrayOf( // Observation matrix
        floatArrayOf(1f, 0f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f, 0f)
    )

    private val F = arrayOf( // State transition matrix
        floatArrayOf(1f, 0f, 1f, 0f),
        floatArrayOf(0f, 1f, 0f, 1f),
        floatArrayOf(0f, 0f, 1f, 0f),
        floatArrayOf(0f, 0f, 0f, 1f)
    )

    /**
     * Initializes the filter with an initial position.
     *
     * @param x Initial x-position.
     * @param y Initial y-position.
     */
    fun initialize(x: Float, y: Float) {
        state[0] = x
        state[1] = y
        state[2] = 0f
        state[3] = 0f

        for (i in P.indices) {
            for (j in P[i].indices) {
                P[i][j] = if (i == j) 100f else 0f
            }
        }
    }

    /**
     * Predicts the next state and updates the error covariance matrix.
     */
    fun predict() {
        val result = FloatArray(4)
        for (i in 0..3) {
            result[i] = 0f
            for (j in 0..3) {
                result[i] += F[i][j] * state[j]
            }
        }
        for (i in 0..3) state[i] = result[i]

        // P = F * P * F^T + Q
        val temp = Array(4) { FloatArray(4) }
        for (i in 0..3) for (j in 0..3)
            temp[i][j] = (0..3).sumOf { k -> (F[i][k] * P[k][j]).toDouble() }.toFloat()

        for (i in 0..3) for (j in 0..3)
            P[i][j] = (0..3).sumOf { k -> (temp[i][k] * F[j][k]).toDouble() }.toFloat() + Q[i][j]
    }

    /**
     * Corrects the predicted state using a new position measurement.
     *
     * @param x Measured x-position.
     * @param y Measured y-position.
     */
    fun correct(x: Float, y: Float) {
        val z = floatArrayOf(x, y)

        val yVec = FloatArray(2)
        for (i in 0..1) {
            yVec[i] = z[i] - (0..3).sumOf { j -> (H[i][j] * state[j]).toDouble() }.toFloat()
        }

        val S = Array(2) { FloatArray(2) }
        for (i in 0..1) for (j in 0..1)
            S[i][j] = (0..3).sumOf { k -> (H[i][k] * P[k][j]).toDouble() }.toFloat() + R[i][j]

        val K = Array(4) { FloatArray(2) }
        for (i in 0..3) for (j in 0..1)
            K[i][j] = (0..3).sumOf { k -> (P[i][k] * H[j][k]).toDouble() }.toFloat() / S[j][j]

        for (i in 0..3)
            state[i] += (0..1).sumOf { j -> (K[i][j] * yVec[j]).toDouble() }.toFloat()

        val KH = Array(4) { FloatArray(4) }
        for (i in 0..3) for (j in 0..3)
            KH[i][j] = (0..1).sumOf { k -> (K[i][k] * H[k][j]).toDouble() }.toFloat()

        for (i in 0..3) for (j in 0..3)
            P[i][j] -= KH[i][j] * P[i][j]
    }

    /**
     * Returns the estimated x-position.
     *
     * @return Current x estimate.
     */
    fun getX(): Float = state[0]

    /**
     * Returns the estimated y-position.
     *
     * @return Current y estimate.
     */
    fun getY(): Float = state[1]
}
