package com.example.detectask.eval

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.detectask.data.SensorJsonParser
import com.example.detectask.domain.usecase.*
import com.example.detectask.ml.llm.*
import com.example.detectask.ui.assistant.AssistantActivity.AnalysisMode
import com.example.detectask.ui.assistant.AssistantActivity.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs end-to-end evaluations on test images, videos, and JSON scenes.
 *
 * Evaluates multiple input types and prompts across different modes and sources.
 * Handles both local and server-based LLMs.
 */
object EvaluationRunner {

    const val CURRENT_MODEL_NAME = "yoloe-v8l-seg-pf"

    /**
     * Entry point to run all evaluation scenarios.
     */
    suspend fun runAll(context: Context, useServerLLM: Boolean) {
        val runId = generateRunId()
        val logger = EvaluationLogger.init(context, runId)
        val localClient = LlmChatClient(context)
        val localImageAnalyzer = ImageSceneAnalyzer(context)
        val localVideoAnalyzer = VideoAnalyzer(context)
        val serverAnalyzer = RemoteSceneResultAdapter(context)
        val jsonAnalyzer = JsonSceneAnalyzer(context.contentResolver)

        val images = listOf("image_cluttered.jpg", "image_room.jpg", "image_target.jpg")
        val imageModes = AnalysisMode.entries.toTypedArray()
        val imageTargets = listOf(null, "chair")

        // Evaluate all image inputs
        for (image in images) {
            for (mode in imageModes) {
                for (target in imageTargets) {
                    val prompts = if (mode == AnalysisMode.NARRATIVE)
                        listOf<String?>(null)
                    else getTestPrompts(mode, target)

                    for (userPrompt in prompts) {
                        runCatching {
                            val result = evaluateImage(
                                context, image, mode, target, userPrompt, useServerLLM, runId,
                                localClient, localImageAnalyzer, serverAnalyzer
                            )
                            logger.log(result)
                        }.onFailure {
                            logger.log(failedEntry(image, mode, target, useServerLLM, it))
                        }
                    }
                }
            }
        }

        // Evaluate all video inputs
        val videos = listOf("video_static.mp4", "video_moving.mp4")
        val sensors = mapOf(
            "video_static.mp4" to listOf(null, "video_static.json"),
            "video_moving.mp4" to listOf(null, "video_moving.json")
        )

        for (video in videos) {
            for (mode in imageModes) {
                for (sensor in sensors[video] ?: listOf(null)) {
                    val prompts = if (mode == AnalysisMode.NARRATIVE)
                        listOf<String?>(null)
                    else getTestPrompts(mode, null)

                    for (userPrompt in prompts) {
                        runCatching {
                            val result = evaluateVideo(
                                context, video, mode, sensor, userPrompt, useServerLLM, runId,
                                localClient, localVideoAnalyzer, serverAnalyzer
                            )
                            logger.log(result)
                        }.onFailure {
                            logger.log(failedEntry(video, mode, null, useServerLLM, it, sensor))
                        }
                    }
                }
            }
        }

        // Evaluate all static JSON inputs
        val jsonFiles = listOf(
            Triple("scene_counts_only_chairs.json", listOf(AnalysisMode.COUNTS_ONLY), false),
            Triple("scene_detailed_livingroom.json", listOf(AnalysisMode.DETAILED), false),
            Triple("scene_invalid_structure.json", imageModes.toList(), true),
            Triple("scene_label_type_error.json", listOf(AnalysisMode.DETAILED), true)
        )

        for ((file, validModes, allowFailure) in jsonFiles) {
            for (mode in validModes) {
                val prompts = if (mode == AnalysisMode.NARRATIVE)
                    listOf<String?>(null)
                else getTestPrompts(mode, null)

                for (userPrompt in prompts) {
                    runCatching {
                        val result = evaluateJson(
                            context, file, mode, userPrompt, useServerLLM, runId,
                            localClient, jsonAnalyzer
                        )
                        logger.log(result)
                    }.onFailure {
                        Log.e("EvalRunner", "❌ JSON eval failed for $file / $mode", it)
                        if (allowFailure) {
                            logger.log(failedEntry(file, mode, null, useServerLLM, it))
                        } else {
                            throw it
                        }
                    }
                }
            }
        }

        Log.i("EvalRunner", "✅ Evaluation complete [$runId]")
    }

