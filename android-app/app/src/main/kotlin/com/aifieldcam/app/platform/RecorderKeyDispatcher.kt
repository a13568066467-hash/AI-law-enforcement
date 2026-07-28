package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.platform.commandcall.CommandCallAiSuppressLatch
import com.aifieldcam.app.platform.commandcall.CommandCallController
import com.aifieldcam.app.platform.commandcall.CommandCallIntercom
import com.aifieldcam.app.platform.commandcall.CommandCallIntercomPolicy
import com.aifieldcam.app.platform.commandcall.CommandCallPttOwner

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

    /** DOWN 时锁定，UP / 长按定时器只用此值，避免中途推流/进房翻转归属。 */
    private var lockedPttOwner: CommandCallPttOwner? = null

    private val sosLongPressRunnable = Runnable {
        sosLongPressHandled = true
        pendingSosSession?.let { session ->
            Log.i(TAG, "SOS long-press (timer) -> field event capture")
            FieldEventSosController.onHoldReady(session)
        }
    }

    private val pttLongPressRunnable = Runnable {
        pttLongPressHandled = true
        pendingPttSession?.let { session ->
            when (lockedPttOwner ?: resolvePttOwner(uplinkHint = false)) {
                CommandCallPttOwner.COMMAND_CALL -> {
                    Log.i(TAG, "PTT long-press (timer) -> command-call uplink")
                    CommandCallIntercom.startUplink()
                }
                CommandCallPttOwner.VIDEO_STREAM -> {
                    // DOWN 已开始 talk；长按定时器路径不应再开 AI
                    Log.i(TAG, "PTT long-press (timer) -> video-stream talk already down")
                }
                CommandCallPttOwner.AI_OR_LIGHT -> {
                    Log.i(TAG, "PTT long-press (timer) -> snap+ask")
                    PttSnapAskController.onPttDown(session)
                }
            }
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
                        val owner = resolvePttOwner(uplinkHint = false)
                        lockedPttOwner = owner
                        when (owner) {
                            CommandCallPttOwner.COMMAND_CALL -> {
                                // 连线对讲：等长按定时器再上行；短按走白光
                                pttLongPressHandled = false
                                pendingPttSession = session
                                mainHandler.removeCallbacks(pttLongPressRunnable)
                                mainHandler.postDelayed(pttLongPressRunnable, LONG_PRESS_MS)
                                Log.d(TAG, "PTT down (command call) locked=$owner")
                            }
                            CommandCallPttOwner.VIDEO_STREAM -> {
                                handlePttTalkDown(session)
                            }
                            CommandCallPttOwner.AI_OR_LIGHT -> {
                                pttLongPressHandled = false
                                pendingPttSession = session
                                mainHandler.removeCallbacks(pttLongPressRunnable)
                                mainHandler.postDelayed(pttLongPressRunnable, LONG_PRESS_MS)
                                Log.d(TAG, "PTT down locked=$owner")
                            }
                        }
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(pttLongPressRunnable)
                    pendingPttSession = null
                    val owner = lockedPttOwner
                        ?: resolvePttOwner(
                            uplinkHint = CommandCallIntercom.isTalking(),
                        )
                    lockedPttOwner = null
                    when (owner) {
                        CommandCallPttOwner.COMMAND_CALL -> {
                            finishOrphanAiCaptureIfNeeded(owner)
                            if (pttLongPressHandled || CommandCallIntercom.isTalking()) {
                                Log.i(TAG, "PTT long release -> stop command-call uplink")
                                CommandCallIntercom.stopUplink()
                                pttLongPressHandled = false
                            } else {
                                Log.i(TAG, "PTT short (command call) -> white light")
                                session.toggleWhiteLight()
                            }
                            return true
                        }
                        CommandCallPttOwner.VIDEO_STREAM -> {
                            finishOrphanAiCaptureIfNeeded(owner)
                            handlePttTalkUp(session)
                            return true
                        }
                        CommandCallPttOwner.AI_OR_LIGHT -> {
                            if (pttLongPressHandled) {
                                Log.i(TAG, "PTT long release -> finish snap+ask")
                                PttSnapAskController.onPttUp()
                                pttLongPressHandled = false
                            } else {
                                if (PttSnapAskController.isActive()) {
                                    PttSnapAskController.cancel()
                                }
                                Log.i(TAG, "PTT short -> white light")
                                session.toggleWhiteLight()
                            }
                            return true
                        }
                    }
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
                        Log.i(TAG, "SOS long release -> submit field event")
                        FieldEventSosController.onRelease()
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
                    if (session.isVideoStreaming()) {
                        session.stopVideoStream("user-stop-video")
                    }
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
        Log.i(TAG, "SOS long-press -> field event capture")
        FieldEventSosController.onHoldReady(session)
        return true
    }

    fun handlePttLongPress(session: SessionManager, event: KeyEvent): Boolean {
        if (!DeviceProfile.isDsjZecn6a1) return false
        if (!pttKeys.contains(event.keyCode)) return false
        mainHandler.removeCallbacks(pttLongPressRunnable)
        pendingPttSession = null
        pttLongPressHandled = true
        val owner = lockedPttOwner ?: resolvePttOwner(uplinkHint = false).also {
            lockedPttOwner = it
        }
        when (owner) {
            CommandCallPttOwner.COMMAND_CALL -> {
                Log.i(TAG, "PTT long-press -> command-call uplink")
                CommandCallIntercom.startUplink()
            }
            CommandCallPttOwner.VIDEO_STREAM -> {
                Log.i(TAG, "PTT long-press -> video-stream (talk already via DOWN)")
            }
            CommandCallPttOwner.AI_OR_LIGHT -> {
                Log.i(TAG, "PTT long-press -> snap+ask")
                PttSnapAskController.onPttDown(session)
            }
        }
        return true
    }

    private fun resolvePttOwner(uplinkHint: Boolean): CommandCallPttOwner =
        CommandCallIntercomPolicy.pttOwner(
            commandCallActive = CommandCallAiSuppressLatch.blocksAiRealtime(
                CommandCallController.isInCall(),
            ) || (uplinkHint && CommandCallIntercom.isTalking()),
            videoStreaming = VideoStreamManager.isEncodedStreaming(),
        )

    /**
     * 若本轮锁定归属不是 AI，但 AI 仍在采（历史竞态残留），强制收尾，避免 pressed 卡死。
     */
    private fun finishOrphanAiCaptureIfNeeded(owner: CommandCallPttOwner) {
        if (owner == CommandCallPttOwner.AI_OR_LIGHT) return
        if (!PttSnapAskController.isActive()) return
        Log.w(TAG, "PTT up owner=$owner but AI still active -> cancel")
        PttSnapAskController.cancel()
    }

    // ── M7: 视频通话中 PTT 对讲 ──

    /** 视频通话中 PTT 按住：开始发送音频 */
    private fun handlePttTalkDown(session: SessionManager) {
        Log.i(TAG, "PTT talk down (video call)")
        // 关闭白光灯（视频通话中不需要）
        Ze69Hardware.setWhiteLight(false)
        VoiceCaptureHelper.start(
            onStarted = {
                Log.d(TAG, "PTT talk audio capture active")
            },
            onError = { err ->
                Log.w(TAG, "PTT talk audio error: $err")
                com.aifieldcam.app.util.TtsSpeaker.speak(err.ifBlank { "对讲采音失败" })
            },
        )
    }

    /** 视频通话中 PTT 松开：停止发送音频 */
    private fun handlePttTalkUp(session: SessionManager) {
        Log.i(TAG, "PTT talk up (video call)")
        VoiceCaptureHelper.stop { pcm ->
            if (pcm != null && pcm.size > 44) {
                Log.d(TAG, "PTT talk audio: ${pcm.size} bytes captured")
                // PCM 音频数据通过 WebRTC audio track 或 GB28181 RTP 发送
                // 此处由上层消费者（WebRtcPeer/SipUaClient）处理
            }
        }
    }

    private fun shouldDebounce(keyCode: Int): Boolean {
        val now = System.currentTimeMillis()
        val last = lastHandledAtMs[keyCode] ?: 0L
        if (now - last < DEBOUNCE_MS) return true
        lastHandledAtMs[keyCode] = now
        return false
    }
}
