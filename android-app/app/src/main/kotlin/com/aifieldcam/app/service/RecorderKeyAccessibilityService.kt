package com.aifieldcam.app.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.platform.DeviceProfile
import com.aifieldcam.app.platform.RecorderKeyDispatcher
import com.aifieldcam.app.platform.RecorderKeyRoute

/**
 * 息屏 / App 不在前台时接收机身侧键（F2/F4/F5/F6/F3）。
 * 需在系统设置中手动开启「执法仪侧键」。
 */
class RecorderKeyAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!DeviceProfile.isDsjZecn6a1) return false
        val session = SessionManager.getInstance(this)
        val handled = RecorderKeyDispatcher.handleKeyEvent(
            session,
            event,
            RecorderKeyRoute.Source.ACCESSIBILITY,
        )
        if (handled) {
            Log.i(TAG, "key handled keyCode=${event.keyCode} action=${event.action}")
        } else {
            Log.w(TAG, "key NOT handled keyCode=${event.keyCode} action=${event.action} " +
                "activityForeground=${RecorderKeyRoute.activityHandlesKeys}")
        }
        return handled
    }

    companion object {
        private const val TAG = "RecorderKeyA11y"
    }
}
