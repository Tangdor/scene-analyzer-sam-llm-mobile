package com.example.detectask.eval

import com.example.detectask.ui.assistant.AssistantActivity.AnalysisMode
import java.util.Date

/**
 * A single JSON-compatible log entry for an evaluation result.
 *
 * Contains metadata, timing, prompts, and optional error information.
 *
 * @property run_id Unique identifier for the current evaluation run.
 * @property model_name Name of the model used (e.g., YOLO variant).
 * @property llm Indicates whether the local or server LLM was used ("local" or "server").
 * @property source_type Type of input file ("image", "video", or "json").
 * @property file_name Name of the evaluated file (e.g., image or video asset).
 * @property mode The analysis mode used during evaluation.
 * @property target_label Optional: The label that was specifically queried (e.g., "chair").
 * @property sensor_file Optional: Name of the sensor file used (only for video).
 * @property cv_duration_ms Time spent on scene analysis (computer vision), in milliseconds.
 * @property llm_duration_ms Time spent by the LLM to generate a response, in milliseconds.
 * @property total_duration_ms Total evaluation time, from CV start to LLM end.
 * @property prompt_length Character count of the prompt sent to the LLM.
 * @property response_length Character count of the LLM response.
 * @property scene_json Raw scene representation in JSON format.
 * @property prompt Full prompt used for the LLM call.
 * @property llm_response Generated response from the LLM.
 * @property error Optional: Error message if the evaluation failed.
 * @property timestamp ISO 8601 timestamp of log creation.
 */
data class LogEntry(
    val run_id: String,
    val model_name: String,
    val llm: String,

    val source_type: String,
    val file_name: String,
    val mode: AnalysisMode,

    val target_label: String? = null,
    val sensor_file: String? = null,

    val cv_duration_ms: Long,
    val llm_duration_ms: Long,
    val total_duration_ms: Long,

    val prompt_length: Int,
    val response_length: Int,

    val scene_json: String,
    val prompt: String,
    val llm_response: String,

    val error: String? = null,
    val timestamp: String = ISO8601.now()
)

/**
 * Utility object for generating timestamps in ISO 8601 format.
 */
object ISO8601 {

    /**
     * Returns the current system time in ISO 8601 format.
     *
     * @return A string like "2025-07-11T14:22:45+02:00".
     */
    fun now(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(Date())
}