    /**
     * Generates a unique identifier for this evaluation run.
     */
    private fun generateRunId(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        return "${sdf.format(Date())}_$CURRENT_MODEL_NAME"
    }

    /**
     * Returns test prompts based on analysis mode and optional target label.
     */
    private fun getTestPrompts(mode: AnalysisMode, target: String?): List<String> = when (mode) {
        AnalysisMode.NARRATIVE -> emptyList()
        AnalysisMode.COUNTS_ONLY -> listOf(
            "how many total objects?",
            "how many ${target ?: "objects"}?"
        )

        AnalysisMode.DETAILED -> listOf(
            "what objects are there?",
            "where is the ${target ?: "object"}?",
            "what is the position of the ${target ?: "object"}?",
            "what relations are there?"
        )
    }

    /**
     * Builds a fallback log entry for failed analysis attempts.
     */
    private fun failedEntry(
        file: String,
        mode: AnalysisMode,
        target: String?,
        useServerLLM: Boolean,
        exception: Throwable,
        sensor: String? = null
    ): LogEntry {
        return LogEntry(
            run_id = "invalid",
            model_name = CURRENT_MODEL_NAME,
            llm = if (useServerLLM) "server" else "local",
            source_type = when {
                file.endsWith(".json") -> "json"
                file.endsWith(".mp4") -> "video"
                else -> "image"
            },
            file_name = file,
            mode = mode,
            target_label = target,
            sensor_file = sensor,
            cv_duration_ms = 0,
            llm_duration_ms = 0,
            total_duration_ms = 0,
            prompt_length = 0,
            response_length = 0,
            scene_json = "",
            prompt = "",
            llm_response = "",
            error = exception.message ?: "Unknown error"
        )
    }

    /**
     * Evaluates an image using the given configuration.
     */
    private suspend fun evaluateImage(
        context: Context,
        assetName: String,
        mode: AnalysisMode,
        target: String?,
        userPrompt: String?,
        useServerLLM: Boolean,
        runId: String,
        localClient: LlmChatClient,
        localAnalyzer: ImageSceneAnalyzer,
        serverAnalyzer: RemoteSceneResultAdapter
    ): LogEntry = withContext(Dispatchers.IO) {
        val input = context.assets.open("eval/images/$assetName")
        val bitmap = BitmapFactory.decodeStream(input)

        val startCV = System.currentTimeMillis()
        val result = if (useServerLLM)
            serverAnalyzer.analyzeImage(bitmap, mode, target)
        else
            localAnalyzer.analyze(bitmap, mode)
        val endCV = System.currentTimeMillis()

        input.close()
        bitmap.recycle()

        buildLogEntry(
            result.summary, SourceType.IMAGE, assetName, mode, target, null,
            useServerLLM, runId, userPrompt, localClient, endCV - startCV
        )
    }

    /**
     * Evaluates a video with optional sensor data.
     */
    private suspend fun evaluateVideo(
        context: Context,
        assetName: String,
        mode: AnalysisMode,
        sensorJson: String?,
        userPrompt: String?,
        useServerLLM: Boolean,
        runId: String,
        localClient: LlmChatClient,
        localAnalyzer: VideoAnalyzer,
        serverAnalyzer: RemoteSceneResultAdapter
    ): LogEntry = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("temp_video", ".mp4", context.cacheDir).apply {
            outputStream().use { output ->
                context.assets.open("eval/videos/$assetName").use { input ->
                    input.copyTo(output)
                }
            }
        }
        val uri = Uri.fromFile(tempFile)

