package com.aifieldcam.app.ble

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 全局相册存储：无论当前在哪个页面，JPEG 拼包完成后都会落盘。
 */
object AlbumStore {

    interface Listener {
        fun onImageSaved(file: File, jpeg: ByteArray)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun albumDir(context: Context): File = File(context.applicationContext.filesDir, "album")

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun saveImage(context: Context, jpeg: ByteArray): File {
        val dir = albumDir(context)
        dir.mkdirs()
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".jpg"
        val file = File(dir, name)
        file.writeBytes(jpeg)
        listeners.forEach { it.onImageSaved(file, jpeg) }
        return file
    }

    fun latestImageFile(context: Context): File? {
        val dir = albumDir(context)
        if (!dir.exists()) return null
        return dir.listFiles()
            ?.filter { it.extension.equals("jpg", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
    }
}
