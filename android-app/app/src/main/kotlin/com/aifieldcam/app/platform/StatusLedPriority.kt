package com.aifieldcam.app.platform

/**
 * 状态指示灯优先级解析（纯逻辑，供单测；真机由 [DeviceStatusIndicator] 驱动红/绿节点）。
 *
 * 指挥连线：红常亮；连线中按住 PTT：黄常亮（红+绿）；按住 PTT 开 AI：黄常亮。
 */
enum class StatusLedPattern {
    COMMAND_CALL_PTT_YELLOW_STEADY,
    AI_LISTEN_YELLOW_STEADY,
    COMMAND_CALL_RED_STEADY,
    STREAM_RG_BLINK,
    VIDEO_RED_BLINK,
    AUDIO_YELLOW_BLINK,
    CHARGE_RED_STEADY,
    FULL_GREEN_STEADY,
    STANDBY_GREEN_STEADY,
}

object StatusLedPriority {
    fun resolve(
        commandCallPtt: Boolean,
        aiListening: Boolean,
        commandCallActive: Boolean,
        videoStreaming: Boolean,
        videoRecording: Boolean,
        audioRecording: Boolean,
        charging: Boolean,
        fullCharge: Boolean,
    ): StatusLedPattern = when {
        commandCallPtt -> StatusLedPattern.COMMAND_CALL_PTT_YELLOW_STEADY
        aiListening -> StatusLedPattern.AI_LISTEN_YELLOW_STEADY
        commandCallActive -> StatusLedPattern.COMMAND_CALL_RED_STEADY
        videoStreaming -> StatusLedPattern.STREAM_RG_BLINK
        videoRecording -> StatusLedPattern.VIDEO_RED_BLINK
        audioRecording -> StatusLedPattern.AUDIO_YELLOW_BLINK
        charging && fullCharge -> StatusLedPattern.FULL_GREEN_STEADY
        charging -> StatusLedPattern.CHARGE_RED_STEADY
        else -> StatusLedPattern.STANDBY_GREEN_STEADY
    }
}