        val sensors = sensorJson?.let {
            val json = context.assets.open("eval/sensors/$it").bufferedReader()
                .use(BufferedReader::readText)
            SensorJsonParser.parse(json)
        } ?: emptyList()

        val startCV = System.currentTimeMillis()
        val result = if (useServerLLM)
            serverAnalyzer.analyzeVideo(uri, mode, sensors)
        else
            localAnalyzer.analyze(uri, mode, sensors)
        val endCV = System.currentTimeMillis()

        val entry = buildLogEntry(
            sceneJson = result.summary,
            source = SourceType.VIDEO,
            fileName = assetName,
            mode = mode,
            target = null,
            sensor = sensorJson,
            useServerLLM = useServerLLM,
            runId = runId,
            userPrompt = userPrompt,
            localClient = localClient,
            cvDuration = endCV - startCV
        )

        tempFile.delete()
        return@withContext entry
    }

    /**
     * Evaluates a static JSON scene file.
     */
    private suspend fun evaluateJson(
        context: Context,
        assetName: String,
        mode: AnalysisMode,
        userPrompt: String?,
        useServerLLM: Boolean,
        runId: String,
        localClient: LlmChatClient,
        analyzer: JsonSceneAnalyzer
    ): LogEntry = withContext(Dispatchers.IO) {
        val jsonContent = context.assets
            .open("eval/json/$assetName")
            .bufferedReader()
            .use { it.readText() }

        val tempFile = File.createTempFile("temp_json", ".json", context.cacheDir).apply {
            writeText(jsonContent)
        }

        val uri = Uri.fromFile(tempFile)

        val startCV = System.currentTimeMillis()
        val result = analyzer.analyze(uri, mode)
        val endCV = System.currentTimeMillis()

        tempFile.delete()

        buildLogEntry(
            sceneJson = result.summary,
            source = SourceType.JSON,
            fileName = assetName,
            mode = mode,
            target = null,
            sensor = null,
            useServerLLM = useServerLLM,
            runId = runId,
            userPrompt = userPrompt,
            localClient = localClient,
            cvDuration = endCV - startCV
        )
    }

    /**
     * Combines CV and LLM results into a [LogEntry].
     */
    private suspend fun buildLogEntry(
        sceneJson: String,
        source: SourceType,
        fileName: String,
        mode: AnalysisMode,
        target: String?,
        sensor: String?,
        useServerLLM: Boolean,
        runId: String,
        userPrompt: String?,
        localClient: LlmChatClient,
        cvDuration: Long
    ): LogEntry {
        val emptyScene = sceneJson.trim().isEmpty()
        val startAll = System.currentTimeMillis()

        val prompt = if (emptyScene) "" else PromptBuilder.build(
            scene = sceneJson,
            source = source,
            mode = mode,
            labels = target?.let { setOf(it) },
            userPrompt = userPrompt
        )

        val finalPrompt = if (useServerLLM || emptyScene) prompt
        else PromptLimiter.truncateAfterJson(prompt)

        val startLLM = System.currentTimeMillis()
        val response = if (!emptyScene) {
            if (useServerLLM) LLMServerClient.generate(finalPrompt)
            else localClient.generateDirectResponse(finalPrompt)
        } else ""
        val endLLM = System.currentTimeMillis()

        return LogEntry(
            run_id = runId,
            model_name = CURRENT_MODEL_NAME,
            llm = if (useServerLLM) "server" else "local",
            source_type = source.name.lowercase(),
            file_name = fileName,
            mode = mode,
            target_label = target,
            sensor_file = sensor,
            cv_duration_ms = cvDuration,
            llm_duration_ms = if (emptyScene) 0 else endLLM - startLLM,
            total_duration_ms = System.currentTimeMillis() - startAll,
            prompt_length = finalPrompt.length,
            response_length = response.length,
            scene_json = sceneJson,
            prompt = finalPrompt,
            llm_response = response,
            error = null
        )
    }
}
