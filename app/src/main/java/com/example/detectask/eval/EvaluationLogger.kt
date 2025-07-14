package com.example.detectask.eval

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter

/**
 * Singleton-style logger for recording evaluation entries to a JSONL file.
 *
 * Use [init] once before calling [get] or [log]. Each run writes to its own file based on [runId].
 *
 * Log entries are serialized to a newline-delimited JSON format for easy parsing.
 */
class EvaluationLogger private constructor(
    private val runId: String
) {

    companion object {
        private var instance: EvaluationLogger? = null

        /**
         * Initializes the logger instance for a specific run.
         *
         * @param context Android context (used for file access).
         * @param runId Identifier for the evaluation session (used in file naming).
         * @return The initialized [EvaluationLogger].
         */
        fun init(context: Context, runId: String): EvaluationLogger {
            val logger = EvaluationLogger(runId)
            instance = logger
            return logger
        }

        /**
         * Returns the logger instance.
         *
         * @throws IllegalStateException if [init] was not called beforehand.
         */
        fun get(): EvaluationLogger {
            return instance ?: throw IllegalStateException("EvaluationLogger not initialized.")
        }
    }

    /**
     * File where log entries will be written.
     */
    private val logFile: File by lazy {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadsDir, "DetectAskEval")
        if (!targetDir.exists()) targetDir.mkdirs()
        File(targetDir, "$runId.jsonl")
    }

    /**
     * Logs a new evaluation entry by writing it to the output file.
     *
     * @param entry The [LogEntry] to log.
     */
    fun log(entry: LogEntry) {
        try {
            val enriched = entry.copy(run_id = runId)
            val json = serialize(enriched)
            FileWriter(logFile, true).use { it.write(json + "\n") }
            Log.i("EvalLogger", "✅ Logged entry: ${entry.file_name} / ${entry.mode} / ${entry.llm}")
        } catch (e: Exception) {
            Log.e("EvalLogger", "❌ Failed to log entry: ${e.message}", e)
        }
    }

    /**
     * Serializes a [LogEntry] to a JSON line.
     *
     * Escapes strings to produce a safe, compact JSONL entry.
     */
    private fun serialize(entry: LogEntry): String {
        return buildString {
            append("{")
            append("\"run_id\": \"").append(entry.run_id).append("\",")
            append("\"model_name\": \"").append(entry.model_name).append("\",")
            append("\"llm\": \"").append(entry.llm).append("\",")
            append("\"source_type\": \"").append(entry.source_type).append("\",")
            append("\"file_name\": \"").append(entry.file_name).append("\",")
            append("\"mode\": \"").append(entry.mode.name).append("\",")
            append("\"target_label\": ").append(
                if (entry.target_label != null) "\"${entry.target_label}\"" else "null"
            ).append(",")
            append("\"sensor_file\": ").append(
                if (entry.sensor_file != null) "\"${entry.sensor_file}\"" else "null"
            ).append(",")
            append("\"cv_duration_ms\": ").append(entry.cv_duration_ms).append(",")
            append("\"llm_duration_ms\": ").append(entry.llm_duration_ms).append(",")
            append("\"total_duration_ms\": ").append(entry.total_duration_ms).append(",")
            append("\"prompt_length\": ").append(entry.prompt_length).append(",")
            append("\"response_length\": ").append(entry.response_length).append(",")
            append("\"scene_json\": ").append(jsonEscape(entry.scene_json)).append(",")
            append("\"prompt\": ").append(jsonEscape(entry.prompt)).append(",")
            append("\"llm_response\": ").append(jsonEscape(entry.llm_response)).append(",")
            append("\"error\": ").append(
                if (entry.error != null) "\"${entry.error}\"" else "null"
            ).append(",")
            append("\"timestamp\": \"").append(entry.timestamp).append("\"")
            append("}")
        }
    }

    /**
     * Escapes a string for JSON output.
     *
     * @param text The raw string.
     * @return Escaped JSON string, wrapped in quotes.
     */
    private fun jsonEscape(text: String): String {
        return "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""
    }
}
