package com.aifieldcam.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.platform.DeviceProfile
import com.aifieldcam.app.platform.RecorderKeyDispatcher
import com.aifieldcam.app.platform.RecorderKeyRoute

/**
 * 息屏 / App 不在前台时接收机身侧键（录像 F5、拍照 F4、PTT F6 等）。
 * 需在系统设置中开启「执法仪侧键」。
 */
class RecorderKeyAccessibilityService : AccessibilityService() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        Log.i(TAG, "accessibility connected; filterKeyEvents enabled")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!DeviceProfile.isDsjZecn6a1) return false
        acquireBriefWake()
        val session = SessionManager.getInstance(this)
        val handled = RecorderKeyDispatcher.handleKeyEvent(
            session,
            event,
            RecorderKeyRoute.Source.ACCESSIBILITY,
        )
        if (handled) {
            Log.i(TAG, "key handled keyCode=${event.keyCode} action=${event.action}")
        } else {
            Log.d(
                TAG,
                "key skip keyCode=${event.keyCode} action=${event.action} " +
                    "activityForeground=${RecorderKeyRoute.activityHandlesKeys}",
            )
        }
        return handled
    }

    override fun onDestroy() {
        releaseWake()
        super.onDestroy()
    }

    /** 息屏按键开录时短暂唤醒 CPU，避免相机/编码器启动被挂起 */
    private fun acquireBriefWake() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "aifieldcam:RecorderKey",
            ).also {
                it.setReferenceCounted(false)
                it.acquire(60_000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock failed: ${e.message}")
        }
    }

    private fun releaseWake() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "RecorderKeyA11y"
    }
}
