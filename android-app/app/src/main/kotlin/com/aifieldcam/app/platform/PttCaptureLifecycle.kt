package com.aifieldcam.app.platform

/**
 * PTT 松手 / 断联时的采集清理策略（纯逻辑，供单测锁定）。
 *
 * 关键路径：LISTENING 中途 WS 断联会把 phase 降为 CONNECTING，但麦可能仍在采；
 * 若松手或重连时未停麦，[PttSnapAskController] 的 beginCapture 会因 isCapturing 静默返回。
 */
internal object PttCaptureLifecycle {
    data class ReleaseAction(
        val stopCapture: Boolean,
        val tryCommit: Boolean,
    )

    fun onRelease(phase: RealtimeVoicePhase): ReleaseAction = when (phase) {
        RealtimeVoicePhase.LISTENING ->
            ReleaseAction(stopCapture = true, tryCommit = true)
        // CONNECTING 可能由 LISTENING 断联降级而来，麦可能仍在采
        RealtimeVoicePhase.CONNECTING ->
            ReleaseAction(stopCapture = true, tryCommit = false)
        else ->
            ReleaseAction(stopCapture = false, tryCommit = false)
    }

    /** 按住期间断联：须先停麦，否则 Ready 后无法重新 beginCapture。 */
    fun mustStopCaptureOnDisconnect(pressed: Boolean, phase: RealtimeVoicePhase): Boolean =
        pressed && (
            phase == RealtimeVoicePhase.LISTENING ||
                phase == RealtimeVoicePhase.CONNECTING
            )

    /** 已在采时禁止静默跳过；应先停再开，否则按住无任何 UI/TTS 反馈。 */
    fun canBeginCapture(pressed: Boolean, alreadyCapturing: Boolean): Boolean =
        pressed && !alreadyCapturing

    fun shouldRestartCapture(pressed: Boolean, alreadyCapturing: Boolean): Boolean =
        pressed && alreadyCapturing
}
