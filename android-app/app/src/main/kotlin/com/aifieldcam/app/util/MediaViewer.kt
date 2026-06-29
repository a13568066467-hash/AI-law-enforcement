package com.aifieldcam.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.io.File

object MediaViewer {

    fun openVideo(context: Context, file: File) {
        openMedia(context, file)
    }

    fun openMedia(context: Context, file: File) {
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(context, "文件不存在或为空", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            "bin" -> "application/octet-stream"
            else -> "video/mp4"
        }
        val uri = PhoneCameraHelper.fileUri(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val label = if (mime.startsWith("image")) "查看快照" else "播放录像"
        try {
            context.startActivity(Intent.createChooser(intent, label))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "未找到可打开该文件的应用", Toast.LENGTH_SHORT).show()
        }
    }
}
