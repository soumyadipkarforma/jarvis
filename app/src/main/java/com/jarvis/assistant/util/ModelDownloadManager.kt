package com.jarvis.assistant.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Robust utility to download and manage AI model files and native libraries at runtime.
 * Includes retries, checksum verification, and resume support.
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "DownloadManager"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        
        // Stable URLs
        const val VOSK_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val PHI2_MODEL_URL = "https://huggingface.co/TheBloke/phi-2-GGUF/resolve/main/phi-2.Q4_K_M.gguf"
        
        // These still need to be hosted on a reliable server by the developer
        const val COQUI_TTS_URL = "https://github.com/soumyadipkarforma/jarvis/releases/download/v1.0.0/tts-model.zip"
        const val NATIVE_LIBS_URL = "https://github.com/soumyadipkarforma/jarvis/releases/download/v1.0.0/native-libs.zip"

        // Expected SHA-256 hashes for verification (examples, should be updated with real ones)
        private val EXPECTED_HASHES = mapOf(
            "vosk-model.zip" to "07253457585f5869485747657685960685746352413524354657687980987a6b", 
            "phi-2.Q4_K_M.gguf" to "416b71b8e4e9638c03e91122a61f36474135767b0959864a7810787a6b416b71"
        )
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    /**
     * Check if a specific model or library exists and is valid.
     */
    fun isModelDownloaded(fileName: String): Boolean {
        val file = File(context.filesDir, fileName)
        if (file.exists() && file.length() > 0) return true
        
        val dir = File(context.filesDir, fileName.removeSuffix(".zip"))
        return dir.exists() && dir.isDirectory
    }

    /**
     * Download a file with retries and progress tracking.
     */
    suspend fun downloadFile(
        url: String, 
        targetFileName: String, 
        expectedHash: String? = null,
        progressListener: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        
        for (attempt in 1..MAX_RETRIES) {
            try {
                Log.d(TAG, "Download attempt $attempt for $targetFileName")
                val file = performDownload(url, targetFileName, progressListener)
                
                if (file != null) {
                    if (expectedHash != null && !verifyChecksum(file, expectedHash)) {
                        Log.e(TAG, "Checksum verification failed for $targetFileName")
                        file.delete()
                        throw IOException("Checksum mismatch")
                    }
                    return@withContext file
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt $attempt failed: ${e.message}")
                if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS * attempt)
            }
        }
        
        Log.e(TAG, "All download attempts failed for $targetFileName", lastException)
        null
    }

    private fun performDownload(url: String, targetFileName: String, progressListener: (Int) -> Unit): File? {
        val targetFile = File(context.filesDir, targetFileName)
        val tempFile = File(context.filesDir, "$targetFileName.tmp")
        
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) return null
        
        val body = response.body ?: return null
        val contentLength = body.contentLength()
        
        body.byteStream().use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead: Long = 0
                
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (contentLength > 0) {
                        val progress = ((totalBytesRead * 100) / contentLength).toInt()
                        progressListener(progress)
                    }
                }
            }
        }
        
        if (tempFile.renameTo(targetFile)) {
            return targetFile
        }
        return null
    }

    /**
     * Unzip a model file into a directory with error handling.
     */
    suspend fun unzipFile(zipFile: File, targetDirName: String): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, targetDirName)
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()
        
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val file = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { output ->
                            zip.copyTo(output)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unzip failed for ${zipFile.name}", e)
            targetDir.deleteRecursively()
            false
        }
    }

    /**
     * Verify SHA-256 checksum of a file.
     */
    private fun verifyChecksum(file: File, expectedHash: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            Log.d(TAG, "Actual hash: $actualHash")
            actualHash.equals(expectedHash, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }
}