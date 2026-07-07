package com.aifieldcam.app.platform

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPipelineWatchdogTest {

    @Test
    fun stallThreshold_allowsSlowDiskFlushOnScreenOff() {
        assertTrue(RecordingPipelineWatchdog.stallThresholdMs() >= 20_000L)
        assertTrue(RecordingPipelineWatchdog.startGraceMs() >= 10_000L)
    }

    @Test
    fun storageStopThreshold_isExactly1Gb() {
        assertEquals(1_024L, RecordingPipelineWatchdog.storageStopMb())
    }

    @Test
    fun storageWarnThreshold_is2Gb() {
        assertEquals(2_048L, RecordingPipelineWatchdog.storageWarnMb())
    }
}