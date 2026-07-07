package com.aifieldcam.app.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.aifieldcam.app.platform.DeviceProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhoneCameraHelper {

    private const val AUTHORITY_SUFFIX = ".fileprovider"

    fun authority(context: Context): String =
        "${context.applicationContext.packageName}$AUTHORITY_SUFFIX"

    private fun layout(context: Context): MediaStorageLocator.Layout =
        MediaStorageLocator.resolve(context.applicationContext)

    fun newPhotoFile(context: Context): File {
        val dir = layout(context).albumDir
        dir.mkdirs()
        val name = timestampName() + if (DeviceProfile.isDsjZecn6a1) {
            "_native.jpg"
        } else {
            "_phone.jpg"
        }
        return File(dir, name)
    }

    fun newVideoFile(context: Context): File {
        val dir = videoDir(context)
        dir.mkdirs()
        val name = timestampName() + if (DeviceProfile.isDsjZecn6a1) {
            "_1080p.mp4"
        } else {
            "_phone.mp4"
        }
        return File(dir, name)
    }

    fun newAudioFile(context: Context): File {
        val dir = layout(context).audioDir
        dir.mkdirs()
        return File(dir, timestampName() + "_native.m4a")
    }

    fun videoDir(context: Context): File = layout(context).videoDir

    fun storageSummary(context: Context): String = layout(context).summary

    fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context.applicationContext, authority(context), file)

    private fun timestampName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}
