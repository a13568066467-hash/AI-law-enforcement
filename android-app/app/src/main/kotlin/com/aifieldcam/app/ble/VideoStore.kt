package com.aifieldcam.app.ble

import android.content.Context
import com.aifieldcam.app.util.GallerySaver
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoStore {

    fun videoDir(context: Context): File = File(context.applicationContext.filesDir, "videos")

    fun saveVideo(context: Context, data: ByteArray): File {
        val dir = videoDir(context)
        dir.mkdirs()
        val ext = detectExtension(data)
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ext
        val file = File(dir, name)
        file.writeBytes(data)
        if (ext == ".mp4") {
            GallerySaver.saveVideoToGallery(context, file)
        }
        return file
    }

    private fun detectExtension(data: ByteArray): String {
        if (data.size >= 2 &&
            (data[0].toInt() and 0xFF) == 0xFF &&
            (data[1].toInt() and 0xFF) == 0xD8
        ) {
            return ".jpg"
        }
        if (data.size >= 8 &&
            data[4] == 'f'.code.toByte() &&
            data[5] == 't'.code.toByte() &&
            data[6] == 'y'.code.toByte() &&
            data[7] == 'p'.code.toByte()
        ) {
            return ".mp4"
        }
        return ".bin"
    }
}
