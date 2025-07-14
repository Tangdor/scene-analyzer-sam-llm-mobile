package com.example.detectask.domain.usecase

import android.content.ContentResolver
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.example.detectask.domain.SceneDescriber
import com.example.detectask.ui.assistant.AssistantActivity
import com.example.detectask.ui.views.DetectionBox
import org.json.JSONObject

/**
 * A scene analyzer that parses and interprets pre-generated JSON files.
 *
 * This implementation supports different analysis modes based on the
 * structure and contents of the provided JSON, such as detailed object listings
 * or simple object count summaries.
 *
 * @constructor Creates a [JsonSceneAnalyzer] using the given [contentResolver] to read external files.
 * @param contentResolver Used to access the contents of the provided JSON [Uri].
 */
class JsonSceneAnalyzer(private val contentResolver: ContentResolver) : BaseSceneAnalyzer(), SceneAnalyzer {

    /**
     * Analyzes a JSON file located at [uri] and generates a scene summary based on the [mode].
     *
     * The method reads the file content, parses the JSON, and depending on the selected [mode],
     * either extracts object details, object counts, or generates a narrative description.
     *
     * @param uri The URI pointing to the JSON file to analyze.
     * @param mode The analysis mode, determining the structure and depth of interpretation.
     * @return A [SceneAnalysisResult] containing the parsed data and generated summary.
     * @throws IllegalArgumentException If the file cannot be read or if the JSON format is invalid for the given mode.
     */
    fun analyze(uri: Uri, mode: AssistantActivity.AnalysisMode): SceneAnalysisResult {
        val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("Failed to read JSON file.")

        val json = JSONObject(content)

        // Validate structure for detailed mode
        if (mode == AssistantActivity.AnalysisMode.DETAILED) {
            val array = json.optJSONArray("objects")
            if (array == null || array.length() == 0 || array.get(0) !is JSONObject) {
                throw IllegalArgumentException("Incompatible JSON format for mode DETAILED.")
            }
        }

        val boxes = mutableListOf<DetectionBox>()
        val objectArray = json.optJSONArray("objects")
        if (objectArray != null) {
            for (i in 0 until objectArray.length()) {
                val obj = objectArray.getJSONObject(i)
                val label = obj.getString("label")
                val score = obj.optDouble("score", 1.0).toFloat()
                val rectJson = obj.getJSONObject("box")
                val rect = RectF(
                    rectJson.getDouble("x").toFloat(),
                    rectJson.getDouble("y").toFloat(),
                    rectJson.getDouble("x").toFloat() + rectJson.getDouble("width").toFloat(),
                    rectJson.getDouble("y").toFloat() + rectJson.getDouble("height").toFloat()
                )
                boxes.add(DetectionBox(label, score, rect))
            }
        }

        val scene = when (mode) {
            AssistantActivity.AnalysisMode.DETAILED -> {
                SceneDescriber.describeSceneSimple(boxes, 720f, 720f)
            }

            AssistantActivity.AnalysisMode.COUNTS_ONLY -> {
                val countsJson = json.optJSONObject("counts")
                    ?: throw IllegalArgumentException("Missing 'counts' object in JSON for COUNTS_ONLY mode.")

                val countsMap = countsJson.keys().asSequence().associateWith { countsJson.getInt(it) }
                SceneDescriber.describeCountsOnlyMinimal(countsMap)
            }

            AssistantActivity.AnalysisMode.NARRATIVE -> {
                SceneDescriber.describeNarrativeMinimalJsonFromBoxes(boxes, 720f, 720f)
            }
        }

        lastSceneSummary = safelyTrim(scene, 800)
        Log.d("LLM", "Scene saved (JSON Analyze):\n$lastSceneSummary")

        return SceneAnalysisResult(
            summary = lastSceneSummary,
            boxes = boxes,
            json = content
        )
    }

    /**
     * Generates a text response from the language model based on user input and selected [mode].
     *
     * @param userInput The prompt or query provided by the user.
     * @param mode The analysis mode, which may influence the LLM's behavior (not directly used here).
     * @return A generated text response from the language model.
     */
    override fun generateResponse(userInput: String, mode: AssistantActivity.AnalysisMode): String {
        return com.example.detectask.ml.llm.LLMManager.generate(userInput)
    }

    /**
     * Generates a narrative description for the scene.
     *
     * Internally invokes [generateResponse] with a fixed prompt suited for storytelling.
     *
     * @return A narrative-style summary of the scene.
     */
    override fun generateNarrativeDescription(): String {
        return generateResponse("What does the scene show?", AssistantActivity.AnalysisMode.NARRATIVE)
    }
}
