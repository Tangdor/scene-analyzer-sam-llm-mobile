package com.example.detectask.ml.llm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Filters a scene JSON object by keeping only entries relevant to a set of target labels.
 *
 * Supports both "counts only" and full scene filtering modes.
 */
object SceneFilter {

    /**
     * Filters a full scene JSON string to retain only relevant objects based on [labels].
     *
     * When [isCountsOnly] is true, only the "counts" field is returned.
     * Otherwise, includes matching "positions" and an empty "relations" array.
     *
     * Gracefully falls back to returning the original JSON if parsing fails.
     *
     * @param json The original scene JSON string.
     * @param labels The set of user-requested object labels (e.g., "chair").
     * @param isCountsOnly If true, returns a minimal structure with only counts.
     *
     * @return A filtered scene JSON string compatible with the LLM prompt format.
     */
    fun filterSceneByTargetLabels(
        json: String,
        labels: Set<String>,
        isCountsOnly: Boolean
    ): String {
        Log.d("LLM", "🔎 SceneFilter: isCountsOnly=$isCountsOnly, labels=$labels")
        if (labels.isEmpty()) return json

        return try {
            val root = JSONObject(json)
            val counts = root.optJSONObject("counts") ?: JSONObject()
            val positions = root.optJSONObject("positions") ?: JSONObject()
            val filteredCounts = JSONObject()
            val filteredPositions = JSONObject()
            val filteredLabels = mutableSetOf<String>()

            // Match and retain only relevant counts
            for (key in counts.keys()) {
                val match = labels.any { userLabel ->
                    val cleaned = userLabel.trim().lowercase().removeSuffix("s")
                    cleaned == key.lowercase()
                }
                if (match) {
                    filteredCounts.put(key, counts.getInt(key))
                    filteredLabels.add(key)
                }
            }

            // Filter corresponding positions
            for (key in positions.keys()) {
                if (filteredLabels.any { label -> key.startsWith(label + "_") }) {
                    filteredPositions.put(key, positions.getString(key))
                }
            }

            val total = filteredCounts.keys().asSequence().sumOf { filteredCounts.getInt(it) }

            return JSONObject().apply {
                put("counts", filteredCounts)
                put("total", total)
                if (!isCountsOnly) {
                    put("positions", filteredPositions)
                    put("relations", JSONArray())
                }
            }.toString().replace("\\/", "/")

        } catch (e: Exception) {
            Log.e("LLM", "❌ SceneFilter failed: ${e.message}")
            return json
        }
    }
}
