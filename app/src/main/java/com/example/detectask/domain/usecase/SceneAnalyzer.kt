package com.example.detectask.domain.usecase

import com.example.detectask.ui.assistant.AssistantActivity.AnalysisMode

/**
 * Defines a common interface for all scene analyzer implementations.
 *
 * Scene analyzers generate textual interpretations of visual content,
 * either based on user input or predefined narrative prompts.
 */
interface SceneAnalyzer {

    /**
     * Generates a language-based response based on user [input] and the selected analysis [mode].
     *
     * Implementations may use machine learning models or heuristic logic to produce context-aware replies.
     *
     * @param input The user-provided prompt or question.
     * @param mode The analysis mode that determines the level or style of interpretation.
     * @return A string response based on the given input and mode.
     */
    fun generateResponse(input: String, mode: AnalysisMode): String

    /**
     * Generates a narrative-style description of the analyzed scene.
     *
     * The description is typically structured as a natural-language summary of detected elements.
     *
     * @return A high-level narrative explanation of the scene.
     */
    fun generateNarrativeDescription(): String
}
