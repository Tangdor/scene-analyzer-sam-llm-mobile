package com.example.detectask.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.util.Base64
import com.example.detectask.data.SensorSample
import com.example.detectask.domain.SceneDescriber
import com.example.detectask.ml.llm.LLMManager
import com.example.detectask.tracking.TrackingManager
import com.example.detectask.ui.assistant.AssistantActivity
import com.example.detectask.ui.views.DetectionBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * A scene analyzer that delegates image and video processing to a remote server.
 *
 * This class handles communication with an external segmentation service, prepares the request
 * data, and interprets the server's JSON response into usable application objects.
 *
 * @constructor Initializes the remote analyzer with the given Android [context].
 * @param context Used for accessing media and external file storage.
 */
class RemoteSceneResultAdapter(private val context: Context) : BaseSceneAnalyzer() {

    private val client = OkHttpClient()
    private val serverUrl = "http://192.168.188.20:5000/segment"

    /**
     * Sends an image to the remote server and performs scene analysis based on the [mode] and optional [target].
     *
     * The result is interpreted and returned as a [SceneAnalysisResult], including the JSON response and a file path.
     *
     * @param bitmap The image to analyze.
     * @param mode The selected analysis mode.
     * @param target Optional class label to focus detection on.
     * @return A structured [SceneAnalysisResult] from the server's response.
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        mode: AssistantActivity.AnalysisMode,
        target: String?
    ): SceneAnalysisResult = withContext(Dispatchers.IO) {
        val boxes = sendBitmapToServer(bitmap, target)
        val labels = boxes.map { "${it.label} (%.2f)".format(it.score) }

        val scene = when (mode) {
            AssistantActivity.AnalysisMode.DETAILED,
            AssistantActivity.AnalysisMode.NARRATIVE ->
                SceneDescriber.describeNarrativeMinimalJsonFromBoxes(
                    boxes,
                    bitmap.width.toFloat(),
                    bitmap.height.toFloat()
                )

            AssistantActivity.AnalysisMode.COUNTS_ONLY ->
                SceneDescriber.describeCountsOnlyMinimal(
                    boxes.groupingBy { it.label }.eachCount()
                )
        }

        lastSceneSummary = safelyTrim(scene, 800)

        val json = JSONObject().apply {
            val array = JSONArray()
            for (box in boxes) {
                val obj = JSONObject()
                obj.put("label", box.label)
                obj.put("score", box.score)
                val rect = JSONObject()
                rect.put("x", box.rect.left)
                rect.put("y", box.rect.top)
                rect.put("width", box.rect.width())
                rect.put("height", box.rect.height())
                obj.put("box", rect)
                array.put(obj)
            }
            put("objects", array)
        }

        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "server_image_analysis_${System.currentTimeMillis()}.json"
        ).apply {
            writeText(json.toString(2))
        }

        SceneAnalysisResult(
            summary = scene,
            boxes = boxes,
            json = scene,
            filePath = file.absolutePath,
            labels = labels
        )
    }

    /**
     * Sends a video to the server by extracting and processing frames at regular intervals.
     *
     * Each frame is matched with the closest [SensorSample] by timestamp to account for orientation.
     * Tracking is applied over time to produce a coherent scene description.
     *
     * @param uri The URI of the video to analyze.
     * @param mode The desired analysis mode.
     * @param sensorSamples A list of sensor data used for orientation adjustment.
     * @return A [SceneAnalysisResult] containing analysis results, tracked objects, and file path.
     * @throws IllegalArgumentException If video duration cannot be determined.
     */
    suspend fun analyzeVideo(
        uri: Uri,
        mode: AssistantActivity.AnalysisMode,
        sensorSamples: List<SensorSample>
    ): SceneAnalysisResult = withContext(Dispatchers.IO) {

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)

        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: throw IllegalArgumentException("Could not determine video duration.")

        val stepMicros = 100_000L // 10 fps
        val totalSteps = durationMs * 1000 / stepMicros

        TrackingManager.reset()

        var lastFrameWidth = 1f
        var lastFrameHeight = 1f

