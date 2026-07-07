package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingForegroundHoldTest {

    @Before
    fun setUp() {
        RecordingForegroundHold.reset()
    }

    @Test
    fun recordLifecycle_acquireOnceReleaseOnce() {
        assertEquals(1, RecordingForegroundHold.acquire())
        assertFalse(RecordingForegroundHold.shouldStopAfterRelease())
        assertEquals(0, RecordingForegroundHold.release())
        assertTrue(RecordingForegroundHold.shouldStopAfterRelease())
    }

    @Test
    fun captureDuringRecord_keepsServiceAlive() {
        RecordingForegroundHold.acquire() // 录像
        RecordingForegroundHold.acquire() // 误重入
        RecordingForegroundHold.release()
        assertFalse(RecordingForegroundHold.shouldStopAfterRelease())
        RecordingForegroundHold.release()
        assertTrue(RecordingForegroundHold.shouldStopAfterRelease())
    }

    @Test
    fun cancelPrepare_mustReleaseHold() {
        RecordingForegroundHold.acquire()
        assertEquals(0, RecordingForegroundHold.release())
        assertTrue(RecordingForegroundHold.shouldStopAfterRelease())
    }
}
