package com.example.detectask.ui.recording

import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.detectask.R
import com.example.detectask.data.SensorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Activity for video recording with cameraX, capturing sensor data alongside video.
 */
class VideoRecordingActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var recordButton: Button
    private lateinit var sensorLogger: SensorLogger
    private lateinit var cameraExecutor: ExecutorService

    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording_layout)

        viewFinder = findViewById(R.id.viewFinder)
        recordButton = findViewById(R.id.recordButton)
        sensorLogger = SensorLogger(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        startCamera()

        recordButton.setOnClickListener {
            if (recording) stopRecording() else startRecording()
        }
    }

    /**
     * Initializes CameraX preview and prepares video capture use case.
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture!!)
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Starts recording video and sensor data.
     * Saves video file with timestamped name in Downloads directory.
     */
    private fun startRecording() {
        val name = SimpleDateFormat(
            "dd.MM.yyyy_HH-mm-ss",
            Locale.GERMANY
        ).format(System.currentTimeMillis())
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        }).build()

        val videoCapture = this.videoCapture ?: return
        currentRecording = videoCapture.output
            .prepareRecording(this, mediaStoreOutput)
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        sensorLogger.start()
                        recording = true
                        recordButton.text = "Stop Recording"
                        Log.d("Recording", "Started")
                    }

                    is VideoRecordEvent.Finalize -> {
                        sensorLogger.stop()
                        recording = false
                        recordButton.text = "Start Recording"
                        lifecycleScope.launch { saveSensorData() }

                        if (recordEvent.hasError()) {
                            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show()
                            Log.d("Recording", "Saved to: ${recordEvent.outputResults.outputUri}")
                        }
                    }
                }
            }
    }

    /**
     * Stops the current video recording.
     */
    private fun stopRecording() {
        currentRecording?.stop()
        currentRecording = null
    }

    /**
     * Saves collected sensor data as JSON file in Downloads directory after recording finishes.
     */
    private suspend fun saveSensorData() {
        withContext(Dispatchers.IO) {
            val baseDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val timestamp = System.currentTimeMillis()
            val formatter =
                SimpleDateFormat("dd.MM.yyyy_HH-mm-ss", Locale.GERMANY)
            val formattedName = formatter.format(java.util.Date(timestamp))

            val fileName = "sensor_data_video_$formattedName.json"
            val sensorFile = java.io.File(baseDir, fileName)

            val json = sensorLogger.samples.joinToString(
                separator = ",\n",
                prefix = "[\n",
                postfix = "\n]"
            ) { sample ->
                """
            {
              "timestamp": ${sample.timestamp},
              "gyro": [${sample.gyro.joinToString()}],
              "accel": [${sample.accel.joinToString()}]
            }
            """.trimIndent()
            }

            sensorFile.writeText(json)
            Log.d("SensorLog", "✅ Sensor data saved to: ${sensorFile.absolutePath}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
