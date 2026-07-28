package com.aifieldcam.app.platform

/**
 * AI 助手按住说话启动门禁（与现场事件工单互斥）。
 */
object AiAssistantStartPolicy {

    enum class DenyReason {
        FIELD_EVENT_BUSY,
    }

    fun allowStart(fieldEventCapturing: Boolean): DenyReason? {
        if (fieldEventCapturing) return DenyReason.FIELD_EVENT_BUSY
        return null
    }

    fun denyMessage(reason: DenyReason): String = when (reason) {
        DenyReason.FIELD_EVENT_BUSY -> "事件上报中，请稍后再使用语音助手"
    }
}
