package com.aifieldcam.app.platform

/**
 * 侧键路由：Activity 前台时由 [MainActivity] 处理；息屏/后台由无障碍处理。
 * 两路互斥，确保每一路不会在对方应负责时消费事件。
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

    /** 无障碍是否应处理此键（Activity 不在前台时无障碍接管）。 */
    fun shouldAccessibilityHandle(): Boolean = !activityHandlesKeys

    /**
     * 互斥路由 + 同路去重。
     * - Activity 前台时：拒绝无障碍事件，Activity 通过去重保护
     * - Activity 后台时：拒绝 Activity 事件（防止 paused Activity 仍收到 KeyEvent），无障碍通过去重保护
     * @return true 表示应继续分发；false 表示应丢弃
     */
    @Synchronized
    fun accept(source: Source, keyCode: Int, action: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        // ── 互斥路由 ──
        when {
            activityHandlesKeys && source == Source.ACCESSIBILITY -> return false  // Activity 前台，无障碍不处理
            !activityHandlesKeys && source == Source.ACTIVITY -> return false       // Activity 后台，不走 Activity 分发
        }
        // ── 同路去重 ──
        if (keyCode == lastKeyCode &&
            action == lastAction &&
            source == lastSource &&
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
