package com.example.parakeet

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.provider.Settings
import android.content.Intent
import com.k2fsa.sherpa.onnx.*
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnRecord: Button
    
    private var recognizer: OfflineRecognizer? = null
    private var isRecording = false
    private var audioRecord: android.media.AudioRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        btnRecord = findViewById(R.id.btnRecord)

        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        } else {
            initModel()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initModel()
        } else {
            tvStatus.text = "Permission denied. Cannot record audio."
        }
    }

    private fun initModel() {
        ModelManager.ensureModelReady(this, onProgress = { msg ->
            runOnUiThread { tvStatus.text = msg }
        }, onReady = { modelDir ->
            runOnUiThread {
                setupRecognizer(modelDir)
                btnRecord.isEnabled = true
                setupRecordButton()
                
                val btnAccessibility = Button(this).apply {
                    text = "Enable Volume Down Hotkey"
                    setOnClickListener {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
                (findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as? android.view.ViewGroup)?.addView(btnAccessibility)
            }
        })
    }

    private fun setupRecognizer(modelDir: File) {
        val nemoConfig = OfflineNemoEncDecCtcModelConfig.builder()
            .setModel(File(modelDir, "model.int8.onnx").absolutePath)
            .build()
        val modelConfig = OfflineModelConfig.builder()
            .setNemo(nemoConfig)
            .setTokens(File(modelDir, "tokens.txt").absolutePath)
            .setNumThreads(4)
            .setDebug(false)
            .build()
        val config = OfflineRecognizerConfig.builder()
            .setOfflineModelConfig(modelConfig)
            .build()

        recognizer = OfflineRecognizer(config)
        tvStatus.text = "Model Ready (Parakeet INT8)"
    }
    
    private fun setupRecordButton() {
        btnRecord.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRecording()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRecording()
                    true
                }
                else -> false
            }
        }
    }
    
    private fun startRecording() {
        isRecording = true
        tvStatus.text = "Recording..."
        tvResult.text = ""
        
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            
            audioRecord?.startRecording()
            
            thread {
                val stream = recognizer?.createStream() ?: return@thread
                val buffer = ShortArray(bufferSize)
                
                while (isRecording) {
                    val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readResult > 0) {
                        val samples = FloatArray(readResult) { i -> buffer[i] / 32768.0f }
                        stream.acceptWaveform(samples, sampleRate)
                    }
                }
                
                recognizer?.decode(stream)
                val finalResult = recognizer?.getResult(stream)?.text
                runOnUiThread {
                    tvResult.text = finalResult
                    tvStatus.text = "Model Ready (Parakeet INT8)"
                }
                stream.release()
            }
        }
    }
    
    private fun stopRecording() {
        isRecording = false
        tvStatus.text = "Processing..."
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
