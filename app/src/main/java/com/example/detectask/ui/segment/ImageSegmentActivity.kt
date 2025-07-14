package com.example.detectask.ui.segment

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.detectask.ui.views.DetectionBox
import com.example.detectask.R
import com.example.detectask.domain.SceneDescriber
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
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Activity allowing image capture and segmentation either locally or via a server.
 * Displays results with bounding boxes, labels, and optional masks.
 */
class ImageSegmentActivity : AppCompatActivity() {

    private lateinit var captureButton: Button
    private lateinit var serverButton: Button
    private lateinit var localButton: Button
    private lateinit var resultImageView: ImageView
    private lateinit var labelsTextView: TextView
    private lateinit var promptEditText: EditText

    private val SERVER_URL = "http://192.168.188.20:5000/segment"
    private val client = OkHttpClient()

    private val CAMERA_REQUEST_CODE = 101
    private val CAMERA_PERMISSION_CODE = 102
    private var photoUri: Uri? = null
    private var lastCapturedBitmap: Bitmap? = null

    private lateinit var yolo: YoloSegmentor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_segment)

        captureButton = findViewById(R.id.captureImageButton)
        serverButton = findViewById(R.id.serverButton)
        localButton = findViewById(R.id.localButton)
        resultImageView = findViewById(R.id.resultImageView)
        labelsTextView = findViewById(R.id.labelsTextView)
        promptEditText = findViewById(R.id.promptEditText)

        yolo = YoloSegmentor(this)

        captureButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                dispatchTakePictureIntent()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_CODE
                )
            }
        }

        serverButton.setOnClickListener {
            lastCapturedBitmap?.let { runServerInference(it) }
        }

        localButton.setOnClickListener {
            lastCapturedBitmap?.let { runLocalInference(it) }
        }
    }

    /**
     * Starts camera intent to capture an image and save to a temporary file.
     */
    private fun dispatchTakePictureIntent() {
        val photoFile = createImageFile()
        photoUri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile)

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        }
        startActivityForResult(intent, CAMERA_REQUEST_CODE)
    }

    /**
     * Creates a temporary image file in the app's external pictures directory.
     */
    private fun createImageFile(): File {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "captured")
        dir.mkdirs()
        return File.createTempFile("captured_", ".jpg", dir)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            photoUri?.let {
                val stream: InputStream? = contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(stream)
                stream?.close()
                bitmap?.let { bmp ->
                    val rotatedBitmap = rotateBitmapIfRequired(bmp, it)
                    lastCapturedBitmap = rotatedBitmap
                    resultImageView.setImageBitmap(rotatedBitmap)
                    labelsTextView.text = "Bild aufgenommen"
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * Rotates bitmap according to EXIF orientation data if needed.
     */
    private fun rotateBitmapIfRequired(bitmap: Bitmap, uri: Uri): Bitmap {
        val inputStream = contentResolver.openInputStream(uri) ?: return bitmap
        val exif = ExifInterface(inputStream)
        val rotation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (rotation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Runs YOLO segmentation locally on the given bitmap with optional target label filtering.
     * Draws bounding boxes and labels on the image and updates UI.
     */
    private fun runLocalInference(bitmap: Bitmap) {
        val target = promptEditText.text.toString().trim()
        val detections = if (target.isNotEmpty()) {
            yolo.detect(bitmap, targetLabel = target)
        } else {
            yolo.detect(bitmap)
        }

        val resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        val boxPaint = Paint().apply {
            color = Color.BLUE
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
        }

        val labels = mutableListOf<String>()
        val boxes = mutableListOf<DetectionBox>()

        for (detection in detections) {
            if (detection is DetectionBox) {
                canvas.drawRect(detection.rect, boxPaint)
                canvas.drawText("${detection.label} %.2f".format(detection.score), detection.rect.left, detection.rect.top - 10, textPaint)
                labels.add("${detection.label} %.2f".format(detection.score))
                boxes.add(detection)
            }
        }

        val scene = SceneDescriber.describeSceneSimple(boxes, bitmap.width.toFloat(), bitmap.height.toFloat())

        resultImageView.setImageBitmap(resultBitmap)
        labelsTextView.text = "Lokal erkannt: ${labels.joinToString()}\n\n$scene"
    }

    /**
     * Sends the image and optional target label to the remote server for segmentation,
     * then draws results including masks, bounding boxes, and labels.
     */
    private fun runServerInference(bitmap: Bitmap) {
        val base64 = bitmapToBase64(bitmap)
        val target = promptEditText.text.toString().trim()
        val json = JSONObject().apply {
            put("image", base64)
            if (target.isNotEmpty()) put("target", target)
        }

        val request = Request.Builder()
            .url(SERVER_URL)
            .post(RequestBody.Companion.create("application/json".toMediaTypeOrNull(), json.toString()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { labelsTextView.text = "Fehler: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val obj = JSONObject(body)
                    val objects = obj.optJSONArray("objects") ?: return

                    val boxes = mutableListOf<RectF>()
                    val labels = mutableListOf<String>()
                    val detectionBoxes = mutableListOf<DetectionBox>()
                    val masks = mutableListOf<List<PointF>>()

                    for (i in 0 until objects.length()) {
                        val item = objects.getJSONObject(i)
                        val label = item.getString("label")
                        val score = item.getDouble("score").toFloat()
                        labels.add("$label %.2f".format(score))

                        val box = item.getJSONObject("box")
                        val x = box.getDouble("x").toFloat()
                        val y = box.getDouble("y").toFloat()
                        val w = box.getDouble("width").toFloat()
                        val h = box.getDouble("height").toFloat()
                        val rect = RectF(x, y, x + w, y + h)
                        boxes.add(rect)
                        detectionBoxes.add(DetectionBox(label, score, rect))

                        if (item.has("mask")) {
                            val pointList = mutableListOf<PointF>()
                            val points = item.getJSONObject("mask").getJSONArray("points")
                            for (j in 0 until points.length()) {
                                val point = points.getJSONArray(j)
                                pointList.add(
                                    PointF(
                                        point.getDouble(0).toFloat(),
                                        point.getDouble(1).toFloat()
                                    )
                                )
                            }
                            masks.add(pointList)
                        }
                    }

                    val resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = Canvas(resultBitmap)
                    val boxPaint = Paint().apply {
                        color = Color.BLUE
                        strokeWidth = 4f
                        style = Paint.Style.STROKE
                    }
                    val textPaint = Paint().apply {
                        color = Color.WHITE
                        textSize = 36f
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    val maskPaint = Paint().apply {
                        colorFilter = PorterDuffColorFilter(
                            Color.argb(100, 138, 43, 226),
                            PorterDuff.Mode.SRC_IN
                        )
                    }

                    for (points in masks) {
                        val maskBmp = createMaskBitmap(bitmap.width, bitmap.height, points)
                        canvas.drawBitmap(maskBmp, 0f, 0f, maskPaint)
                    }

                    for ((i, rect) in boxes.withIndex()) {
                        canvas.drawRect(rect, boxPaint)
                        canvas.drawText(labels[i], rect.left, rect.top - 10, textPaint)
                    }

                    val scene = SceneDescriber.describeSceneSimple(detectionBoxes, bitmap.width.toFloat(), bitmap.height.toFloat())

                    runOnUiThread {
                        resultImageView.setImageBitmap(resultBitmap)
                        labelsTextView.text = "Lokal erkannt: ${labels.joinToString()}\n\n$scene"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        labelsTextView.text = "Fehler beim Verarbeiten"
                    }
                }
            }
        })
    }

    /**
     * Creates a bitmap mask from a polygon defined by a list of points.
     */
    private fun createMaskBitmap(width: Int, height: Int, points: List<PointF>): Bitmap {
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        val path = Path()
        points.firstOrNull()?.let { path.moveTo(it.x, it.y) }
        points.drop(1).forEach { path.lineTo(it.x, it.y) }
        path.close()
        val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, paint)
        return maskBitmap
    }

    /**
     * Converts a bitmap to a Base64-encoded JPEG string.
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
