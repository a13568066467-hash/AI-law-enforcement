package com.aifieldcam.app.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.aifieldcam.app.service.RecorderKeyAccessibilityService

/** 息屏 / 后台时侧键需开启本无障碍服务。量产机静默写入，不弹窗引导。 */
object RecorderKeyAccessibility {

    private const val TAG = "RecorderKeyA11y"

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!enabled) return false
        val expected = serviceComponent(context).flattenToString()
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

    /**
     * 开机 / 启动时静默开启「执法仪侧键」无障碍。
     * 需 ROM 预授 [android.permission.WRITE_SECURE_SETTINGS] 或 adb grant。
     */
    fun ensureEnabledSilently(context: Context): Boolean {
        if (!DeviceProfile.isDsjZecn6a1 && !Ze69Hardware.isZe69Platform) return false
        if (isEnabled(context)) return true
        return try {
            val component = serviceComponent(context).flattenToString()
            val cr = context.contentResolver
            val current = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val merged = if (current.isBlank()) {
                component
            } else {
                val parts = current.split(':').filter { it.isNotBlank() }.toMutableSet()
                if (parts.contains(component)) current else (parts + component).joinToString(":")
            }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            val ok = isEnabled(context)
            if (ok) Log.i(TAG, "recorder key accessibility enabled silently")
            else Log.w(TAG, "accessibility write ok but service not active yet")
            ok
        } catch (e: SecurityException) {
            Log.w(TAG, "silent enable denied (need WRITE_SECURE_SETTINGS): ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "silent enable failed", e)
            false
        }
    }

    fun openSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun serviceComponent(context: Context) = ComponentName(
        context.packageName,
        RecorderKeyAccessibilityService::class.java.name,
    )
}
