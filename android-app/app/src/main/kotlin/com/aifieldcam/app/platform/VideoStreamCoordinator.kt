package com.aifieldcam.app.platform

import android.util.Log
import com.aifieldcam.app.data.SessionManager

/**
 * V2 视频连线协调：推流时优先 [MediaEncoderPipeline] + NAL/JPEG 双路上传。
 */
object VideoStreamCoordinator {

    private const val TAG = "VideoStreamCoord"

    @Volatile
    private var activeCallId: String = ""

    @Volatile
    private var previewOnly: Boolean = false

    /** 开录完成后启动预览（startRecord 异步） */
    @Volatile
    private var pendingPreviewCallId: String? = null

    @Volatile
    private var streamStartedWithPipeline: Boolean = false

    fun activeCallId(): String = activeCallId

    fun isPreviewStreaming(): Boolean = previewOnly &&
        (HttpPreviewRelay.isRunning() || HttpNalRelay.isRunning())

    fun prepareCall(callId: String) {
        activeCallId = callId
    }

    /**
     * 指挥中心发起连线。
     * - 未录像：启用 V2 管线并自动开录
     * - 已在录像：仅 JPEG 预览（无法中途切换编码器）
     */
    fun beginCall(session: SessionManager, callId: String, reason: String) {
        if (callId.isBlank()) return
        activeCallId = callId
        previewOnly = true
        VideoStreamManager.markPreviewActive(true)

        when {
            NativeRecorder.isRecording() -> {
                Log.i(TAG, "preview on existing recording ($reason)")
                startRelays(callId, useNal = NativeRecorder.useMediaEncoderPipeline)
            }
            NativeRecorder.isBusy() -> {
                Log.w(TAG, "camera busy, cannot start call")
                session.showStreamError("相机忙，无法建立视频连线")
                endCall(session, "camera-busy")
            }
            else -> {
                Log.i(TAG, "V2 pipeline auto-record for call ($reason)")
                NativeRecorder.useMediaEncoderPipeline = true
                streamStartedWithPipeline = true
                pendingPreviewCallId = callId
                if (!session.startRecord()) {
                    Log.w(TAG, "auto record failed: ${session.getLastActionError()}")
                    NativeRecorder.useMediaEncoderPipeline = false
                    streamStartedWithPipeline = false
                    pendingPreviewCallId = null
                    endCall(session, "auto-record-failed")
                }
            }
        }

        DeviceStatusIndicator.setVideoStreaming(true)
        Log.i(TAG, "call armed callId=$callId reason=$reason")
    }

    /** [SessionManager.onNativeRecordStarted] 回调 */
    fun onRecordStartedForStream() {
        val pending = pendingPreviewCallId ?: return
        pendingPreviewCallId = null
        startRelays(pending, useNal = streamStartedWithPipeline)
        if (streamStartedWithPipeline) {
            Log.i(TAG, "V2 pipeline record ready for stream")
        }
    }

    private fun startRelays(callId: String, useNal: Boolean) {
        HttpPreviewRelay.start(callId)
        if (useNal && MediaEncoderPipeline.isEncoding()) {
            HttpNalRelay.start(callId)
        }
    }

    fun endCall(session: SessionManager, reason: String) {
        Log.i(TAG, "call ended callId=$activeCallId reason=$reason")
        pendingPreviewCallId = null
        HttpPreviewRelay.stop()
        HttpNalRelay.stop()
        StreamingPipelineWatchdog.stop()
        StreamingPipelineWatchdog.onStopStreaming = null
        VideoStreamManager.markPreviewActive(false)
        DeviceStatusIndicator.setVideoStreaming(false)
        WebRtcPeer.endCall()
        activeCallId = ""
        previewOnly = false
        streamStartedWithPipeline = false
        // 录像仍在跑时禁止清管线开关，否则 stopRecording 会误走 MediaRecorder 路径
        if (!NativeRecorder.isRecording() && !MediaEncoderPipeline.isEncoding()) {
            if (!DeviceProfile.CONTINUOUS_LOOP_RECORDING) {
                NativeRecorder.useMediaEncoderPipeline = false
            }
        }
        session.onVideoStreamEnded(reason)
    }

    fun onHttpCallStart(session: SessionManager, callId: String, caller: String) {
        prepareCall(callId)
        session.onWebRtcCallStart(caller, callId)
    }
}
