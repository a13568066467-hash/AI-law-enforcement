package com.aifieldcam.app.platform.commandcall

/**
 * 连线对讲采音接缝：录像中应走 PCM tee 共麦，避免第二路 AudioRecord。
 */
interface CommandCallAudioCapture {
    fun start(
        onPcm: (ByteArray) -> Unit,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    )

    fun stop(onStopped: () -> Unit = {})
}

/** 生产默认：复用 [com.aifieldcam.app.platform.VoiceCaptureHelper] 的共麦策略。 */
object DefaultCommandCallAudioCapture : CommandCallAudioCapture {
    override fun start(
        onPcm: (ByteArray) -> Unit,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        com.aifieldcam.app.platform.VoiceCaptureHelper.startStreaming(onPcm, onStarted, onError)
    }

    override fun stop(onStopped: () -> Unit) {
        com.aifieldcam.app.platform.VoiceCaptureHelper.stopStreaming(onStopped)
    }
}
