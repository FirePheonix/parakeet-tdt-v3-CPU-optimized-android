package com.example.parakeet

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class RecordingService : Service() {

    companion object {
        const val ACTION_TOGGLE = "ACTION_TOGGLE"
        const val CHANNEL_ID = "RecordingServiceChannel"
        var isRecording = false
    }

    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initModel()
    }

    private fun initModel() {
        ModelManager.ensureModelReady(this, onProgress = {
            Log.d("RecordingService", "Model progress: $it")
        }, onReady = { modelDir ->
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    nemoCtc = OfflineNemoEncDecCtcModelConfig(
                        model = File(modelDir, "model.int8.onnx").absolutePath
                    ),
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    numThreads = 4,
                    debug = false
                )
            )
            recognizer = OfflineRecognizer(config)
            Log.d("RecordingService", "Model Ready")
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (isRecording) return
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("RecordingService", "No recording permission")
            return
        }

        startForeground(1, buildNotification("Recording..."))
        isRecording = true

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord?.startRecording()

        thread {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val audioFile = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Record_$timestamp.wav")
            val textFile = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "Transcript_$timestamp.txt")
            
            val fos = FileOutputStream(audioFile)
            writeWavHeader(fos, 0, sampleRate, 1, sampleRate * 2) // Dummy header

            var totalAudioLen = 0L

            val stream = recognizer?.createStream()
            val buffer = ShortArray(bufferSize)
            val byteBuffer = ByteArray(bufferSize * 2)

            while (isRecording) {
                val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readResult > 0) {
                    for (i in 0 until readResult) {
                        byteBuffer[i * 2] = (buffer[i].toInt() and 0xff).toByte()
                        byteBuffer[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xff).toByte()
                    }
                    fos.write(byteBuffer, 0, readResult * 2)
                    totalAudioLen += (readResult * 2)

                    if (stream != null) {
                        val samples = FloatArray(readResult) { i -> buffer[i] / 32768.0f }
                        stream.acceptWaveform(samples, sampleRate)
                        while (recognizer?.isReady(stream) == true) {
                            recognizer?.decode(stream)
                        }
                    }
                }
            }

            fos.close()
            // Fix WAV header
            val raf = RandomAccessFile(audioFile, "rw")
            raf.seek(0)
            val header = createWavHeader(totalAudioLen, sampleRate, 1, sampleRate * 2)
            raf.write(header)
            raf.close()

            if (stream != null) {
                stream.inputFinished()
                while (recognizer?.isReady(stream) == true) {
                    recognizer?.decode(stream)
                }
                val finalResult = recognizer?.getResult(stream)?.text ?: ""
                
                FileOutputStream(textFile).use { it.write(finalResult.toByteArray()) }
                stream.release()
                
                updateNotification("Saved text: $finalResult")
            } else {
                updateNotification("Audio saved, but STT model not ready.")
            }
            
            stopForeground(false)
        }
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun createWavHeader(totalAudioLen: Long, sampleRate: Int, channels: Int, byteRate: Int): ByteArray {
        val totalDataLen = totalAudioLen + 36
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        return header
    }

    private fun writeWavHeader(out: FileOutputStream, totalAudioLen: Long, sampleRate: Int, channels: Int, byteRate: Int) {
        val header = createWavHeader(totalAudioLen, sampleRate, channels, byteRate)
        out.write(header, 0, 44)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Parakeet STT")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Recording Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
