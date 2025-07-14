package com.example.detectask.ml.llm

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client for sending prompts to an external LLM server.
 *
 * Assumes a Gemma/LLaMA-compatible REST endpoint that accepts a JSON payload
 * with a "prompt" field and responds with a "response" field.
 *
 * Notes:
 * - Uses long timeouts to support slow inference on CPU-based backends.
 * - Logs prompt size and response latency for debugging and profiling.
 */
object LLMServerClient {

    private const val SERVER_URL = "http://192.168.188.20:5001/llm" // Adjust if needed

    // Timeouts are tuned for large model inference with slow response times.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * Sends a prompt to the LLM server and retrieves the generated response.
     *
     * Handles network errors, timeout issues, and HTTP errors gracefully.
     *
     * @param prompt The input prompt string to send.
     * @return The LLM response string, or a fallback error message on failure.
     */
    fun generate(prompt: String): String {
        try {
            Log.d("LLM_SERVER", "➡️  Sending prompt (${prompt.length} chars)…")
            val start = System.currentTimeMillis()

            val json = JSONObject().apply { put("prompt", prompt) }
            val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(SERVER_URL)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - start
                Log.d("LLM_SERVER", "⬅️  Response in ${duration} ms – code ${response.code}")

                if (!response.isSuccessful) {
                    Log.e("LLM_SERVER", "❌ Server error: ${response.code}")
                    return "⚠️ LLM server error: ${response.code}"
                }

                val result = JSONObject(response.body?.string() ?: "{}")
                return result.optString("response", "⚠️ Empty response from LLM server")
            }
        } catch (e: Exception) {
            Log.e("LLM_SERVER", "❌ Exception: ${e.message}", e)
            return "⚠️ LLM server error: ${e.message}".trim()
        }
    }
}
