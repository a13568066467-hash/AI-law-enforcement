package com.aifieldcam.app.platform

/**
 * 低电策略（PRD：≤15% 警示灯，≤10% 禁新录像/AI）
 */
object BatteryPolicy {

    const val WARN_PERCENT = 15
    const val BLOCK_PERCENT = 10

    @Volatile
    var levelPercent: Int = 100
        private set

    fun update(level: Int) {
        levelPercent = level.coerceIn(0, 100)
    }

    fun shouldWarn(): Boolean = levelPercent in 1..WARN_PERCENT

    fun shouldBlockNewWork(): Boolean = levelPercent in 0..BLOCK_PERCENT

    fun blockReason(): String =
        "电量 ${levelPercent}%，低于 ${BLOCK_PERCENT}% 已禁止新录像与 AI"
}
