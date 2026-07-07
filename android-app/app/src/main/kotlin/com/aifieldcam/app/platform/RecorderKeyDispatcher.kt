package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.platform.RecorderKeyRoute

/**
 * DSJ-ZECN6A1 机身键 — 对齐厂商《按键操作说明》+ ROM mtk-kpd.kl。
 *
 * | 键 | KeyCode | 说明书 |
 * |----|---------|--------|
 * | 录音 | F2 | 短按 开/停录音 |
 * | 录像 | F5 | 短按 开/停录像 |
 * | 拍照 | F4 | 短按 拍照 |
 * | SOS | F3 | 短按 重点标记；长按 SOS |
 * | PTT | F6 | 短按 白光灯；长按 对讲 |
 */
object RecorderKeyDispatcher {

    private const val TAG = "RecorderKey"
    private const val DEBOUNCE_MS = 450L
    private const val LONG_PRESS_MS = 500L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 说明书：录音键（mtk-kpd #record → F2） */
    private val audioToggleKeys = setOf(KeyEvent.KEYCODE_F2)

    /** 说明书：录像键（mtk-kpd #video → F5） */
    private val videoToggleKeys = setOf(
        KeyEvent.KEYCODE_F5,
        KeyEvent.KEYCODE_F9,
        KeyEvent.KEYCODE_MEDIA_RECORD,
    )

    private val captureKeys = setOf(
        KeyEvent.KEYCODE_F4,
        KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_F8,
    )

    val sosKeys = setOf(
        KeyEvent.KEYCODE_F3,
        KeyEvent.KEYCODE_F10,
    )

    val pttKeys = setOf(KeyEvent.KEYCODE_F6)

    private val lastHandledAtMs = mutableMapOf<Int, Long>()
    private var sosLongPressHandled = false
    private var pttLongPressHandled = false

    private val sosLongPressRunnable = Runnable {
        sosLongPressHandled = true
        pendingSosSession?.let { session ->
            Log.i(TAG, "SOS long-press (timer) -> emergency")
            session.runDemoScenario("sos_emergency") { _, err ->
                if (err.isNotBlank()) Log.w(TAG, "SOS: $err")
            }
        }
    }

    private val pttLongPressRunnable = Runnable {
        pttLongPressHandled = true
        pendingPttSession?.let { session ->
            Log.i(TAG, "PTT long-press (timer) -> intercom")
            session.setAiListening(true)
        }
    }

    @Volatile
    private var pendingSosSession: SessionManager? = null

    @Volatile
    private var pendingPttSession: SessionManager? = null

    fun handleKeyEvent(session: SessionManager, event: KeyEvent): Boolean =
        handleKeyEvent(session, event, RecorderKeyRoute.Source.ACTIVITY)

    fun handleKeyEvent(session: SessionManager, event: KeyEvent, source: RecorderKeyRoute.Source): Boolean {
        if (!DeviceProfile.isDsjZecn6a1) return false
        if (!RecorderKeyRoute.accept(source, event.keyCode, event.action)) return false

        if (pttKeys.contains(event.keyCode)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        pttLongPressHandled = false
                        pendingPttSession = session
                        mainHandler.removeCallbacks(pttLongPressRunnable)
                        mainHandler.postDelayed(pttLongPressRunnable, LONG_PRESS_MS)
                        Log.d(TAG, "PTT down")
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(pttLongPressRunnable)
                    pendingPttSession = null
                    if (pttLongPressHandled) {
                        Log.i(TAG, "PTT long release -> intercom end")
                        session.setAiListening(false)
                        pttLongPressHandled = false
                    } else {
                        Log.i(TAG, "PTT short -> white light")
                        session.toggleWhiteLight()
                    }
                    return true
                }
            }
            return false
        }

        if (sosKeys.contains(event.keyCode)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        sosLongPressHandled = false
                        pendingSosSession = session
                        mainHandler.removeCallbacks(sosLongPressRunnable)
                        mainHandler.postDelayed(sosLongPressRunnable, LONG_PRESS_MS)
                        Log.d(TAG, "SOS down (await long-press or short mark)")
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(sosLongPressRunnable)
                    pendingSosSession = null
                    if (!sosLongPressHandled) {
                        Log.i(TAG, "SOS short -> important mark")
                        session.markImportantWithFeedback()
                    } else {
                        sosLongPressHandled = false
                    }
                    return true
                }
            }
            return false
        }

        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return true

        if (shouldDebounce(event.keyCode)) {
            Log.d(TAG, "debounced keyCode=${event.keyCode}")
            return true
        }

        return when {
            audioToggleKeys.contains(event.keyCode) -> {
                Log.i(TAG, "audio toggle keyCode=${event.keyCode}")
                if (session.isAudioRecording()) {
                    session.stopAudioRecordWithFeedback()
                } else {
                    session.startAudioRecordWithFeedback()
                }
                true
            }
            videoToggleKeys.contains(event.keyCode) -> {
                Log.i(TAG, "video toggle keyCode=${event.keyCode}")
                if (session.isRecorderBusy()) {
                    session.stopRecordWithFeedback()
                } else {
                    session.startRecordWithFeedback()
                }
                true
            }
            captureKeys.contains(event.keyCode) -> {
                Log.i(TAG, "capture keyCode=${event.keyCode}")
                session.triggerCaptureWithFeedback()
                true
            }
            else -> {
                Log.d(TAG, "unmapped keyCode=${event.keyCode} scanCode=${event.scanCode}")
                false
            }
        }
    }

    /** Activity [onKeyLongPress] 路径；无障碍服务走定时器。 */
    fun handleSosLongPress(session: SessionManager, event: KeyEvent): Boolean {
        if (!DeviceProfile.isDsjZecn6a1) return false
        if (!sosKeys.contains(event.keyCode)) return false
        mainHandler.removeCallbacks(sosLongPressRunnable)
        pendingSosSession = null
        sosLongPressHandled = true
        Log.i(TAG, "SOS long-press -> emergency")
        session.runDemoScenario("sos_emergency") { _, err ->
            if (err.isNotBlank()) Log.w(TAG, "SOS: $err")
        }
        return true
    }

    fun handlePttLongPress(session: SessionManager, event: KeyEvent): Boolean {
        if (!DeviceProfile.isDsjZecn6a1) return false
        if (!pttKeys.contains(event.keyCode)) return false
        mainHandler.removeCallbacks(pttLongPressRunnable)
        pendingPttSession = null
        pttLongPressHandled = true
        Log.i(TAG, "PTT long-press -> intercom")
        session.setAiListening(true)
        return true
    }

    private fun shouldDebounce(keyCode: Int): Boolean {
        val now = System.currentTimeMillis()
        val last = lastHandledAtMs[keyCode] ?: 0L
        if (now - last < DEBOUNCE_MS) return true
        lastHandledAtMs[keyCode] = now
        return false
    }
}
