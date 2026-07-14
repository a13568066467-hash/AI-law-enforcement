package com.aifieldcam.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import java.io.File

object MediaViewer {

    fun openVideo(context: Context, file: File) {
        openMedia(context, file)
    }

    fun openMedia(context: Context, file: File) {
        if (!file.exists() || file.length() == 0L) return
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            "bin" -> "application/octet-stream"
            else -> "video/mp4"
        }
        val uri = try {
            PhoneCameraHelper.fileUri(context, file)
        } catch (_: IllegalArgumentException) {
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, null))
        } catch (_: ActivityNotFoundException) {
            // 无可用播放器时静默
        }
    }
}
