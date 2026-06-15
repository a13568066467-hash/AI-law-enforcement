package com.aifieldcam.app.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.aifieldcam.app.ble.AlbumStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhoneCameraHelper {

    private const val AUTHORITY_SUFFIX = ".fileprovider"

    fun authority(context: Context): String =
        "${context.applicationContext.packageName}$AUTHORITY_SUFFIX"

    fun newPhotoFile(context: Context): File {
        val dir = AlbumStore.albumDir(context)
        dir.mkdirs()
        val name = timestampName() + "_phone.jpg"
        return File(dir, name)
    }

    fun newVideoFile(context: Context): File {
        val dir = videoDir(context)
        dir.mkdirs()
        val name = timestampName() + "_phone.mp4"
        return File(dir, name)
    }

    fun videoDir(context: Context): File =
        File(context.applicationContext.filesDir, "videos")

    fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context.applicationContext, authority(context), file)

    private fun timestampName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}
