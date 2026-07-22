package com.aifieldcam.app.platform

/**
 * 专机锁定维保出口计数：仅未绑定人员时有效；在「我的 → 设置 → 解绑」连续点击达到阈值则应进入系统桌面。
 */
class UnboundDesktopEscapeCounter(
    private val requiredTaps: Int = REQUIRED_TAPS,
    private val maxGapMs: Long = MAX_GAP_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var count = 0
    private var lastTapAt = 0L

    fun reset() {
        count = 0
        lastTapAt = 0L
    }

    /**
     * @return true 表示本次数满，调用方应打开系统桌面并 [reset]。
     */
    fun onTap(unbound: Boolean): Boolean {
        if (!unbound) {
            reset()
            return false
        }
        val now = clock()
        if (lastTapAt > 0L && now - lastTapAt > maxGapMs) {
            count = 0
        }
        lastTapAt = now
        count += 1
        if (count < requiredTaps) return false
        reset()
        return true
    }

    fun currentCountForTests(): Int = count

    companion object {
        const val REQUIRED_TAPS = 7
        const val MAX_GAP_MS = 2_000L
    }
}
