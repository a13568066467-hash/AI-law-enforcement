package com.aifieldcam.app.platform

import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPipelineWatchdogTest {

    @Test
    fun stallThreshold_allowsSlowDiskFlushOnScreenOff() {
        // 与实现常量对齐：息屏 + FGS 下缓冲写盘
        assertTrue(RecordingPipelineWatchdog.stallThresholdMs() >= 20_000L)
        assertTrue(RecordingPipelineWatchdog.startGraceMs() >= 10_000L)
    }
}
