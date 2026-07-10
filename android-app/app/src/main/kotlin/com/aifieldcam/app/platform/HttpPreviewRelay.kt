package com.aifieldcam.app.platform

import android.util.Base64
import android.util.Log
import com.aifieldcam.app.data.ApiClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 录像期间将 JPEG 预览帧 POST 到后端，供 AI-screen Web 轮询显示。
 * 不切换 [NativeRecorder] 编码路径，避免影响现有录像稳定性。
 */
object HttpPreviewRelay {

    private const val TAG = "HttpPreview"
    private const val FRAME_INTERVAL_MS = 800L

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "HttpPreview").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)
    private var callId: String = ""
    private var scheduled: ScheduledFuture<*>? = null

    fun start(callId: String) {
        stop()
        if (callId.isBlank()) return
        this.callId = callId
        running.set(true)
        scheduled = executor.scheduleAtFixedRate(
            { tickUpload() },
            0,
            FRAME_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        Log.i(TAG, "preview relay started callId=$callId")
    }

    fun stop() {
        running.set(false)
        scheduled?.cancel(false)
        scheduled = null
        callId = ""
        Log.i(TAG, "preview relay stopped")
    }

    fun isRunning(): Boolean = running.get()

    private fun tickUpload() {
        if (!running.get() || callId.isBlank()) return
        if (!NativeRecorder.isRecording()) return
        NativeRecorder.grabRecordingFrame { frame ->
            if (frame == null || frame.isEmpty()) return@grabRecordingFrame
            val b64 = Base64.encodeToString(frame, Base64.NO_WRAP)
            ApiClient.postWebRtcFrame(callId, b64) { ok, err ->
                if (!ok && err.isNotBlank()) {
                    Log.w(TAG, "frame upload failed: $err")
                }
            }
        }
    }
}
