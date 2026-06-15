package com.aifieldcam.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object CameraPermissionHelper {

    fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        if (Build.VERSION.SDK_INT <= 32) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        perms.add(Manifest.permission.RECORD_AUDIO)
        return perms.toTypedArray()
    }

    fun capturePermissions(): Array<String> {
        return arrayOf(Manifest.permission.CAMERA)
    }

    fun videoPermissions(): Array<String> {
        return requiredPermissions()
    }

    fun hasCamera(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun missing(context: Context, permissions: Array<String>): List<String> {
        return permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
}
