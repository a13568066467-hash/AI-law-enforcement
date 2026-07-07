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
    fun accessibility_skippedWhenActivityHandlesKeys() {
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
    fun accessibility_handlesWhenScreenOff() {
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
    fun duplicateEventFromTwoSources_isDropped() {
        RecorderKeyRoute.activityHandlesKeys = true
        assertTrue(
            RecorderKeyRoute.accept(
                RecorderKeyRoute.Source.ACTIVITY,
                KEYCODE_F5,
                ACTION_DOWN,
                nowMs = 1000L,
            ),
        )
        RecorderKeyRoute.activityHandlesKeys = false
        assertFalse(
            RecorderKeyRoute.accept(
                RecorderKeyRoute.Source.ACCESSIBILITY,
                KEYCODE_F5,
                ACTION_DOWN,
                nowMs = 1100L,
            ),
        )
    }

    @Test
    fun sameKeyAfterDebounceWindow_isAccepted() {
        assertTrue(
            RecorderKeyRoute.accept(
                RecorderKeyRoute.Source.ACTIVITY,
                KEYCODE_F4,
                ACTION_DOWN,
                nowMs = 1000L,
            ),
        )
        assertTrue(
            RecorderKeyRoute.accept(
                RecorderKeyRoute.Source.ACTIVITY,
                KEYCODE_F4,
                ACTION_DOWN,
                nowMs = 2000L,
            ),
        )
    }
}
