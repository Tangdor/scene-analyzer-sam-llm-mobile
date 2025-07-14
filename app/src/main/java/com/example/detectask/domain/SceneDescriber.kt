package com.example.detectask.domain

import android.graphics.RectF
import com.example.detectask.tracking.EnhancedTrackedObject
import com.example.detectask.ui.views.DetectionBox
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Utility object for generating scene summaries from tracked objects or detection boxes.
 *
 * Supports structured JSON, natural-language summaries, and compact counts.
 */
object SceneDescriber {

    /**
     * Describes a tracked scene as structured JSON, including relative positions and relations.
     */
    fun describeTrackedSceneAsJson(
        objects: List<EnhancedTrackedObject>,
        frameWidth: Float,
        frameHeight: Float
    ): String {
        val labelCounters = mutableMapOf<String, Int>()
        val idMap = mutableMapOf<EnhancedTrackedObject, String>()
        val result = JSONObject()
        val objectIds = JSONArray()
        val counts = mutableMapOf<String, Int>()

        for (obj in objects) {
            val label = obj.label
            val count = labelCounters.getOrDefault(label, 0) + 1
            labelCounters[label] = count
            val id = "${label}_$count"
            idMap[obj] = id
            objectIds.put(id)

            val location = describeRelativeLocationNormalized(obj.predictRect(), frameWidth, frameHeight)
            result.put(id, JSONObject().apply {
                put("position", location)
                put("relations", JSONArray())
            })

            counts[label] = counts.getOrDefault(label, 0) + 1
        }

        val pairs = idMap.entries.toList()
        for (i in pairs.indices) {
            val (aObj, aId) = pairs[i]
            val aCenter = centerOf(aObj.predictRect())
            for (j in i + 1 until pairs.size) {
                val (bObj, bId) = pairs[j]
                val bCenter = centerOf(bObj.predictRect())

                val dx = bCenter.first - aCenter.first
                val dy = bCenter.second - aCenter.second

                val rel = when {
                    abs(dx) > abs(dy) && dx > 0 -> "$bId is right of $aId"
                    abs(dx) > abs(dy) && dx < 0 -> "$bId is left of $aId"
                    abs(dy) > abs(dx) && dy > 0 -> "$bId is below $aId"
                    abs(dy) > abs(dx) && dy < 0 -> "$bId is above $aId"
                    else -> "$bId is near $aId"
                }

                result.getJSONObject(aId).getJSONArray("relations").put(rel)
                result.getJSONObject(bId).getJSONArray("relations").put(invertRelation(rel))
            }
        }

        result.put("objects", objectIds)
        result.put("counts", JSONObject(counts))
        return result.toString().replace("\\/", "/")
    }

    /**
     * Returns a plain-text scene summary listing all detected boxes and their counts.
     */
    fun describeSceneSimple(
        boxes: List<DetectionBox>,
        imageWidth: Float,
        imageHeight: Float
    ): String {
        val builder = StringBuilder()
        builder.appendLine("OBJECTS:")
        for ((index, box) in boxes.withIndex()) {
            val location = describeRelativeLocationNormalized(box.rect, imageWidth, imageHeight)
            builder.appendLine("- ${box.label}_${index + 1} at $location")
        }

        builder.appendLine("\nCOUNTS:")
        val counts = boxes.groupingBy { it.label }.eachCount()
        for ((label, count) in counts) {
            builder.appendLine("- $label: $count")
        }

        builder.appendLine("\nSUMMARY:")
        builder.appendLine("- Detected ${boxes.size} object(s).")

        return builder.toString().trim()
    }

    /**
     * Extracts details from a specific object by ID from a full scene JSON string.
     */
    fun describeSceneForTarget(targetId: String, fullSceneJson: String): String {
        val root = JSONObject(fullSceneJson)
        val counts = root.optJSONObject("counts") ?: JSONObject()
        val positions = root.optJSONObject("positions") ?: return fullSceneJson
        val relations = root.optJSONArray("relations") ?: JSONArray()

        val pos = positions.optString(targetId, null) ?: return fullSceneJson
        val label = targetId.substringBefore("_")

        val filteredRelations = JSONArray()
        for (i in 0 until relations.length()) {
            val rel = relations.optString(i)
            if (rel.contains(targetId)) filteredRelations.put(rel)
        }

        return JSONObject().apply {
            put("object", JSONObject().apply {
                put("id", targetId)
                put("label", label)
                put("position", pos)
                put("relations", filteredRelations)
            })
            put("counts", counts)
        }.toString().replace("\\/", "/")
    }

