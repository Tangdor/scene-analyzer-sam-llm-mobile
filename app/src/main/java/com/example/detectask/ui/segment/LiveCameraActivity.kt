package com.example.detectask.ui.segment

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.detectask.ui.views.DetectionBox
import com.example.detectask.ui.views.DetectionMask
import com.example.detectask.ui.views.OverlayView
import com.example.detectask.R
import com.example.detectask.ml.yolo.YoloSegmentor
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Activity showing live camera feed and performing real-time segmentation
 * using either a local YOLO model or a remote server.
 *
 * The results are overlaid on the camera preview.
 */
class LiveCameraActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var overlayView: OverlayView
    private lateinit var promptEditText: EditText
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageAnalysis: ImageAnalysis

    private val YOLO_SERVER_URL = "http://192.168.188.20:5000/segment"
    private val client = OkHttpClient()
    private val CAMERA_PERMISSION_CODE = 101

    private var frameWidth = 640f
    private var frameHeight = 640f

    private var mode = "server"
    private lateinit var localDetector: YoloSegmentor

    @Volatile
    private var isProcessingFrame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_camera)

        mode = intent.getStringExtra("mode") ?: "server"
        if (mode == "local") {
            localDetector = YoloSegmentor(this)
        }

        textureView = findViewById(R.id.textureView)
        overlayView = findViewById(R.id.overlayView)
        promptEditText = findViewById(R.id.livePromptEditText)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            textureView.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }
    }

    /**
     * Checks if camera permission has been granted.
     */
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts camera preview, image analysis and binds lifecycle.
     * Handles frame processing for segmentation.
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetResolution(Size(640, 640))
                .build()

            val surfaceProvider = Preview.SurfaceProvider { request ->
                val surface = Surface(textureView.surfaceTexture)
                request.provideSurface(surface, cameraExecutor) {}
            }

            preview.setSurfaceProvider(surfaceProvider)

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (!isProcessingFrame) {
                    isProcessingFrame = true
                    val bitmap = textureView.bitmap
                    imageProxy.close()

                    bitmap?.let {
                        frameWidth = textureView.width.toFloat()
                        frameHeight = textureView.height.toFloat()

                        val startTime = System.currentTimeMillis()
                        if (mode == "server") {
                            sendFrameToYolo(it) {
                                val duration = System.currentTimeMillis() - startTime
                                Log.d("LiveCam", "Server Inferenzdauer: ${duration}ms")
                                isProcessingFrame = false
                            }
                        } else {
                            runLocalYolo(it) {
                                val duration = System.currentTimeMillis() - startTime
                                Log.d("LiveCam", "Lokal Inferenzdauer: ${duration}ms")
                                isProcessingFrame = false
                            }
                        }
                    } ?: run {
                        isProcessingFrame = false
                    }
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Sends current camera frame to remote YOLO server for segmentation.
     * Updates overlay with results.
     * @param bitmap the current frame
     * @param onFinish callback invoked after processing finishes
     */
    private fun sendFrameToYolo(bitmap: Bitmap, onFinish: () -> Unit) {
        val base64Image = bitmapToBase64(bitmap)
        val target = promptEditText.text.toString().trim()
        val json = JSONObject().put("image", base64Image)
        if (target.isNotEmpty()) {
            json.put("target", target)
        }

        val body = RequestBody.Companion.create(
            "application/json; charset=utf-8".toMediaTypeOrNull(),
            json.toString()
        )

        val request = Request.Builder()
            .url(YOLO_SERVER_URL)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                runOnUiThread { onFinish() }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: return runOnUiThread { onFinish() }

                try {
                    val jsonResponse = JSONObject(responseBody)
                    val objects = jsonResponse.getJSONArray("objects")

                    val scaleX = frameWidth / bitmap.width.toFloat()
                    val scaleY = frameHeight / bitmap.height.toFloat()

                    val boxes = mutableListOf<DetectionBox>()
                    val masks = mutableListOf<DetectionMask>()

                    for (i in 0 until objects.length()) {
                        val obj = objects.getJSONObject(i)
                        val label = obj.getString("label")
                        val score = obj.getDouble("score").toFloat()

                        val box = obj.getJSONObject("box")
                        val x = box.getDouble("x").toFloat() * scaleX
                        val y = box.getDouble("y").toFloat() * scaleY
                        val w = box.getDouble("width").toFloat() * scaleX
                        val h = box.getDouble("height").toFloat() * scaleY

                        boxes.add(DetectionBox(label, score, RectF(x, y, x + w, y + h)))

                        if (obj.has("mask")) {
                            val pointsArray = obj.getJSONObject("mask").getJSONArray("points")
                            val points = mutableListOf<PointF>()
                            for (j in 0 until pointsArray.length()) {
                                val pair = pointsArray.getJSONArray(j)
                                val px = pair.getDouble(0).toFloat() * scaleX
                                val py = pair.getDouble(1).toFloat() * scaleY
                                points.add(PointF(px, py))
                            }
                            masks.add(DetectionMask(label, score, points))
                        }
                    }

                    runOnUiThread {
                        overlayView.updateDetections(boxes, masks)
                        onFinish()
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread { onFinish() }
                }
            }
        })
    }

    /**
     * Runs local YOLO segmentation on current frame.
     * Updates overlay with bounding boxes.
     * @param bitmap current frame
     * @param onFinish callback after processing
     */
    private fun runLocalYolo(bitmap: Bitmap, onFinish: () -> Unit) {
        val target = promptEditText.text.toString().trim()
        val results = if (target.isNotEmpty()) {
            localDetector.detect(bitmap, targetLabel = target)
        } else {
            localDetector.detect(bitmap)
        }

        val boxes = mutableListOf<DetectionBox>()
        for (item in results) {
            if (item is DetectionBox) {
                boxes.add(item)
            }
        }

        runOnUiThread {
            overlayView.updateDetections(boxes, emptyList())
            onFinish()
        }
    }

    /**
     * Converts bitmap image to Base64 encoded JPEG string.
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    override fun onDestroy() {
        super.onDestroy()
        imageAnalysis.clearAnalyzer()
        cameraExecutor.shutdown()
    }
}
