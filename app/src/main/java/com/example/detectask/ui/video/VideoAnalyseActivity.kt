package com.example.detectask.ui.video

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.detectask.R
import com.example.detectask.data.SensorJsonParser
import com.example.detectask.data.SensorSample
import com.example.detectask.domain.usecase.VideoAnalyzer
import com.example.detectask.ml.llm.LLMManager
import com.example.detectask.ui.assistant.AssistantActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader

/**
 * Activity to select, play, and analyze video files.
 * Supports optional sensor JSON input for enhanced analysis.
 */
class VideoAnalyseActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var selectVideoButton: Button
    private lateinit var analyzeVideoButton: Button
    private lateinit var selectJsonButton: Button
    private lateinit var analysisResultView: TextView

    private var videoUri: Uri? = null
    private var sensorJsonUri: Uri? = null
    private lateinit var videoAnalyzer: VideoAnalyzer

    private lateinit var sensorManager: SensorManager
    private val totalRotation = FloatArray(3)

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                for (i in 0..2) {
                    totalRotation[i] += event.values[i]
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val VIDEO_PICK_CODE = 5678
    private val JSON_PICK_CODE = 5679

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_analyse)

        videoView = findViewById(R.id.videoView)
        selectVideoButton = findViewById(R.id.selectVideoButton)
        analyzeVideoButton = findViewById(R.id.analyzeVideoButton)
        selectJsonButton = findViewById(R.id.selectSensorButton)
        analysisResultView = findViewById(R.id.analysisResultView)

        videoAnalyzer = VideoAnalyzer(this)
        LLMManager.init(videoAnalyzer.llm)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager.registerListener(
            gyroListener,
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
            SensorManager.SENSOR_DELAY_GAME
        )

        selectVideoButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply { type = "video/*" }
            startActivityForResult(intent, VIDEO_PICK_CODE)
        }

        selectJsonButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/json"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Choose sensor JSON"), JSON_PICK_CODE)
        }

        analyzeVideoButton.setOnClickListener {
            videoUri?.let { analyzeVideoWithOptionalSensor(it) }
                ?: showToast("Please select a video first.")
        }
    }

    /**
     * Analyzes the video using the optional sensor data if provided.
     * Updates UI with analysis results.
     *
     * @param uri Uri of the video to analyze.
     */
    private fun analyzeVideoWithOptionalSensor(uri: Uri) {
        analysisResultView.text = if (sensorJsonUri != null) {
            "📹 Analyzing video with sensor data..."
        } else {
            "📹 Analyzing video (no sensor data selected)..."
        }

        lifecycleScope.launch {
            val sensorSamples: List<SensorSample> = withContext(Dispatchers.IO) {
                sensorJsonUri?.let { jsonUri ->
                    try {
                        contentResolver.openInputStream(jsonUri)?.bufferedReader()?.use(BufferedReader::readText)
                            ?.let { SensorJsonParser.parse(it) }
                    } catch (e: Exception) {
                        Log.e("VideoAnalyse", "⚠️ Failed to read sensor data: ${e.message}")
                        emptyList()
                    }
                } ?: emptyList()
            }

            try {
                val result = withContext(Dispatchers.IO) {
                    videoAnalyzer.analyze(uri, AssistantActivity.AnalysisMode.DETAILED, sensorSamples)
                }

                LLMManager.setSceneWithMode(
                    scene = result.summary,
                    source = AssistantActivity.SourceType.VIDEO,
                    mode = AssistantActivity.AnalysisMode.DETAILED
                )

                analysisResultView.text = buildString {
                    append(result.summary.ifBlank { "⚠️ No scene summary generated." })
                    append("\n\n📄 JSON saved to:\n${result.filePath}")
                }

            } catch (e: Exception) {
                Log.e("VideoAnalyseActivity", "❌ Analysis exception: ${e.message}", e)
                analysisResultView.text = buildString {
                    append("❌ Analysis failed.\n")
                    append("Reason: ${e.message ?: "Unknown error"}\n")
                    append("Try a different video or check detection model.")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(gyroListener)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            VIDEO_PICK_CODE -> {
                if (resultCode == RESULT_OK) {
                    videoUri = data?.data
                    videoView.setVideoURI(videoUri)
                    videoView.setOnPreparedListener { it.isLooping = true }
                    videoView.start()
                }
            }

            JSON_PICK_CODE -> {
                if (resultCode == RESULT_OK) {
                    sensorJsonUri = data?.data
                    showToast("📁 Sensor log selected.")
                }
            }
        }
    }

    /**
     * Shows a toast message.
     *
     * @param message Text to show
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
