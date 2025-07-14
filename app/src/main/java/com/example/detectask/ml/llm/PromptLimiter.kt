package com.example.detectask.ml.llm

/**
 * Utility for truncating long prompts to fit LLM input limits.
 *
 * Prevents token overflows or excessive prompt lengths.
 */
object PromptLimiter {

    /** Maximum allowed character length for prompts */
    private const val MAX_CHARS = 512

    /**
     * Truncates a prompt string if it exceeds [MAX_CHARS], preserving readability.
     *
     * Appends a note if the prompt was shortened. Whitespace is trimmed.
     *
     * @param prompt The full prompt string to check.
     * @return The original prompt if short enough, or a truncated version with a suffix.
     */
    fun truncateAfterJson(prompt: String): String {
        if (prompt.isBlank()) return ""

        return if (prompt.length <= MAX_CHARS) {
            prompt
        } else {
            prompt.take(MAX_CHARS).trimEnd() + "\n... (truncated)"
        }
    }
}