    /**
     * Generates a minimal narrative-style JSON summary from tracked objects.
     */
    fun describeNarrativeMinimalJson(
        objects: List<EnhancedTrackedObject>,
        frameWidth: Float,
        frameHeight: Float
    ): String {
        val counts = mutableMapOf<String, Int>()
        val positions = JSONObject()
        val labelToPositions = mutableMapOf<String, MutableList<String>>()

        val labelCounters = mutableMapOf<String, Int>()
        val idMap = mutableMapOf<EnhancedTrackedObject, String>()

        for (obj in objects) {
            val label = obj.label
            val count = labelCounters.getOrDefault(label, 0) + 1
            labelCounters[label] = count
            val id = "${label}_$count"
            idMap[obj] = id

            val pos = describeRelativeLocationNormalized(obj.predictRect(), frameWidth, frameHeight)
            positions.put(id, pos)
            labelToPositions.getOrPut(label) { mutableListOf() }.add(pos)
            counts[label] = counts.getOrDefault(label, 0) + 1
        }

        val relationPhrases = JSONArray()
        for ((label, posList) in labelToPositions) {
            val summary = summarizePositions(label, posList)
            if (summary.isNotBlank()) relationPhrases.put(summary)
        }

        return JSONObject().apply {
            put("counts", JSONObject(labelCounters))
            put("positions", positions)
            put("relations", relationPhrases)
            put("total", labelCounters.values.sum())
        }.toString().replace("\\/", "/")
    }

    /**
     * Same as [describeNarrativeMinimalJson], but works with plain detection boxes.
     */
    fun describeNarrativeMinimalJsonFromBoxes(
        boxes: List<DetectionBox>,
        frameWidth: Float,
        frameHeight: Float
    ): String {
        val counts = mutableMapOf<String, Int>()
        val positions = JSONObject()
        val labelToPositions = mutableMapOf<String, MutableList<String>>()

        val labelCounters = mutableMapOf<String, Int>()
        val idMap = mutableMapOf<DetectionBox, String>()

        for (box in boxes) {
            val label = box.label
            val count = labelCounters.getOrDefault(label, 0) + 1
            labelCounters[label] = count
            val id = "${label}_$count"
            idMap[box] = id

            val pos = describeRelativeLocationNormalized(box.rect, frameWidth, frameHeight)
            positions.put(id, pos)
            labelToPositions.getOrPut(label) { mutableListOf() }.add(pos)
            counts[label] = counts.getOrDefault(label, 0) + 1
        }

        val relationPhrases = JSONArray()
        for ((label, posList) in labelToPositions) {
            val summary = summarizePositions(label, posList)
            if (summary.isNotBlank()) relationPhrases.put(summary)
        }

        return JSONObject().apply {
            put("counts", JSONObject(counts))
            put("positions", positions)
            put("relations", relationPhrases)
            put("total", labelCounters.values.sum())
        }.toString().replace("\\/", "/")
    }

    /**
     * Generates a human-style summary phrase from a list of zones.
     */
    private fun summarizePositions(label: String, positions: List<String>): String {
        val zones = positions.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        if (zones.isEmpty()) return ""
        val main = zones.first().key
        val quant = if (positions.size == 1) "a" else "${positions.size}"
        val labelPlural = if (positions.size > 1) "${label}s" else label
        return "There ${if (quant == "a") "is" else "are"} $quant $labelPlural mostly at $main"
    }

    /**
     * Converts a bounding box to a descriptive relative zone label.
     */
    private fun describeRelativeLocationNormalized(rect: RectF, width: Float, height: Float): String {
        val cx = (rect.left + rect.right) / 2f / width
        val cy = (rect.top + rect.bottom) / 2f / height
        val hZone = when {
            cx < 0.33f -> "left"
            cx > 0.66f -> "right"
            else -> "center"
        }
        val vZone = when {
            cy < 0.33f -> "top"
            cy > 0.66f -> "bottom"
            else -> "middle"
        }
        return "$hZone/$vZone"
    }

    /**
     * Creates a compact summary from object label counts.
     */
    fun describeCountsOnlyMinimal(counts: Map<String, Int>): String {
        val total = counts.values.sum()
        return JSONObject().apply {
            put("counts", JSONObject(counts))
            put("total", total)
        }.toString().replace("\\/", "/")
    }

    /**
     * Returns the center point of a rectangle.
     */
    private fun centerOf(rect: RectF): Pair<Float, Float> {
        return (rect.left + rect.right) / 2f to (rect.top + rect.bottom) / 2f
    }

    /**
     * Inverts a textual spatial relation string.
     */
    private fun invertRelation(relation: String): String {
        return relation.replace(" is right of ", " TEMP_RIGHT ")
            .replace(" is left of ", " is right of ")
            .replace(" TEMP_RIGHT ", " is left of ")
            .replace(" is above ", " TEMP_ABOVE ")
            .replace(" is below ", " is above ")
            .replace(" TEMP_ABOVE ", " is below ")
    }
}
