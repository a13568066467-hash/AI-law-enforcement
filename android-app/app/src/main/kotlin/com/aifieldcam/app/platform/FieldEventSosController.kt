package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.platform.commandcall.CommandCallAiSuppressLatch
import com.aifieldcam.app.platform.commandcall.CommandCallController
import com.aifieldcam.app.util.TtsSpeaker

/**
 * SOS 按住说话 → 实时转写 → 松手等最终稿 → 上传现场事件工单（仅 transcript）。
 */
internal object FieldEventSosController : RealtimeVoiceClient.Listener {

    private const val TAG = "FieldEventSos"
    private const val MAX_VOICE_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeSession: SessionManager? = null

    @Volatile
    private var capturing = false

    @Volatile
    private var awaitingFinal = false

    @Volatile
    private var finalTranscript: String? = null

    @Volatile
    private var serverReady = false

    private var client: RealtimeVoiceClient? = null

    fun isCapturing(): Boolean = capturing || awaitingFinal

    fun onHoldReady(session: SessionManager) {
        val deny = FieldEventSosPolicy.allowCapture(
            deviceBound = session.isDeviceBound(),
            commandCallActive = CommandCallAiSuppressLatch.blocksAiRealtime(
                CommandCallController.isInCall(),
            ),
            aiAssistantActive = PttSnapAskController.isActive(),
        )
        if (deny != null) {
            val msg = FieldEventSosPolicy.denyMessage(deny)
            Log.i(TAG, "deny capture: $deny -> $msg")
            TtsSpeaker.speak(msg)
            return
        }
        if (capturing || awaitingFinal) return

        val config = session.realtimeVoiceConfig()
        if (config == null) {
            TtsSpeaker.speak("请先扫码绑定后再上报")
            return
        }

        activeSession = session
        capturing = true
        awaitingFinal = false
        finalTranscript = null
        serverReady = false
        TtsSpeaker.stop()

        if (client == null) client = RealtimeVoiceClient(this)
        client?.connect(config, keepAlive = false)
        mainHandler.postDelayed(maxDurationStop, MAX_VOICE_MS)
    }

    private val maxDurationStop = Runnable {
        if (capturing) {
            Log.i(TAG, "SOS voice max duration -> submit")
            onRelease()
        }
    }

    private val finalWaitTimeout = Runnable {
        if (!awaitingFinal) return@Runnable
        finishWithTranscript(timedOut = true)
    }

    fun onRelease() {
        mainHandler.removeCallbacks(maxDurationStop)
        if (!capturing) return
        capturing = false
        val session = activeSession
        if (session == null) {
            cleanupVoice()
            return
        }
        TtsSpeaker.speak("正在整理上报")
        awaitingFinal = true
        finalTranscript = null
        VoiceCaptureHelper.stopStreaming {
            if (client?.commitInput() != true) {
                awaitingFinal = false
                cleanupVoice()
                activeSession = null
                TtsSpeaker.speak("语音未能发送，请重试")
                return@stopStreaming
            }
            mainHandler.postDelayed(
                finalWaitTimeout,
                FieldEventTranscriptWaitPolicy.TIMEOUT_MS,
            )
            // 若最终稿已先到（竞态），立即结算
            finishWithTranscript(timedOut = false)
        }
    }

    private fun finishWithTranscript(timedOut: Boolean) {
        if (!awaitingFinal) return
        when (
            val resolve = FieldEventTranscriptWaitPolicy.resolve(finalTranscript, timedOut)
        ) {
            FieldEventTranscriptWaitPolicy.Resolve.Wait -> return
            FieldEventTranscriptWaitPolicy.Resolve.RejectEmpty -> {
                awaitingFinal = false
                mainHandler.removeCallbacks(finalWaitTimeout)
                cleanupVoice()
                activeSession = null
                TtsSpeaker.speak("未识别到有效语音")
            }
            is FieldEventTranscriptWaitPolicy.Resolve.Submit -> {
                awaitingFinal = false
                mainHandler.removeCallbacks(finalWaitTimeout)
                val session = activeSession
                activeSession = null
                cleanupVoice()
                if (session == null) {
                    TtsSpeaker.speak("上报失败")
                    return
                }
                session.submitFieldEventTranscript(resolve.transcript)
            }
        }
    }

    fun cancel() {
        mainHandler.removeCallbacks(maxDurationStop)
        mainHandler.removeCallbacks(finalWaitTimeout)
        capturing = false
        awaitingFinal = false
        finalTranscript = null
        activeSession = null
        cleanupVoice()
    }

    private fun cleanupVoice() {
        VoiceCaptureHelper.stopStreaming()
        client?.cancelResponse()
        client?.disconnect()
        client = null
        serverReady = false
    }

    private fun beginCapture() {
        if (!capturing || !serverReady) return
        if (VoiceCaptureHelper.isCapturing()) return
        VoiceCaptureHelper.startStreaming(
            onPcm = { pcm -> client?.sendAudio(pcm) },
            onStarted = {
                Log.i(TAG, "SOS realtime capture started")
            },
            onError = { err ->
                capturing = false
                awaitingFinal = false
                activeSession = null
                cleanupVoice()
                Log.w(TAG, "SOS capture error: $err")
                TtsSpeaker.speak(err.ifBlank { "无法录音" })
            },
        )
    }

    override fun onConnectionStateChanged(state: RealtimeVoiceClient.ConnectionState) {
        when (state) {
            RealtimeVoiceClient.ConnectionState.CONNECTING -> Unit
            RealtimeVoiceClient.ConnectionState.CONNECTED -> Unit
            RealtimeVoiceClient.ConnectionState.DISCONNECTED -> {
                serverReady = false
                if (capturing) {
                    capturing = false
                    VoiceCaptureHelper.stopStreaming()
                    activeSession = null
                    TtsSpeaker.speak("实时语音服务异常")
                }
            }
        }
    }

    override fun onEvent(event: RealtimeVoiceEvent) {
        when (event) {
            RealtimeVoiceEvent.Ready -> {
                serverReady = true
                if (capturing) beginCapture()
            }
            is RealtimeVoiceEvent.UserTranscript -> {
                if (!event.partial) {
                    finalTranscript = event.text
                    Log.i(TAG, "final transcript: ${event.text}")
                    if (awaitingFinal) finishWithTranscript(timedOut = false)
                }
            }
            is RealtimeVoiceEvent.Error -> {
                if (capturing || awaitingFinal) {
                    capturing = false
                    awaitingFinal = false
                    mainHandler.removeCallbacks(maxDurationStop)
                    mainHandler.removeCallbacks(finalWaitTimeout)
                    activeSession = null
                    cleanupVoice()
                    TtsSpeaker.speak(event.message.ifBlank { "实时语音服务异常" })
                }
            }
            else -> Unit
        }
    }

    override fun onAudio(pcm24k: ByteArray) {
        // 工单路径不播助手音频
    }
}
