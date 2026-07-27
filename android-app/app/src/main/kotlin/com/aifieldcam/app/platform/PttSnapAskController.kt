package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.platform.commandcall.CommandCallAiPriority
import com.aifieldcam.app.platform.commandcall.CommandCallController
import com.aifieldcam.app.util.TtsSpeaker
import java.util.concurrent.Executors

internal object PttRealtimeReducer {
    enum class Event { PRESS_READY, PRESS_CONNECTING, RELEASE, AUDIO, DONE, FAILURE }

    fun reduce(phase: RealtimeVoicePhase, event: Event): RealtimeVoicePhase = when (event) {
        Event.PRESS_READY -> RealtimeVoicePhase.LISTENING
        Event.PRESS_CONNECTING -> RealtimeVoicePhase.CONNECTING
        Event.RELEASE -> if (phase == RealtimeVoicePhase.LISTENING) {
            RealtimeVoicePhase.THINKING
        } else {
            RealtimeVoicePhase.IDLE
        }
        Event.AUDIO -> if (phase == RealtimeVoicePhase.LISTENING) phase else RealtimeVoicePhase.SPEAKING
        Event.DONE -> RealtimeVoicePhase.IDLE
        Event.FAILURE -> RealtimeVoicePhase.ERROR
    }
}

/**
 * 物理 PTT 全双工语音控制器。
 *
 * 达到按键分发器的 500ms 阈值后流式发送 PCM；松手提交；回答期间再次按下会打断回答。
 */
internal object PttSnapAskController : RealtimeVoiceClient.Listener {
    enum class Status {
        IDLE,
        CONNECTING,
        CAPTURING_VOICE,
        PROCESSING,
        SPEAKING,
        ERROR,
    }

