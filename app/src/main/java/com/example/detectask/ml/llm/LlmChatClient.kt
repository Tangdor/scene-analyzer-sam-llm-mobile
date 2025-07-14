package com.example.detectask.ml.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import java.io.FileOutputStream

/**
 * LLM-Client für die lokale Ausführung von Textgenerierung über MediaPipe Gemma.
 *
 * Verwaltet eine interne Session mit Chat-Historie, Reset-Mechanismen und
 * verschiedenen Eingabearten (direkt oder dialogisch).
 *
 * @constructor Initialisiert das Modell aus den Assets, wenn es nicht existiert.
 */
class LlmChatClient(context: Context) {

    private val llm: LlmInference
    private var session: LlmInferenceSession
    private val history = mutableListOf<Pair<String, String>>()

    companion object {
        private const val MAX_TOKENS = 512
        private const val TEMPERATURE = 0.3f
        private const val TOP_P = 0.9f
        private const val TOP_K = 2
        private const val MAX_HISTORY_SIZE = 3
    }

    init {
        val modelFile = File(context.filesDir, "gemma.task")
        if (!modelFile.exists()) {
            context.assets.open("gemma.task").use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .setPreferredBackend(LlmInference.Backend.CPU)
            .build()

        llm = LlmInference.createFromOptions(context, options)
        session = createSession()
    }

    /**
     * Erstellt eine neue Session mit den vordefinierten Sampling-Optionen.
     *
     * @return Eine neue [LlmInferenceSession]-Instanz.
     */
    private fun createSession(): LlmInferenceSession {
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(TEMPERATURE)
            .setTopK(TOP_K)
            .setTopP(TOP_P)
            .build()
        return LlmInferenceSession.createFromOptions(llm, sessionOptions)
    }

    /**
     * Setzt die Session zurück, wenn die maximale Chat-History überschritten wurde.
     */
    private fun resetSessionIfHistoryTooLong() {
        if (history.size >= MAX_HISTORY_SIZE) {
            Log.d("LLM", "⚠️ Session reset (history.size = ${history.size})")
            session.close()
            session = createSession()
            history.clear()
        }
    }

    /**
     * Manuelles Zurücksetzen der Session und Löschung des Verlaufs.
     */
    fun resetSession() {
        Log.d("LLM", "Manual session reset")
        session.close()
        session = createSession()
        history.clear()
    }

    /**
     * Generiert eine Antwort im dialogischen Stil unter Beibehaltung der History.
     *
     * @param userInput Der neue Prompt des Benutzers.
     * @return Die Antwort des Sprachmodells.
     */
    fun generateResponse(userInput: String): String {
        resetSessionIfHistoryTooLong()
        session.addQueryChunk("User: $userInput\nAssistant: ")
        val result = session.generateResponse().trim()
        history.add(userInput to result)
        return result
    }

    /**
     * Generiert eine direkte Antwort basierend auf einem vollständigen Prompt (kein Verlauf).
     *
     * Reset vor jedem Aufruf, um Token-Überlauf zu vermeiden.
     *
     * @param fullPrompt Vollständig zusammengesetzter Prompt-String.
     * @return Antworttext des LLMs oder Fehlermeldung bei Fehler.
     */
    fun generateDirectResponse(fullPrompt: String): String {
        resetSession() // explizit jedes Mal → verhindert Token-Overflow

        val estimatedTotalTokens = fullPrompt.length / 4
        val allowedInputTokens = 500
        val safe = if (estimatedTotalTokens <= allowedInputTokens) {
            fullPrompt
        } else {
            fullPrompt.take(allowedInputTokens * 4).substringBeforeLast("\n")
        }

        return try {
            session.addQueryChunk("DATA:\n")
            Log.d("LLM_ACTUAL_PROMPT", "---------- BEGIN PROMPT ----------\n$safe\n---------- END PROMPT ----------")
            session.addQueryChunk(safe)
            val result = session.generateResponse().trim()
            Log.d("LLM", "🔁 LLM answer: $result")
            history.add("direct" to result)
            result
        } catch (e: Exception) {
            Log.e("LLM", "❌ LLM crashed: ${e.message}")
            "⚠️ LLM failed: input too long or invalid."
        }
    }

}
