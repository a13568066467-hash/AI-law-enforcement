package com.aifieldcam.app.platform

/**
 * 松手后等待最终转写的纯策略（短超时；空则拒建，不用 partial 兜底）。
 */
object FieldEventTranscriptWaitPolicy {

    const val TIMEOUT_MS = 3_000L

    /**
     * @param finalTranscript 非 partial 的最终转写；尚未到达则为 null
     * @param timedOut 是否已超过等待超时
     * @return 可提交的正文；null 表示继续等待或拒建
     */
    fun resolve(finalTranscript: String?, timedOut: Boolean): Resolve {
        val text = finalTranscript?.trim().orEmpty()
        if (text.isNotEmpty()) return Resolve.Submit(text)
        if (timedOut) return Resolve.RejectEmpty
        return Resolve.Wait
    }

    sealed interface Resolve {
        data object Wait : Resolve
        data object RejectEmpty : Resolve
        data class Submit(val transcript: String) : Resolve
    }
}
