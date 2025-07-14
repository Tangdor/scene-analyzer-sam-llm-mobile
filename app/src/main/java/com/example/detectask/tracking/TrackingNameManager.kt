package com.example.detectask.tracking

/**
 * Assigns stable and human-readable names to tracked objects.
 *
 * Each label is given a numbered suffix to distinguish between multiple instances,
 * e.g. "chair_1", "chair_2", etc. Names remain stable across frames.
 */
object TrackingNameManager {

    private val nameMap = mutableMapOf<Int, String>()
    private val labelCounts = mutableMapOf<String, Int>()

    /**
     * Resets all name mappings and counters.
     */
    fun reset() {
        nameMap.clear()
        labelCounts.clear()
    }

}
