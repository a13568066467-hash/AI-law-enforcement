package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.platform.commandcall.CommandCallController
import com.aifieldcam.app.util.TtsSpeaker

/**
 * SOS 按住说话 → 松手上传现场事件工单。
 */
internal object FieldEventSosController {

    private const val TAG = "FieldEventSos"
    private const val MIN_PCM_BYTES = 1600 // ~50ms @16kHz mono s16

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeSession: SessionManager? = null

    @Volatile
    private var capturing = false

    fun isCapturing(): Boolean = capturing

    fun onHoldReady(session: SessionManager) {
        val deny = FieldEventSosPolicy.allowCapture(
            deviceBound = session.isDeviceBound(),
            commandCallActive = CommandCallController.isInCall(),
        )
        if (deny != null) {
            val msg = FieldEventSosPolicy.denyMessage(deny)
            Log.i(TAG, "deny capture: $deny")
            TtsSpeaker.speak(msg)
            return
        }
        if (capturing) return
        activeSession = session
        capturing = true
        VoiceCaptureHelper.start(
            onStarted = {
                Log.i(TAG, "SOS voice capture started")
            },
            onError = { err ->
                capturing = false
                activeSession = null
                Log.w(TAG, "SOS capture error: $err")
                TtsSpeaker.speak(err.ifBlank { "无法录音" })
            },
        )
        mainHandler.postDelayed(maxDurationStop, VoiceCaptureHelper.maxDurationMs())
    }

    private val maxDurationStop = Runnable {
        if (capturing) {
            Log.i(TAG, "SOS voice max duration -> submit")
            onRelease()
        }
    }

    fun onRelease() {
        mainHandler.removeCallbacks(maxDurationStop)
        if (!capturing) return
        capturing = false
        val session = activeSession
        activeSession = null
        if (session == null) {
            VoiceCaptureHelper.stop { }
            return
        }
        TtsSpeaker.speak("正在整理上报")
        VoiceCaptureHelper.stop { pcm ->
            if (pcm == null || pcm.size < MIN_PCM_BYTES) {
                TtsSpeaker.speak("未识别到有效语音")
                return@stop
            }
            session.submitFieldEventAudioPcm(pcm)
        }
    }

    fun cancel() {
        mainHandler.removeCallbacks(maxDurationStop)
        capturing = false
        activeSession = null
        VoiceCaptureHelper.stop { }
    }
}
