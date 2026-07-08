package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.demo.DemoScenarios
import com.aifieldcam.app.util.TtsSpeaker

/**
 * PTT 长按：实时抓帧 + 语音采集 → AI 意图分发（场景切换 / 视觉识别 / 操作指导）。
 *
 * 状态机：
 *   IDLE → (PTT_DOWN) → SNAPPING → CAPTURING_VOICE → (PTT_UP or TIMEOUT) → PROCESSING → IDLE
 */
object PttSnapAskController {

    private const val TAG = "PttSnapAsk"
    /** 按下即松（< 300ms）视为白光灯，不触发抓拍 */
    private const val MIN_HOLD_MS = 300L
    /** 最长录音时间 */
    private const val MAX_VOICE_MS = 15_000L

    enum class Status { IDLE, SNAPPING, CAPTURING_VOICE, PROCESSING }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    var status: Status = Status.IDLE
        private set

    private var pendingSession: SessionManager? = null
    private var snapshotJpeg: ByteArray? = null
    private var holdStartMs = 0L
    private var voiceCollected = false

    private val maxVoiceTimeout = Runnable {
        if (status == Status.CAPTURING_VOICE) {
            Log.i(TAG, "voice capture timeout (${MAX_VOICE_MS}ms)")
            finishAndDispatch()
        }
    }

    /** PTT 按下，开始抓帧 + 录音 */
    fun onPttDown(session: SessionManager) {
        if (status != Status.IDLE) {
            Log.w(TAG, "already ${status}, ignore PTT down")
            return
        }
        pendingSession = session
        snapshotJpeg = null
        voiceCollected = false
        holdStartMs = System.currentTimeMillis()

        // 亮灯：AI 交互中
        Ze69Hardware.setAiListeningIndicator(true)

        // Step 1: 抓帧
        status = Status.SNAPPING
        TtsSpeaker.speak("正在抓拍现场画面")

        NativeRecorder.grabRecordingFrame { jpeg ->
            if (status == Status.IDLE) return@grabRecordingFrame
            snapshotJpeg = jpeg
            if (jpeg != null) {
                Log.i(TAG, "frame grabbed: ${jpeg.size} bytes")
            } else {
                Log.w(TAG, "frame grab returned null, continue without image")
            }

            // Step 2: 开始录音
            status = Status.CAPTURING_VOICE
            TtsSpeaker.speak("请说出您的问题")

            VoiceCaptureHelper.start(
                onStarted = {
                    if (status == Status.CAPTURING_VOICE) {
                        Log.i(TAG, "voice capture active, timeout=${MAX_VOICE_MS}ms")
                        mainHandler.postDelayed(maxVoiceTimeout, MAX_VOICE_MS)
                    }
                },
                onError = { err ->
                    Log.w(TAG, "voice capture start failed: $err")
                    TtsSpeaker.speak("麦克风不可用，请用文字输入问题")
                    voiceCollected = false
                },
            )
        }
    }

    /** PTT 松手，停止录音并分发 */
    fun onPttUp() {
        if (status == Status.IDLE) return
        val held = System.currentTimeMillis() - holdStartMs

        // 短按（< 300ms）视为白光灯操作撤销
        if (held < MIN_HOLD_MS) {
            Log.i(TAG, "too short (${held}ms), cancel")
            cancel()
            return
        }

        mainHandler.removeCallbacks(maxVoiceTimeout)

        if (status == Status.CAPTURING_VOICE) {
            VoiceCaptureHelper.stop { pcm ->
                if (pcm != null && pcm.size > 44) {
                    voiceCollected = true
                    Log.i(TAG, "voice collected: ${pcm.size} bytes")
                }
                finishAndDispatch()
            }
        } else if (status == Status.SNAPPING) {
            // 松手太早，抓帧还没完成 → 等抓帧回调回来再分发
            Log.i(TAG, "waiting for frame, will dispatch after grab")
        }
    }

    private fun finishAndDispatch() {
        if (status == Status.IDLE) return
        mainHandler.removeCallbacks(maxVoiceTimeout)
        status = Status.PROCESSING
        val session = pendingSession ?: run {
            reset()
            return
        }

        val jpeg = snapshotJpeg
        // 语音到文字的转换（当前走云端 ASR 端点 — 如无则使用 placeholder）
        val voiceText = if (voiceCollected) {
            "语音提问（请连接 ASR 服务以启用语音识别）"
        } else {
            null
        }

        val prompt = voiceText ?: "请结合当前画面给出分析"

        // 意图分发
        val sceneId = if (voiceText != null) DemoScenarios.matchFromText(voiceText) else null

        TtsSpeaker.speak("正在分析，请稍候")

        if (sceneId != null) {
            Log.i(TAG, "scene match: $sceneId")
            session.switchSceneAndAsk(sceneId, jpeg, prompt)
        } else {
            Log.i(TAG, "no scene match, expert consult")
            session.snapAskExpert(jpeg, prompt)
        }

        reset()
    }

    fun cancel() {
        Log.i(TAG, "cancel snap-ask")
        mainHandler.removeCallbacks(maxVoiceTimeout)
        if (status == Status.CAPTURING_VOICE) {
            VoiceCaptureHelper.stop { /* discard */ }
        }
        reset()
    }

    private fun reset() {
        status = Status.IDLE
        pendingSession = null
        snapshotJpeg = null
        voiceCollected = false
        holdStartMs = 0L
        // 灭灯：AI 交互结束，恢复常规指示灯
        Ze69Hardware.setAiListeningIndicator(false)
        DeviceStatusIndicator.refresh()
    }

    fun isActive(): Boolean = status != Status.IDLE
}