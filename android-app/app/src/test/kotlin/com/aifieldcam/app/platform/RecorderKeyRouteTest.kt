package com.aifieldcam.app.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecorderKeyRouteTest {

    private companion object {
        const val KEYCODE_F4 = 134
        const val KEYCODE_F5 = 135
        const val ACTION_DOWN = 0
    }

    @Before
    fun setUp() {
        RecorderKeyRoute.resetForTest()
    }

    @Test
    fun accessibility_blocked_whenActivityHandlesKeys() {
        // Activity 前台 → 无障碍不处理
        RecorderKeyRoute.activityHandlesKeys = true
        assertFalse(RecorderKeyRoute.shouldAccessibilityHandle())
        assertFalse(
            RecorderKeyRoute.accept(
                RecorderKeyRoute.Source.ACCESSIBILITY,
                KEYCODE_F5,
                ACTION_DOWN,
                nowMs = 1000L,
            ),
        )
    }

    @Test
    fun accessibility_accepted_whenScreenOff() {
        // Activity 后台 → 无障碍接管
        RecorderKeyRoute.activityHandlesKeys = false
        assertTrue(RecorderKeyRoute.shouldAccessibilityHandle())
        assertTrue(
            RecorderKeyRoute.accept(
                RecorderKeyRoute.Source.ACCESSIBILITY,
                KEYCODE_F5,
                ACTION_DOWN,
                nowMs = 1000L,
            ),
        )
    }

    @Test
    fun activity_blocked_whenScreenOff() {
        // Activity 后台时，ACTIVITY 路径被拒绝
        RecorderKeyRoute.activityHandlesKeys = false
        assertFalse(
            RecorderKeyRoute.accept(
                RecorderKeyRoute.Source.ACTIVITY,
                KEYCODE_F5,
                ACTION_DOWN,
                nowMs = 1000L,
            ),
        )
    }

    @Test
    fun activity_sameKey_afterDebounceWindow_isAccepted() {
        // Activity 前台时测试去重窗口
        RecorderKeyRoute.activityHandlesKeys = true
        assertTrue(
            RecorderKeyRoute.accept(
                RecorderKeyRoute.Source.ACTIVITY,
                KEYCODE_F4,
                ACTION_DOWN,
                nowMs = 1000L,
            ),
        )
        // 同源同键超过去重窗口 → 接受
        assertTrue(
            RecorderKeyRoute.accept(
                RecorderKeyRoute.Source.ACTIVITY,
                KEYCODE_F4,
                ACTION_DOWN,
                nowMs = 2000L,
            ),
        )
    }

    @Test
    fun activity_sameKey_withinDedupWindow_isDropped() {
        // Activity 前台时测试去重窗口
        RecorderKeyRoute.activityHandlesKeys = true
        assertTrue(
            RecorderKeyRoute.accept(
                RecorderKeyRoute.Source.ACTIVITY,
                KEYCODE_F5,
                ACTION_DOWN,
                nowMs = 1000L,
            ),
        )
        // 同源同键 100ms 内 → 去重拒绝
        assertFalse(
            RecorderKeyRoute.accept(
                RecorderKeyRoute.Source.ACTIVITY,
                KEYCODE_F5,
                ACTION_DOWN,
                nowMs = 1050L,
            ),
        )
    }

    @Test
    fun crossSource_notDeduped() {
        // 互斥路由已经阻止了跨源冲突，去重只检查同源
        RecorderKeyRoute.activityHandlesKeys = true
        assertTrue(
            RecorderKeyRoute.accept(
                RecorderKeyRoute.Source.ACTIVITY,
                KEYCODE_F5,
                ACTION_DOWN,
                nowMs = 1000L,
            ),
        )
        // 切换到后台
        RecorderKeyRoute.activityHandlesKeys = false
        // 不同源 → 不受去重影响
        assertTrue(
            RecorderKeyRoute.accept(
                RecorderKeyRoute.Source.ACCESSIBILITY,
                KEYCODE_F5,
                ACTION_DOWN,
                nowMs = 1100L,
            ),
        )
    }
}