package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnboundDesktopEscapeCounterTest {

    @Test
    fun sevenTapsWhenUnboundTriggers() {
        var now = 1_000L
        val c = UnboundDesktopEscapeCounter(clock = { now })
        repeat(6) {
            assertFalse(c.onTap(unbound = true))
            now += 100
        }
        assertTrue(c.onTap(unbound = true))
        assertEquals(0, c.currentCountForTests())
    }

    @Test
    fun boundResetsAndNeverTriggers() {
        val c = UnboundDesktopEscapeCounter()
        repeat(10) {
            assertFalse(c.onTap(unbound = false))
        }
        assertEquals(0, c.currentCountForTests())
    }

    @Test
    fun gapResetsStreak() {
        var now = 1_000L
        val c = UnboundDesktopEscapeCounter(clock = { now })
        repeat(3) {
            assertFalse(c.onTap(unbound = true))
            now += 100
        }
        now += UnboundDesktopEscapeCounter.MAX_GAP_MS + 1
        assertFalse(c.onTap(unbound = true))
        assertEquals(1, c.currentCountForTests())
    }
}
