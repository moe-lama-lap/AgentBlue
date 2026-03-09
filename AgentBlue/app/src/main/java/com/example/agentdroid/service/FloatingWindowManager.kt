package com.example.agentdroid.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import com.example.agentdroid.MainActivity
import com.example.agentdroid.R
import java.util.Locale

class FloatingWindowManager(
    private val context: Context,
    private val onCommandEntered: (String) -> Unit
) {
    companion object {
        private const val TAG = "FloatingWindowManager"
        private const val CLICK_THRESHOLD = 10
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null

    fun show() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(context).inflate(R.layout.layout_floating_window, null)

        val layoutParams = createLayoutParams()

        floatingView?.findViewById<View>(R.id.btn_robot)
            ?.setOnTouchListener(DraggableTouchListener(layoutParams))

        try {
            windowManager?.addView(floatingView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating view: ${e.message}")
        }
    }

    fun remove() {
        floatingView?.let { view ->
            windowManager?.removeView(view)
        }
        floatingView = null
        windowManager = null
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }
    }

    private fun showCommandDialog() {
        val editText = EditText(context).apply {
            hint = "Enter a command"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val micButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val px16 = (16 * context.resources.displayMetrics.density).toInt()
            val px8 = (8 * context.resources.displayMetrics.density).toInt()
            setPadding(px16, px8, px16, 0)
            addView(editText)
            addView(micButton)
        }

        var isListening = false
        val speechRecognizer: SpeechRecognizer? =
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else null

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                micButton.setImageResource(android.R.drawable.ic_media_pause)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                isListening = false
            }
            override fun onError(error: Int) {
                micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                isListening = false
                Log.w(TAG, "SpeechRecognizer error: $error")
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    editText.setText(matches[0])
                    editText.setSelection(editText.text.length)
                }
                isListening = false
                micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    editText.setText(matches[0])
                    editText.setSelection(editText.text.length)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        micButton.setOnClickListener {
            if (speechRecognizer == null) {
                Toast.makeText(context, "Speech recognition not available on this device.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isListening) {
                val hasMicPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!hasMicPermission) {
                    Toast.makeText(context, "Microphone permission required. Please grant it in the app settings.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                speechRecognizer.startListening(intent)
                isListening = true
            } else {
                speechRecognizer.stopListening()
                isListening = false
            }
        }

        val dialog = android.app.AlertDialog.Builder(
            context,
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        )
            .setTitle("AgentBlue Command")
            .setView(container)
            .setPositiveButton("Run") { _, _ ->
                speechRecognizer?.destroy()
                val command = editText.text.toString()
                Log.d(TAG, "Command entered: $command")
                onCommandEntered(command)
            }
            .setNegativeButton("Cancel") { _, _ ->
                speechRecognizer?.destroy()
            }
            .setNeutralButton("Settings") { _, _ ->
                speechRecognizer?.destroy()
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            .create()

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private inner class DraggableTouchListener(
        private val layoutParams: WindowManager.LayoutParams
    ) : View.OnTouchListener {

        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            return when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val xDiff = (event.rawX - initialTouchX).toInt()
                    val yDiff = (event.rawY - initialTouchY).toInt()
                    if (xDiff < CLICK_THRESHOLD && yDiff < CLICK_THRESHOLD) {
                        showCommandDialog()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }
}
