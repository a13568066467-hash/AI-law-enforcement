package com.aifieldcam.app.platform

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

object DeviceIdentity {

    @SuppressLint("HardwareIds")
    fun recorderId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        return "DSJ-$androidId"
    }

    fun shortLabel(context: Context): String {
        val id = recorderId(context)
        return if (id.length > 8) "本机 ${id.takeLast(8)}" else id
    }
}
