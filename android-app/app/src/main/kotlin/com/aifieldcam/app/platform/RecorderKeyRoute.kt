package com.aifieldcam.app.platform

/**
 * 侧键路由：Activity 前台时由 [MainActivity] 处理；息屏/后台由无障碍处理。
 * 全局去重，避免两路同时消费同一物理键。
 */
object RecorderKeyRoute {

    enum class Source { ACTIVITY, ACCESSIBILITY }

    @Volatile
    var activityHandlesKeys: Boolean = false

    private const val DEDUP_MS = 450L
    private var lastKeyCode = 0
    private var lastAction = 0
    private var lastSource: Source? = null
    private var lastAtMs = 0L

    /** 无障碍是否应处理此键（Activity 已在前台接管时跳过）。 */
    fun shouldAccessibilityHandle(): Boolean = !activityHandlesKeys

    /**
     * @return true 表示应继续分发；false 表示重复事件应丢弃
     */
    @Synchronized
    fun accept(source: Source, keyCode: Int, action: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (source == Source.ACCESSIBILITY && !shouldAccessibilityHandle()) {
            return false
        }
        if (keyCode == lastKeyCode &&
            action == lastAction &&
            nowMs - lastAtMs < DEDUP_MS
        ) {
            return false
        }
        lastKeyCode = keyCode
        lastAction = action
        lastSource = source
        lastAtMs = nowMs
        return true
    }

    /** 测试用 */
    internal fun resetForTest() {
        activityHandlesKeys = false
        lastKeyCode = 0
        lastAction = 0
        lastSource = null
        lastAtMs = 0L
    }
}
