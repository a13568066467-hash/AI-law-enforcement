package com.aifieldcam.app.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.aifieldcam.app.service.RecorderKeyAccessibilityService

/** 息屏 / 后台时侧键需开启本无障碍服务。 */
object RecorderKeyAccessibility {

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!enabled) return false
        val expected = ComponentName(
            context.packageName,
            RecorderKeyAccessibilityService::class.java.name,
        ).flattenToString()
        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return TextUtils.SimpleStringSplitter(':').let { splitter ->
            splitter.setString(services)
            var found = false
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) {
                    found = true
                    break
                }
            }
            found
        }
    }

    fun openSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
