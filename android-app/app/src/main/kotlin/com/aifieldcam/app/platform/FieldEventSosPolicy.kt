package com.aifieldcam.app.platform

/**
 * SOS 按住上报现场事件工单的门禁（纯策略，无 Android 依赖便于单测）。
 */
object FieldEventSosPolicy {

    enum class DenyReason {
        NOT_BOUND,
        IN_COMMAND_CALL,
        AI_ASSISTANT_BUSY,
    }

    fun allowCapture(
        deviceBound: Boolean,
        commandCallActive: Boolean,
        aiAssistantActive: Boolean = false,
    ): DenyReason? {
        if (!deviceBound) return DenyReason.NOT_BOUND
        if (commandCallActive) return DenyReason.IN_COMMAND_CALL
        if (aiAssistantActive) return DenyReason.AI_ASSISTANT_BUSY
        return null
    }

    fun denyMessage(reason: DenyReason): String = when (reason) {
        DenyReason.NOT_BOUND -> "请先扫码绑定后再上报"
        DenyReason.IN_COMMAND_CALL -> "连线中无法上报"
        DenyReason.AI_ASSISTANT_BUSY -> "语音助手使用中，请稍后再上报"
    }
}