    private const val TAG = "PttRealtimeVoice"
    private const val MAX_VOICE_MS = 15_000L
    private const val FRAME_INTERVAL_MS = 1_000L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val frameCompressExecutor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "PttRealtimeFrameCompress").apply { isDaemon = true }
        }
    }
    private var client: RealtimeVoiceClient? = null
    private val player = RealtimeAudioPlayer()
    private var activeConfig: RealtimeVoiceClient.Config? = null
    private var pendingSession: SessionManager? = null
    private var pressed = false
    private var serverReady = false
    private var phase = RealtimeVoicePhase.IDLE
    private var framePumpRunning = false
    private var frameInFlight = false
    private var audioGateOpened = false

    val status: Status
        get() = when (phase) {
            RealtimeVoicePhase.IDLE -> Status.IDLE
            RealtimeVoicePhase.CONNECTING -> Status.CONNECTING
            RealtimeVoicePhase.LISTENING -> Status.CAPTURING_VOICE
            RealtimeVoicePhase.THINKING -> Status.PROCESSING
            RealtimeVoicePhase.SPEAKING -> Status.SPEAKING
            RealtimeVoicePhase.ERROR -> Status.ERROR
        }

    private val maxVoiceTimeout = Runnable {
        if (phase == RealtimeVoicePhase.LISTENING) {
            Log.i(TAG, "voice capture timeout (${MAX_VOICE_MS}ms)")
            onPttUp()
        }
    }

    fun onPttDown(session: SessionManager) {
        if (!CommandCallAiPriority.allowsAiRealtime(CommandCallController.isInCall())) {
            return
        }
        if (phase == RealtimeVoicePhase.LISTENING ||
            phase == RealtimeVoicePhase.CONNECTING
        ) {
            return
        }
        TtsSpeaker.stop()
        if (phase == RealtimeVoicePhase.SPEAKING ||
            phase == RealtimeVoicePhase.THINKING
        ) {
            client?.cancelResponse()
        }
        player.flushAndStop()
        mainHandler.removeCallbacks(maxVoiceTimeout)

        val config = session.realtimeVoiceConfig()
        if (config == null) {
            fail(session, "请先扫码绑定后再使用 AI 助手")
            return
        }

        pendingSession = session
        pressed = true
        if (client == null) client = RealtimeVoiceClient(this)
        if (activeConfig != config || !serverReady || client?.isConnected() != true) {
            activeConfig = config
            serverReady = false
            updatePhase(
                PttRealtimeReducer.reduce(
                    phase,
                    PttRealtimeReducer.Event.PRESS_CONNECTING,
                ),
            )
            client?.connect(config)
        } else {
            beginCapture()
        }
    }

    fun onPttUp() {
        pressed = false
        stopFramePump()
        audioGateOpened = false
        mainHandler.removeCallbacks(maxVoiceTimeout)
        when (phase) {
            RealtimeVoicePhase.LISTENING -> {
                VoiceCaptureHelper.stopStreaming {
                    if (client?.commit() == true) {
                        updatePhase(
                            PttRealtimeReducer.reduce(
                                phase,
                                PttRealtimeReducer.Event.RELEASE,
                            ),
                        )
                    } else {
                        fail(pendingSession, "语音未能发送，请重试")
                    }
                }
            }
            RealtimeVoicePhase.CONNECTING -> updatePhase(RealtimeVoicePhase.IDLE)
            else -> Unit
        }
    }

    fun cancel() {
        pressed = false
        stopFramePump()
        audioGateOpened = false
        mainHandler.removeCallbacks(maxVoiceTimeout)
        VoiceCaptureHelper.stopStreaming()
        client?.cancelResponse()
        player.flushAndStop()
        updatePhase(RealtimeVoicePhase.IDLE)
    }

    /**
     * 指挥来电打断：停采播、取消回答、断开 Realtime，并置 IDLE。
     * 不保留「稍后自动恢复」状态。
     */
    fun interruptForCommandCall() {
        pressed = false
        stopFramePump()
        audioGateOpened = false
        mainHandler.removeCallbacks(maxVoiceTimeout)
        VoiceCaptureHelper.stopStreaming()
        client?.cancelResponse()
        player.flushAndStop()
        client?.disconnect()
        client = null
        serverReady = false
        activeConfig = null
        updatePhase(RealtimeVoicePhase.IDLE)
        pendingSession = null
    }

    fun isActive(): Boolean = phase != RealtimeVoicePhase.IDLE

    override fun onConnectionStateChanged(state: RealtimeVoiceClient.ConnectionState) {
        when (state) {
            RealtimeVoiceClient.ConnectionState.CONNECTING -> {
                if (pressed) updatePhase(RealtimeVoicePhase.CONNECTING)
            }
            RealtimeVoiceClient.ConnectionState.CONNECTED -> Unit
            RealtimeVoiceClient.ConnectionState.DISCONNECTED -> {
                serverReady = false
                if (pressed) updatePhase(RealtimeVoicePhase.CONNECTING)
            }
        }
    }

    override fun onEvent(event: RealtimeVoiceEvent) {
        when (event) {
            RealtimeVoiceEvent.Ready -> {
                serverReady = true
                if (pressed) beginCapture() else updatePhase(RealtimeVoicePhase.IDLE)
            }
            RealtimeVoiceEvent.ResponseStarted -> {
                if (!pressed) updatePhase(RealtimeVoicePhase.THINKING)
            }
            RealtimeVoiceEvent.ResponseDone -> {
                if (!pressed) {
                    updatePhase(
                        PttRealtimeReducer.reduce(phase, PttRealtimeReducer.Event.DONE),
                    )
                }
            }
            is RealtimeVoiceEvent.ToolCall -> {
                val session = pendingSession
                if (session == null) {
                    client?.sendToolResult(
                        event.callId,
                        org.json.JSONObject().put("ok", false).put("error", "会话已结束"),
                    )
                } else {
                    session.executeRealtimeTool(event) { output ->
                        client?.sendToolResult(event.callId, output)
                    }
                }
            }
            is RealtimeVoiceEvent.Error -> fail(
                pendingSession,
                event.message.ifBlank { "实时语音服务异常" },
            )
            is RealtimeVoiceEvent.UserTranscript -> {
                if (!event.partial) Log.i(TAG, "user transcript: ${event.text}")
            }
            is RealtimeVoiceEvent.AssistantTranscript -> {
                if (!event.partial) Log.i(TAG, "assistant transcript: ${event.text}")
            }
        }
    }

    override fun onAudio(pcm24k: ByteArray) {
        if (pressed) return
        updatePhase(PttRealtimeReducer.reduce(phase, PttRealtimeReducer.Event.AUDIO))
        player.enqueue(pcm24k)
    }

    private fun beginCapture() {
        if (!pressed || VoiceCaptureHelper.isCapturing()) return
        updatePhase(
            PttRealtimeReducer.reduce(phase, PttRealtimeReducer.Event.PRESS_READY),
        )
        VoiceCaptureHelper.startStreaming(
            onPcm = { pcm ->
                val sent = client?.sendAudio(pcm) == true
                if (sent && !audioGateOpened) {
                    audioGateOpened = true
                    startFramePump()
                }
            },
            onStarted = {
                audioGateOpened = false
                mainHandler.removeCallbacks(maxVoiceTimeout)
                mainHandler.postDelayed(maxVoiceTimeout, MAX_VOICE_MS)
            },
            onError = { error -> fail(pendingSession, error) },
        )
    }

    private fun onFrameTick() {
        if (!framePumpRunning || !pressed || phase != RealtimeVoicePhase.LISTENING) {
            stopFramePump()
            return
        }
        if (!audioGateOpened || frameInFlight) {
            mainHandler.postDelayed(::onFrameTick, FRAME_INTERVAL_MS)
            return
        }
        val session = pendingSession
        if (session == null) {
            stopFramePump()
            return
        }
        frameInFlight = true
        session.grabSnapshot { jpeg ->
            if (!framePumpRunning || !pressed) {
                finishFrameTick()
                return@grabSnapshot
            }
            if (jpeg == null || jpeg.isEmpty()) {
                finishFrameTick()
                return@grabSnapshot
            }
            // JPEG decode/scale/compress 离主线程，避免卡 UI；发送后回主线程排下一拍
            frameCompressExecutor.execute {
                val compressed = try {
                    RealtimeFrameCompressor.compressForRealtime(jpeg)
                } catch (e: Exception) {
                    Log.w(TAG, "frame compress failed: ${e.message}")
                    null
                }
                if (compressed != null && framePumpRunning && pressed) {
                    client?.sendImage(compressed)
                }
                mainHandler.post { finishFrameTick() }
            }
        }
    }

    private fun finishFrameTick() {
        frameInFlight = false
        if (framePumpRunning && pressed) {
            mainHandler.postDelayed(::onFrameTick, FRAME_INTERVAL_MS)
        }
    }

    private fun startFramePump() {
        stopFramePump()
        framePumpRunning = true
        frameInFlight = false
        mainHandler.post(::onFrameTick)
    }

    private fun stopFramePump() {
        framePumpRunning = false
        frameInFlight = false
        mainHandler.removeCallbacks(::onFrameTick)
    }

    private fun fail(session: SessionManager?, message: String) {
        pressed = false
        stopFramePump()
        audioGateOpened = false
        mainHandler.removeCallbacks(maxVoiceTimeout)
        VoiceCaptureHelper.stopStreaming()
        player.flushAndStop()
        if (session != null) {
            pendingSession = session
            updatePhase(
                PttRealtimeReducer.reduce(phase, PttRealtimeReducer.Event.FAILURE),
            )
        } else {
            phase = RealtimeVoicePhase.ERROR
        }
        TtsSpeaker.speak(message)
        mainHandler.postDelayed(
            {
                if (phase == RealtimeVoicePhase.ERROR) {
                    updatePhase(RealtimeVoicePhase.IDLE)
                }
            },
            1_500L,
        )
    }

    private fun updatePhase(next: RealtimeVoicePhase) {
        if (phase == next) return
        phase = next
        pendingSession?.setRealtimeVoicePhase(next)
    }
}
