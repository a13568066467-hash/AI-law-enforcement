package com.aifieldcam.app.util

import android.media.MediaMetadataRetriever
import java.io.File

object VideoMetadata {

    fun durationMs(file: File): Long {
        if (!file.exists() || file.length() == 0L) return 0L
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            raw?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
