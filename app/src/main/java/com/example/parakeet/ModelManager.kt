package com.example.parakeet

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors

object ModelManager {
    private const val TAG = "ModelManager"
    private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2"
    private const val MODEL_DIR_NAME = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"

    fun ensureModelReady(context: Context, onProgress: (String) -> Unit, onReady: (File) -> Unit) {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val targetModelDir = File(modelsDir, MODEL_DIR_NAME)
        if (targetModelDir.exists() && targetModelDir.list()?.isNotEmpty() == true) {
            onProgress("Model already downloaded.")
            onReady(targetModelDir)
            return
        }

        Executors.newSingleThreadExecutor().execute {
            try {
                val archiveFile = File(modelsDir, "model.tar.bz2")
                if (!archiveFile.exists()) {
                    onProgress("Downloading model (500MB)... This may take a while.")
                    downloadFile(MODEL_URL, archiveFile)
                }
                
                onProgress("Extracting model...")
                extractTarBz2(archiveFile, modelsDir)
                
                archiveFile.delete() // Cleanup
                
                context.mainExecutor.execute {
                    onProgress("Model ready.")
                    onReady(targetModelDir)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize model", e)
                context.mainExecutor.execute {
                    onProgress("Error: ${e.message}")
                }
            }
        }
    }

    private fun downloadFile(urlString: String, destFile: File) {
        val url = URL(urlString)
        url.openStream().use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun extractTarBz2(archiveFile: File, destDir: File) {
        archiveFile.inputStream().use { fis ->
            BZip2CompressorInputStream(fis).use { bzis ->
                TarArchiveInputStream(bzis).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        val destPath = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            destPath.mkdirs()
                        } else {
                            destPath.parentFile?.mkdirs()
                            FileOutputStream(destPath).use { out ->
                                tarIn.copyTo(out)
                            }
                        }
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
    }
}
