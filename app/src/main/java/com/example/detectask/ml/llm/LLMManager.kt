package com.example.detectask.ml.llm

import android.util.Log
import com.example.detectask.domain.SceneDescriber
import com.example.detectask.ui.assistant.AssistantActivity.AnalysisMode
import com.example.detectask.ui.assistant.AssistantActivity.SourceType

/**
 * Central manager for handling LLM-related operations and scene prompt preparation.
 *
 * Maintains scene context, analysis mode, and delegates generation to the appropriate LLM (local or server).
 */
object LLMManager {

    private var sceneContext: String? = null
    private lateinit var llmClient: LlmChatClient
    private var fullSceneJson: String? = null
    private var currentMode: AnalysisMode = AnalysisMode.DETAILED

    /** If true, generation will use the server-based LLM instead of local */
    var useServerLLM: Boolean = false

    /**
     * Initializes the manager with a local LLM client.
     *
     * @param client The [LlmChatClient] to use.
     */
    fun init(client: LlmChatClient) {
        llmClient = client
    }

    /**
     * Clears the current scene context.
     *
     * @param resetLlm Whether to reset the LLM session too (default: true).
     */
    fun clearScene(resetLlm: Boolean = true) {
        sceneContext = null
        fullSceneJson = null
        if (resetLlm) llmClient.resetSession()
    }

    /**
     * Returns whether a scene has been set and is non-empty.
     *
     * @return True if a scene context exists.
     */
    fun hasScene(): Boolean = !sceneContext.isNullOrBlank()

    /**
     * Sets the current scene and mode, and builds the corresponding LLM prompt.
     *
     * @param scene The scene content (typically JSON).
     * @param source The type of source input (image, video, etc.).
     * @param mode The selected analysis mode.
     */
    fun setSceneWithMode(scene: String, source: SourceType, mode: AnalysisMode) {
        fullSceneJson = scene
        currentMode = mode
        val prompt = PromptBuilder.build(scene, source, mode, null)
        Log.d("LLM", "✅ setSceneWithMode (mode=$mode, source=$source)")
        llmClient.resetSession()
        sceneContext = prompt
    }

    /**
     * Generates a response using the current scene and analysis mode.
     *
     * @param userInput User question or instruction.
     * @return LLM-generated response string.
     */
    fun generate(userInput: String): String {
        return generateWithLabels(userInput, emptySet())
    }

    /**
     * Generates a response using the scene, mode, and optionally filtered target labels.
     *
     * @param userInput The user prompt to respond to.
     * @param targetLabels Optional set of labels to focus on.
     * @return The generated response string.
     */
    fun generateWithLabels(userInput: String, targetLabels: Set<String>): String {
        if (!hasScene() || fullSceneJson == null) return localFallback(userInput)

        Log.d("LLM", "📝 Question: $userInput")
        Log.d("LLM", "▶️ generateWithLabels – mode=$currentMode, labels=$targetLabels")

        val effectiveScene = when {
            currentMode == AnalysisMode.DETAILED && targetLabels.size == 1 ->
                SceneDescriber.describeSceneForTarget(targetLabels.first(), fullSceneJson!!)

            targetLabels.isNotEmpty() ->
                SceneFilter.filterSceneByTargetLabels(fullSceneJson!!, targetLabels, currentMode == AnalysisMode.COUNTS_ONLY)

            else -> fullSceneJson!!
        }

        val prompt = PromptBuilder.build(
            scene = effectiveScene,
            source = SourceType.IMAGE,
            mode = currentMode,
            labels = targetLabels,
            userPrompt = userInput
        )
        Log.d("LLM", "📌 Final Prompt Length: ${prompt.length}")

        return if (useServerLLM) {
            Log.d("LLM", "🌐 Sending to server LLM...")
            LLMServerClient.generate(prompt)
        } else {
            val safePrompt = PromptLimiter.truncateAfterJson(prompt)
            Log.d("LLM_FULL_PROMPT", "Q: $userInput\n\n$safePrompt")
            llmClient.generateDirectResponse(safePrompt)
        }
    }

    /**
     * Generates a fallback response if no scene is available.
     *
     * @param input The user input prompt.
     * @return A response using only the raw prompt and no scene context.
     */
    private fun localFallback(input: String): String {
        return if (useServerLLM) {
            LLMServerClient.generate(input)
        } else {
            llmClient.generateResponse(input)
        }
    }
}