        for (i in 0 until totalSteps.toInt()) {
            val frameTimeUs = i * stepMicros
            val frame = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue

            lastFrameWidth = frame.width.toFloat()
            lastFrameHeight = frame.height.toFloat()

            val timestampMs = frameTimeUs / 1000
            val rotation = sensorSamples.minByOrNull { abs(it.timestamp - timestampMs) }?.gyro
                ?: floatArrayOf(0f, 0f, 0f)

            val boxes = sendBitmapToServer(frame, null)
            if (boxes.isEmpty()) continue

            TrackingManager.update(boxes, i, rotation, frame)
        }

        retriever.release()

        val allTracks = TrackingManager.getAllTrackedAndRecentlyLost()
        val tracked = allTracks.filter { it.frameSeenCount >= 2 }

        val boxes = tracked.map {
            DetectionBox(it.label, 1.0f, it.predictRect())
        }

        val scene = when (mode) {
            AssistantActivity.AnalysisMode.DETAILED,
            AssistantActivity.AnalysisMode.NARRATIVE ->
                SceneDescriber.describeNarrativeMinimalJson(tracked, lastFrameWidth, lastFrameHeight)

            AssistantActivity.AnalysisMode.COUNTS_ONLY ->
                SceneDescriber.describeCountsOnlyMinimal(boxes.groupingBy { it.label }.eachCount())
        }

        val json = JSONObject().apply {
            val array = JSONArray()
            for (obj in tracked) {
                val entry = JSONObject()
                entry.put("id", obj.id)
                entry.put("label", obj.label)
                entry.put("firstSeenFrame", obj.firstSeenFrame)
                entry.put("lastSeenFrame", obj.lastSeenFrame)
                val box = obj.predictRect()
                val boxJson = JSONObject()
                boxJson.put("x", box.left)
                boxJson.put("y", box.top)
                boxJson.put("width", box.width())
                boxJson.put("height", box.height())
                entry.put("box", boxJson)
                array.put(entry)
            }
            put("objects", array)
            put("scene", scene)
        }

        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "video_remote_analysis_${System.currentTimeMillis()}.json"
        ).apply {
            writeText(json.toString(2))
        }

        return@withContext SceneAnalysisResult(
            summary = scene,
            boxes = boxes,
            json = json.toString(2),
            filePath = file.absolutePath
        )
    }

    /**
     * Sends a [Bitmap] to the remote server for segmentation and returns detected objects.
     *
     * @param bitmap The image to send.
     * @param target Optional class name to prioritize in the server analysis.
     * @return A list of [DetectionBox]es returned from the server.
     * @throws Exception If the server response is empty or invalid.
     */
    private fun sendBitmapToServer(bitmap: Bitmap, target: String?): List<DetectionBox> {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        val json = JSONObject().apply {
            put("image", base64)
            if (!target.isNullOrBlank()) put("target", target)
        }

        val request = Request.Builder()
            .url(serverUrl)
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), json.toString()))
            .build()

        client.newCall(request).execute().use { response ->
            val result = response.body?.string() ?: throw Exception("Empty server response")
            val root = JSONObject(result)
            val array = root.optJSONArray("objects") ?: JSONArray()
            return (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val label = obj.getString("label")
                val score = obj.getDouble("score").toFloat()
                val box = obj.getJSONObject("box")
                val x = box.getDouble("x").toFloat()
                val y = box.getDouble("y").toFloat()
                val w = box.getDouble("width").toFloat()
                val h = box.getDouble("height").toFloat()
                DetectionBox(label, score, RectF(x, y, x + w, y + h))
            }
        }
    }

    /**
     * Generates a response from the language model based on the given [input] and [mode].
     *
     * @param input The prompt for the language model.
     * @param mode The analysis mode context (not directly used).
     * @return The LLM's generated response.
     */
    suspend fun generateResponse(input: String, mode: AssistantActivity.AnalysisMode): String =
        withContext(Dispatchers.IO) {
            LLMManager.generate(input)
        }

    /**
     * Generates a narrative-style description of the scene.
     *
     * Internally uses [generateResponse] with a predefined prompt.
     *
     * @return A high-level narrative interpretation of the scene.
     */
    suspend fun generateNarrativeDescription(): String =
        withContext(Dispatchers.IO) {
            generateResponse("What does the scene show?", AssistantActivity.AnalysisMode.NARRATIVE)
        }
}
