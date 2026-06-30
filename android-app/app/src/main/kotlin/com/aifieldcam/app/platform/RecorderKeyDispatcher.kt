package com.aifieldcam.app.platform

import android.util.Log
import android.view.KeyEvent
import com.aifieldcam.app.data.SessionManager

/**
 * DSJ-ZECN6A1 机身物理按键 → 录像 / 拍照 / SOS。
 * 常见映射：F9/录像键切换录像，CAMERA/快门键拍照，F10/SOS 键触发应急演示。
 */
object RecorderKeyDispatcher {

    private const val TAG = "RecorderKey"

    private val recordToggleKeys = setOf(
        KeyEvent.KEYCODE_F9,
        KeyEvent.KEYCODE_MEDIA_RECORD,
        KeyEvent.KEYCODE_PROG_RED,
        KeyEvent.KEYCODE_BUTTON_L1,
    )

    private val captureKeys = setOf(
        KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_F8,
        KeyEvent.KEYCODE_FOCUS,
    )

    val sosKeys = setOf(
        KeyEvent.KEYCODE_F10,
        KeyEvent.KEYCODE_CALL,
        KeyEvent.KEYCODE_S,
    )

    fun handleKeyEvent(session: SessionManager, event: KeyEvent): Boolean {
        if (!DeviceProfile.isDsjZecn6a1) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return true

        return when {
            recordToggleKeys.contains(event.keyCode) -> {
                if (session.isRecording()) session.stopRecord() else session.startRecord()
                true
            }
            captureKeys.contains(event.keyCode) -> {
                session.triggerCapture()
                true
            }
            else -> {
                Log.d(TAG, "unmapped keyCode=${event.keyCode} scanCode=${event.scanCode}")
                false
            }
        }
    }

    fun handleSosLongPress(session: SessionManager, event: KeyEvent): Boolean {
        if (!DeviceProfile.isDsjZecn6a1) return false
        if (!sosKeys.contains(event.keyCode)) return false
        session.runDemoScenario("sos_emergency") { _, err ->
            if (err.isNotBlank()) Log.w(TAG, "SOS: $err")
        }
        return true
    }
}
