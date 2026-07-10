package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSegmentPolicyTest {

    @Test
    fun maxSegmentBytes_isOneGib() {
        assertEquals(1024L * 1024 * 1024, RecordingSegmentPolicy.maxSegmentBytes())
    }

    @Test
    fun maxSegmentBytes_isBelowFat32FourGbLimit() {
        val fourGb = 4L * 1024 * 1024 * 1024
        assertTrue(RecordingSegmentPolicy.maxSegmentBytes() < fourGb)
    }

    @Test
    fun rolloverReason_containsSegmentMarker() {
        val reason = RecordingSegmentPolicy.rolloverReason()
        assertTrue(RecordingSegmentPolicy.isSegmentRollover(reason))
        assertTrue(reason.contains("分段保存"))
    }

    @Test
    fun isSegmentRollover_distinguishesOtherInterrupts() {
        assertFalse(RecordingSegmentPolicy.isSegmentRollover("存储空间不足，录像已自动保存"))
        assertFalse(RecordingSegmentPolicy.isSegmentRollover("录像编码致命错误，已停止"))
        assertFalse(RecordingSegmentPolicy.isSegmentRollover("录像已中断（相机断开），已自动保存"))
    }

    @Test
    fun shouldShowRecordingLed_trueDuringSegmentGap() {
        assertTrue(RecordingSegmentPolicy.shouldShowRecordingLed(isRecording = false, segmentRolloverActive = true))
        assertTrue(RecordingSegmentPolicy.shouldShowRecordingLed(isRecording = true, segmentRolloverActive = false))
        assertFalse(RecordingSegmentPolicy.shouldShowRecordingLed(isRecording = false, segmentRolloverActive = false))
    }

    @Test
    fun isSeamlessRotateEnabled_byDefault() {
        assertTrue(RecordingSegmentPolicy.isSeamlessRotateEnabled())
    }

    @Test
    fun continueDelayMs_allowsCameraRelease() {
        assertTrue(RecordingSegmentPolicy.continueDelayMs() >= 1_000L)
    }
}
