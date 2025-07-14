package com.example.detectask.ui.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.detectask.R
import com.example.detectask.data.SensorJsonParser
import com.example.detectask.data.SensorSample
import com.example.detectask.domain.usecase.*
import com.example.detectask.ml.llm.LLMManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

/**
 * Activity providing a chat-based assistant UI for scene analysis.
 *
 * Supports image capture, video selection, and JSON input as scene sources.
 * Allows toggling analysis mode, text-to-speech, and backend LLM source.
 */
class AssistantActivity : AppCompatActivity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var cameraButton: ImageButton
    private lateinit var videoButton: ImageButton
    private lateinit var jsonButton: ImageButton
    private lateinit var modeToggleButton: Button
    private lateinit var clearSceneButton: Button
    private lateinit var ttsToggleButton: Button
    private lateinit var sourceToggleButton: Button
    private lateinit var contextStatusView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var targetObjectEditText: EditText

    private lateinit var imageSceneAnalyzer: ImageSceneAnalyzer
    private lateinit var jsonAnalyzer: JsonSceneAnalyzer
    private lateinit var videoAnalyzer: VideoAnalyzer
    private lateinit var serverAnalyzer: RemoteSceneResultAdapter

    private lateinit var tts: TextToSpeech
    private var ttsEnabled = false

    private val CAMERA_REQUEST_CODE = 1234
    private val VIDEO_PICK_CODE = 9001
    private val JSON_PICK_CODE = 9002
    private var photoFile: File? = null
    private var pendingVideoUri: Uri? = null

    /**
     * Supported scene source types.
     */
    enum class SourceType { IMAGE, VIDEO, JSON }

    /**
     * Supported analysis modes.
     */
    enum class AnalysisMode { DETAILED, COUNTS_ONLY, NARRATIVE }

    private var lastSource: SourceType? = null
    private var analysisMode: AnalysisMode = AnalysisMode.DETAILED
    private var useServerAnalyzer = false
    private var showYoloLabelsInChat = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant)

        // Initialize UI components
        chatContainer = findViewById(R.id.chatContainer)
        inputField = findViewById(R.id.inputField)
        targetObjectEditText = findViewById(R.id.targetObjectEditText)
        sendButton = findViewById(R.id.sendButton)
        cameraButton = findViewById(R.id.cameraButton)
        videoButton = findViewById(R.id.videoButton)
        jsonButton = findViewById(R.id.jsonButton)
        modeToggleButton = findViewById(R.id.modeToggleButton)
        clearSceneButton = findViewById(R.id.clearSceneButton)
        ttsToggleButton = findViewById(R.id.ttsToggleButton)
        sourceToggleButton = findViewById(R.id.sourceToggleButton)
        contextStatusView = findViewById(R.id.contextStatusView)
        progressBar = findViewById(R.id.progressBar)

        // Initialize analyzers and LLM manager
        imageSceneAnalyzer = ImageSceneAnalyzer(this)
        jsonAnalyzer = JsonSceneAnalyzer(contentResolver)
        videoAnalyzer = VideoAnalyzer(this)
        serverAnalyzer = RemoteSceneResultAdapter(this)

        LLMManager.init(imageSceneAnalyzer.llmClient)
        LLMManager.useServerLLM = useServerAnalyzer

        // Initialize Text-To-Speech engine
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts.language = Locale.US }

        sendButton.setOnClickListener { handleSend() }

        modeToggleButton.setOnClickListener {
            analysisMode = when (analysisMode) {
                AnalysisMode.DETAILED -> AnalysisMode.COUNTS_ONLY
                AnalysisMode.COUNTS_ONLY -> AnalysisMode.NARRATIVE
                AnalysisMode.NARRATIVE -> AnalysisMode.DETAILED
            }
            modeToggleButton.text = "Mode: ${analysisMode.name.replace("_", " ").capitalize()}"
            Toast.makeText(this, "Switched to ${modeToggleButton.text}", Toast.LENGTH_SHORT).show()
        }

        clearSceneButton.setOnClickListener {
            LLMManager.clearScene(resetLlm = true)
            lastSource = null
            updateContextStatus(null)
            Toast.makeText(this, "Scene context cleared and LLM session reset.", Toast.LENGTH_SHORT).show()
        }

        sourceToggleButton.setOnClickListener {
            useServerAnalyzer = !useServerAnalyzer
            LLMManager.useServerLLM = useServerAnalyzer
            val sourceLabel = if (useServerAnalyzer) "Server" else "Local"
            sourceToggleButton.text = "Source: $sourceLabel"
            Toast.makeText(this, "Switched to $sourceLabel mode", Toast.LENGTH_SHORT).show()
        }

        ttsToggleButton.setOnClickListener {
            ttsEnabled = !ttsEnabled
            ttsToggleButton.text = if (ttsEnabled) "🔊 TTS: On" else "🔇 TTS: Off"
        }

        cameraButton.setOnClickListener {
            photoFile = File.createTempFile("photo_", ".jpg", cacheDir)
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile!!)
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
            }, CAMERA_REQUEST_CODE)
        }

        videoButton.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_PICK).apply {
                type = "video/*"
            }, VIDEO_PICK_CODE)
        }

        jsonButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/json"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Choose JSON file"), JSON_PICK_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            CAMERA_REQUEST_CODE -> {
                if (resultCode == RESULT_OK && photoFile != null) {
                    BitmapFactory.decodeFile(photoFile!!.absolutePath)?.let { analyzeAndAddToChat(it) }
                }
            }
            VIDEO_PICK_CODE -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { showSensorDialogForVideo(it) }
                }
            }
            JSON_PICK_CODE -> {
                if (resultCode == RESULT_OK && pendingVideoUri != null) {
                    val jsonUri = data?.data
                    analyzeVideoInChat(pendingVideoUri!!, jsonUri)
                    pendingVideoUri = null
                } else if (resultCode == RESULT_OK) {
                    val jsonUri = data?.data
                    if (jsonUri != null) {
                        progressBar.visibility = ProgressBar.VISIBLE
                        showAnalysisStatus("📄", "Analyzing JSON...")

                        lifecycleScope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    jsonAnalyzer.analyze(jsonUri, analysisMode)
                                }

                                LLMManager.setSceneWithMode(result.summary, SourceType.JSON, analysisMode)
                                lastSource = SourceType.JSON
                                updateContextStatus(lastSource)

                                showAnalysisStatus("✅", "Done analyzing JSON. You can now ask questions.")

                                if (analysisMode == AnalysisMode.NARRATIVE) {
                                    val output = jsonAnalyzer.generateNarrativeDescription()
                                    appendToChat("🤖", output)
                                }

                            } catch (e: Exception) {
                                appendToChat("❌", "JSON analysis failed: ${e.message}")
                            } finally {
                                progressBar.visibility = ProgressBar.GONE
                            }
                        }
                    }
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun showSensorDialogForVideo(videoUri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Sensor Data")
            .setMessage("Do you want to include a sensor JSON file?")
            .setPositiveButton("Yes") { _, _ ->
                pendingVideoUri = videoUri
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "application/json"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(Intent.createChooser(intent, "Choose JSON file"), JSON_PICK_CODE)
            }
            .setNegativeButton("No") { _, _ ->
                analyzeVideoInChat(videoUri, null)
            }
            .setCancelable(false)
            .show()
    }

    private fun analyzeVideoInChat(videoUri: Uri, jsonUri: Uri?) {
        progressBar.visibility = ProgressBar.VISIBLE
        showAnalysisStatus("📹", "Analyzing video...")

        lifecycleScope.launch {
            try {
                val sensorSamples: List<SensorSample> = withContext(Dispatchers.IO) {
                    jsonUri?.let {
                        try {
                            contentResolver.openInputStream(it)?.bufferedReader()?.use { r ->
                                SensorJsonParser.parse(r.readText())
                            }
                        } catch (e: Exception) {
                            Log.e("JSON", "Failed to parse sensor JSON", e)
                            emptyList()
                        }
                    } ?: emptyList()
                }

                val result = withContext(Dispatchers.IO) {
                    if (useServerAnalyzer)
                        serverAnalyzer.analyzeVideo(videoUri, analysisMode, sensorSamples)
                    else
                        videoAnalyzer.analyze(videoUri, analysisMode, sensorSamples)
                }

                LLMManager.setSceneWithMode(result.json ?: result.summary, SourceType.VIDEO, analysisMode)
                lastSource = SourceType.VIDEO
                updateContextStatus(lastSource)

                showAnalysisStatus("✅", "Done analyzing video. You can now ask questions.")

                if (analysisMode == AnalysisMode.NARRATIVE) {
                    val output = if (useServerAnalyzer) {
                        serverAnalyzer.generateNarrativeDescription()
                    } else {
                        videoAnalyzer.generateNarrativeDescription()
                    }
                    appendToChat("🤖", output)
                }

            } catch (e: Exception) {
                appendToChat("❌", "Video analysis failed: ${e.message}")
            } finally {
                progressBar.visibility = ProgressBar.GONE
            }
        }
    }

    private fun analyzeAndAddToChat(bitmap: Bitmap) {
        progressBar.visibility = ProgressBar.VISIBLE
        showAnalysisStatus("📷", "Analyzing image...")

        val target = targetObjectEditText.text.toString().trim().ifBlank { null }

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (useServerAnalyzer)
                        serverAnalyzer.analyzeImage(bitmap, analysisMode, target)
                    else
                        imageSceneAnalyzer.analyze(bitmap, analysisMode)
                }

                LLMManager.setSceneWithMode(result.json ?: result.summary, SourceType.IMAGE, analysisMode)
                lastSource = SourceType.IMAGE
                updateContextStatus(lastSource)

                chatContainer.addView(ImageView(this@AssistantActivity).apply {
                    setImageBitmap(bitmap)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 16, 0, 16) }
                    adjustViewBounds = true
                })

                showAnalysisStatus("✅", "Done analyzing image. You can now ask questions.")

                if (analysisMode == AnalysisMode.NARRATIVE) {
                    val output = if (useServerAnalyzer) {
                        serverAnalyzer.generateNarrativeDescription()
                    } else {
                        imageSceneAnalyzer.generateNarrativeDescription()
                    }
                    appendToChat("🤖", output)
                }

            } catch (e: Exception) {
                appendToChat("❌", "Image analysis failed: ${e.message}")
            } finally {
                progressBar.visibility = ProgressBar.GONE
            }
        }
    }

    private fun handleSend() {
        val userInput = inputField.text.toString().trim()
        val targetText = targetObjectEditText.text.toString().trim()
        val targetLabels = if (targetText.isNotBlank()) setOf(targetText.lowercase()) else emptySet()

        if (userInput.isEmpty()) return

        inputField.text.clear()
        appendToChat("👤", userInput)
        progressBar.visibility = ProgressBar.VISIBLE

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    LLMManager.generateWithLabels(userInput, targetLabels)
                }
                appendToChat("🤖", response)
            } catch (e: Exception) {
                appendToChat("❌", "Error: ${e.message}")
            } finally {
                progressBar.visibility = ProgressBar.GONE
            }
        }
    }

    private fun appendToChat(sender: String, message: String) {
        val text = TextView(this).apply {
            text = "$sender: $message"
            setTextColor(resources.getColor(android.R.color.white, theme))
            textSize = 16f
            setPadding(16, 16, 16, 16)
        }
        chatContainer.addView(text)
        val scrollView = findViewById<ScrollView>(R.id.chatScroll)
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }

        if (ttsEnabled && sender == "🤖") {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun updateContextStatus(source: SourceType?) {
        val status = when (source) {
            SourceType.IMAGE -> "Context: Image"
            SourceType.VIDEO -> "Context: Video"
            SourceType.JSON -> "Context: JSON"
            else -> "Context: None"
        }
        contextStatusView.text = status
    }

    private fun showAnalysisStatus(icon: String, message: String) {
        appendToChat(icon, message)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.toggleYoloOutput -> {
                showYoloLabelsInChat = !showYoloLabelsInChat
                Toast.makeText(
                    this,
                    if (showYoloLabelsInChat) "YOLO output will now be shown in chat."
                    else "YOLO output will now be hidden from chat.",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
