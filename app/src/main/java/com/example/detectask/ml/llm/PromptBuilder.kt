package com.example.detectask.ml.llm

import com.example.detectask.ui.assistant.AssistantActivity.AnalysisMode
import com.example.detectask.ui.assistant.AssistantActivity.SourceType
import org.json.JSONObject

/**
 * Builds LLM prompts based on scene content, input type, and analysis mode.
 *
 * Provides mode-specific instruction blocks, and allows optional user input
 * and label filtering to influence the resulting prompt.
 */
object PromptBuilder {

    /**
     * Constructs a complete prompt including the scene, optional user question, and task instructions.
     *
     * @param scene The scene data (JSON or plain-text).
     * @param source The origin of the scene (image, video, JSON).
     * @param mode The type of reasoning or task (e.g., narrative, detailed).
     * @param labels Optional labels to focus on (for targeted object questions).
     * @param userPrompt Optional natural-language question from the user.
     *
     * @return A formatted prompt string suitable for LLM input.
     */
    fun build(
        scene: String,
        source: SourceType,
        mode: AnalysisMode,
        labels: Set<String>? = null,
        userPrompt: String? = null
    ): String {
        val sceneBlock = "SCENE:\n$scene"
        val question = userPrompt?.takeIf { it.isNotBlank() }?.trim()

        // Select instruction template depending on mode
        val instructions = when (mode) {
            AnalysisMode.COUNTS_ONLY -> buildCountsPrompt("")
            AnalysisMode.DETAILED -> buildDetailedPrompt("", labels)
            AnalysisMode.NARRATIVE -> buildNarrativePrompt("")
        }.substringAfter("Instructions:").trim()

        return buildString {
            append(sceneBlock)
            if (!question.isNullOrBlank()) {
                append("\n\n")
                append(question)
            }
            append("\n\nInstructions:\n")
            append(instructions)
        }
    }

    /**
     * Returns instruction block for count-based queries (e.g. "how many chairs").
     *
     * @param scene Not used but included for symmetry with other prompt builders.
     * @return Instruction string with count-related guidance.
     */
    private fun buildCountsPrompt(scene: String): String = """
SCENE:
$scene
Instructions:
- Answer only about the object described under "object".
- Say what it is based on the "label".
- Describe where it is using the "position".
- If nearby objects are mentioned under "relations", include them.
- Mention total quantities only if relevant from "counts".
- If no information is available, say: I cannot answer based on the scene.
- Keep your answer short and factual.
""".trimIndent()

    /**
     * Returns detailed reasoning instructions, with or without focus on specific labels.
     *
     * @param scene The JSON-formatted scene.
     * @param labels Optional labels used to infer whether single-object focus applies.
     * @return Instruction string tailored for detail-oriented reasoning.
     */
    private fun buildDetailedPrompt(scene: String, labels: Set<String>?): String {
        return if (labels?.size == 1) {
            val json = try {
                JSONObject(scene).toString(2)
            } catch (_: Exception) {
                scene
            }

            """
SCENE:
$json
Instructions:
- The object is described in the field "object".
- To answer "where is it", use the "position".
- To answer "what is it", use the "label".
- To answer "what is the position", use the "position".
- Use the "relations" if asked about nearby objects.
- If not found, reply: I cannot answer based on the scene.
""".trimIndent()
        } else {
            """
SCENE:
$scene
Instructions:
- The "objects" list contains all object IDs.
- Use the "counts" object for quantity of each type.
- Do not guess or infer counts per object ID.
- To answer "what are the objects", list from "objects".
- To answer "how many chairs", use "counts".
- If not found, say: I cannot answer based on the scene.
""".trimIndent()
        }
    }

    /**
     * Returns a narrative-style instruction block for short paragraph generation.
     *
     * @param scene The scene (usually JSON).
     * @return Instruction paragraph to guide narrative summarization.
     */
    internal fun buildNarrativePrompt(scene: String): String = """
SCENE:
$scene
Instructions:
- Write one short paragraph.
- Mention total counts and main locations.
- Group each object type into one sentence.
- Be precise, short, and factual.
""".trimIndent()
}
