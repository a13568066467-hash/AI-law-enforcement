package com.aifieldcam.app.platform

/**
 * Realtime WS 重连退避（保活模式下无限重试，不向用户报错）。
 */
internal object RealtimeReconnectPolicy {
    private val DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L, 5_000L, 10_000L)

    /** [attemptIndex] 从 0 起：第几次重连调度。 */
    fun delayMs(attemptIndex: Int): Long {
        if (attemptIndex <= 0) return DELAYS_MS.first()
        val idx = attemptIndex.coerceAtMost(DELAYS_MS.lastIndex)
        return DELAYS_MS[idx]
    }

    /**
     * 非保活：已重试过一次仍失败则上报；保活永不向 UI 报 connection_failed。
     * [failedAttempts] 为已发生的失败次数（含本次）。
     */
    fun shouldReportFailure(keepAlive: Boolean, failedAttempts: Int): Boolean =
        !keepAlive && failedAttempts > 1
}
