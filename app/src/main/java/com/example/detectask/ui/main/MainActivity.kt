package com.example.detectask.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.detectask.R
import com.example.detectask.eval.EvaluationRunner
import com.example.detectask.ui.assistant.AssistantActivity
import com.example.detectask.ui.recording.VideoRecordingActivity
import com.example.detectask.ui.segment.ImageSegmentActivity
import com.example.detectask.ui.segment.LiveCameraActivity
import com.example.detectask.ui.video.VideoAnalyseActivity
import kotlinx.coroutines.launch

/**
 * Main entry point of the app providing navigation to different features
 * and buttons to trigger local or server evaluations.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val goToSegmentButton = findViewById<Button>(R.id.goToSegmentButton)
        val goToLiveServerButton = findViewById<Button>(R.id.goToLiveServerButton)
        val goToLiveLocalButton = findViewById<Button>(R.id.goToLiveLocalButton)
        val goToAssistantButton = findViewById<Button>(R.id.goToAssistantButton)
        val goToVideoButton = findViewById<Button>(R.id.goToVideoButton)
        val goToRecordingButton = findViewById<Button>(R.id.goToRecordingButton)
        val evalLocalButton = findViewById<Button>(R.id.evalLocalButton)
        val evalServerButton = findViewById<Button>(R.id.evalServerButton)
        val evalStatusText = findViewById<TextView>(R.id.evalStatusText)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        evalLocalButton.setOnClickListener {
            evalStatusText.text = "⏳ Lokale Evaluation läuft..."
            lifecycleScope.launch {
                try {
                    EvaluationRunner.runAll(applicationContext, useServerLLM = false)
                    evalStatusText.text = "✅ Lokale Evaluation abgeschlossen"
                } catch (e: Exception) {
                    evalStatusText.text = "❌ Fehler bei lokaler Evaluation: ${e.message}"
                }
            }
        }

        evalServerButton.setOnClickListener {
            evalStatusText.text = "⏳ Server-Evaluation läuft..."
            lifecycleScope.launch {
                try {
                    EvaluationRunner.runAll(applicationContext, useServerLLM = true)
                    evalStatusText.text = "✅ Server-Evaluation abgeschlossen"
                } catch (e: Exception) {
                    evalStatusText.text = "❌ Fehler bei Server-Evaluation: ${e.message}"
                }
            }
        }

        goToSegmentButton.setOnClickListener {
            startActivity(Intent(this, ImageSegmentActivity::class.java))
        }

        goToLiveServerButton.setOnClickListener {
            val intent = Intent(this, LiveCameraActivity::class.java)
            intent.putExtra("mode", "server")
            startActivity(intent)
        }

        goToLiveLocalButton.setOnClickListener {
            val intent = Intent(this, LiveCameraActivity::class.java)
            intent.putExtra("mode", "local")
            startActivity(intent)
        }

        goToAssistantButton.setOnClickListener {
            startActivity(Intent(this, AssistantActivity::class.java))
        }

        goToVideoButton.setOnClickListener {
            startActivity(Intent(this, VideoAnalyseActivity::class.java))
        }

        goToRecordingButton.setOnClickListener {
            startActivity(Intent(this, VideoRecordingActivity::class.java))
        }
    }
}
