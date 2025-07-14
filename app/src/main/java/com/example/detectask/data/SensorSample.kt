package com.example.detectask.data

/**
 * Represents a single sensor sample containing timestamped motion data.
 *
 * A [SensorSample] holds synchronized readings from the gyroscope and
 * accelerometer sensors at a specific point in time.
 *
 * @property timestamp The system time in milliseconds when the sample was recorded.
 * @property gyro A 3-element array representing angular velocity (rad/s) along the X, Y, and Z axes.
 * @property accel A 3-element array representing acceleration (m/s²) along the X, Y, and Z axes.
 */
data class SensorSample(
    val timestamp: Long,
    val gyro: FloatArray,
    val accel: FloatArray
)
