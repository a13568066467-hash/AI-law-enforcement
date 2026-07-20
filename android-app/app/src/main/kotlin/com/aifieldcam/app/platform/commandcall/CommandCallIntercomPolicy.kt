package com.aifieldcam.app.platform.commandcall

/**
 * 指挥连线中 F6 归属：连线优先于 stub 视频流与 AI 对讲。
 */
enum class CommandCallPttOwner {
    /** 连线对讲：长按上行、短按白光 */
    COMMAND_CALL,
    /** 旧 WebRTC/推流 stub 对讲 */
    VIDEO_STREAM,
    /** 默认：长按 AI，短按白光 */
    AI_OR_LIGHT,
}

object CommandCallIntercomPolicy {

    fun pttOwner(commandCallActive: Boolean, videoStreaming: Boolean): CommandCallPttOwner = when {
        commandCallActive -> CommandCallPttOwner.COMMAND_CALL
        videoStreaming -> CommandCallPttOwner.VIDEO_STREAM
        else -> CommandCallPttOwner.AI_OR_LIGHT
    }
}
