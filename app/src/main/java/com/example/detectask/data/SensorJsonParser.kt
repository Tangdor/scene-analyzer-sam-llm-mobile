package com.example.detectask.data

import org.json.JSONArray

/**
 * Utility object for parsing JSON sensor data into a list of [SensorSample] objects.
 */
object SensorJsonParser {

    /**
     * Parses a JSON string containing an array of sensor samples.
     *
     * Each element in the JSON array must be a JSON object with the following structure:
     * - "timestamp": Long (UNIX timestamp in milliseconds)
     * - "gyro": Array of 3 Float values representing gyroscope readings
     * - "accel": Array of 3 Float values representing accelerometer readings
     *
     * @param json The JSON string representing an array of sensor data samples.
     * @return A list of [SensorSample] objects parsed from the JSON input.
     * @throws org.json.JSONException If the JSON format is invalid or required fields are missing.
     */
    fun parse(json: String): List<SensorSample> {
        val samples = mutableListOf<SensorSample>()
        val array = JSONArray(json)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val timestamp = obj.getLong("timestamp")
            val gyroArray = obj.getJSONArray("gyro")
            val accelArray = obj.getJSONArray("accel")

            val gyro = FloatArray(3) { gyroArray.getDouble(it).toFloat() }
            val accel = FloatArray(3) { accelArray.getDouble(it).toFloat() }

            samples.add(SensorSample(timestamp, gyro, accel))
        }

        return samples
    }
}
