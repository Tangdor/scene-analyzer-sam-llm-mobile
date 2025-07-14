package com.example.detectask.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Collects and logs gyroscope and accelerometer sensor data from the device.
 *
 * This class listens to Android's motion sensors and stores synchronized readings
 * from the gyroscope and accelerometer as [SensorSample] entries.
 *
 * @constructor Creates a new [SensorLogger] instance bound to the given [context].
 * The sensor manager and relevant sensors are initialized upon instantiation.
 *
 * @param context The application context used to access the device's sensor system.
 */
class SensorLogger(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _samples = mutableListOf<SensorSample>()

    /**
     * The list of recorded sensor samples.
     *
     * This is an immutable copy of the internally stored sample list.
     */
    val samples: List<SensorSample> get() = _samples.toList()

    /**
     * Starts listening to gyroscope and accelerometer data.
     *
     * The sampling rate is set to [SensorManager.SENSOR_DELAY_GAME].
     * Data is stored internally as [SensorSample] entries.
     */
    fun start() {
        sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
    }

    /**
     * Stops listening to sensor data and unregisters the listener from the system.
     */
    fun stop() {
        sensorManager.unregisterListener(this)
    }

    private var latestGyro = FloatArray(3)
    private var latestAccel = FloatArray(3)

    /**
     * Callback method triggered when new sensor data is available.
     *
     * Updates the most recent gyroscope or accelerometer values depending on
     * the sensor type, and creates a new [SensorSample] using the current timestamp.
     *
     * @param event The sensor event containing updated values.
     */
    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> latestGyro = event.values.clone()
            Sensor.TYPE_ACCELEROMETER -> latestAccel = event.values.clone()
        }

        val timestamp = System.currentTimeMillis()
        _samples.add(SensorSample(timestamp, latestGyro.clone(), latestAccel.clone()))
    }

    /**
     * Callback method triggered when sensor accuracy changes.
     *
     * This implementation intentionally does nothing.
     *
     * @param sensor The sensor whose accuracy changed.
     * @param accuracy The new accuracy value.
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
