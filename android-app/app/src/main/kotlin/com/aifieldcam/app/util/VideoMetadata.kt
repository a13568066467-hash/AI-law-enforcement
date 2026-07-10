package com.aifieldcam.app.util

import android.media.MediaMetadataRetriever
import android.util.Log
import com.aifieldcam.app.platform.RecordingSegmentPolicy
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object VideoMetadata {

    private const val TAG = "VideoMetadata"
    /** 大文件读 moov 可能极慢；超时后回退墙钟时长 */
    private const val READ_TIMEOUT_MS = 8_000L
    private val skipReadBytes: Long
        get() = RecordingSegmentPolicy.maxSegmentBytes() + 50L * 1024 * 1024

    fun durationMs(file: File): Long {
        if (!file.exists() || file.length() == 0L) return 0L
        if (file.length() >= skipReadBytes) {
            Log.i(TAG, "skip metadata read for large file ${file.name} (${file.length()}B)")
            return 0L
        }
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit<Long> {
                readDurationMs(file)
            }
            future.get(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "metadata read timeout for ${file.name} (${file.length()}B)")
            0L
        } catch (e: Exception) {
            Log.w(TAG, "metadata read failed for ${file.name}: ${e.message}")
            0L
        } finally {
            executor.shutdownNow()
        }
    }

    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            raw?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
