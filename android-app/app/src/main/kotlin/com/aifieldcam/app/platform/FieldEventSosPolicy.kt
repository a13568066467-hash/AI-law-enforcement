package com.aifieldcam.app.platform

/**
 * SOS 按住上报现场事件工单的门禁（纯策略，无 Android 依赖便于单测）。
 */
object FieldEventSosPolicy {

    enum class DenyReason {
        NOT_BOUND,
        IN_COMMAND_CALL,
    }

    fun allowCapture(deviceBound: Boolean, commandCallActive: Boolean): DenyReason? {
        if (!deviceBound) return DenyReason.NOT_BOUND
        if (commandCallActive) return DenyReason.IN_COMMAND_CALL
        return null
    }

    fun denyMessage(reason: DenyReason): String = when (reason) {
        DenyReason.NOT_BOUND -> "请先扫码绑定后再上报"
        DenyReason.IN_COMMAND_CALL -> "连线中无法上报"
    }
}
