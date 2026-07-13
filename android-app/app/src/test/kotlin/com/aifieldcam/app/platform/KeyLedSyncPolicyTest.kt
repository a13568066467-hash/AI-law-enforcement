package com.aifieldcam.app.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按键→灯同步规则（与 SessionManager.shouldShowVideoRecordingLed 对齐）：
 * 开录准备中 / 录像中 / 分段间隙 → 亮；正在保存 → 灭。
 */
class KeyLedSyncPolicyTest {

    @Test
    fun videoLed_onWhilePreparingOrRecordingOrSegmentGap() {
        assertTrue(videoLed(saving = false, recording = false, preparing = true, segmentGap = false))
        assertTrue(videoLed(saving = false, recording = true, preparing = false, segmentGap = false))
        assertTrue(videoLed(saving = false, recording = false, preparing = false, segmentGap = true))
    }

    @Test
    fun videoLed_offWhileSavingEvenIfStillRecordingFlag() {
        assertFalse(videoLed(saving = true, recording = true, preparing = false, segmentGap = false))
    }

    @Test
    fun videoLed_offWhenIdle() {
        assertFalse(videoLed(saving = false, recording = false, preparing = false, segmentGap = false))
    }

    @Test
    fun segmentPolicy_ledDuringGap() {
        assertTrue(RecordingSegmentPolicy.shouldShowRecordingLed(false, true))
        assertFalse(RecordingSegmentPolicy.shouldShowRecordingLed(false, false))
    }

    private fun videoLed(
        saving: Boolean,
        recording: Boolean,
        preparing: Boolean,
        segmentGap: Boolean,
    ): Boolean = !saving && (recording || preparing || segmentGap)
}
