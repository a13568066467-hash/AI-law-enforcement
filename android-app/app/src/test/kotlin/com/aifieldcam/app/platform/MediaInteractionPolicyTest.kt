package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交互设计 §5 + 息屏侧键：录像/拍照/录音全局互斥 */
class MediaInteractionPolicyTest {

    private val idle = MediaInteractionPolicy.State()

    @Test
    fun idle_canStartVideoAndCapture() {
        assertNull(MediaInteractionPolicy.canStartVideo(idle))
        assertNull(MediaInteractionPolicy.canCapture(idle))
        assertNull(MediaInteractionPolicy.canStartAudio(idle))
    }

    @Test
    fun recording_blocksCapture() {
        val state = idle.copy(videoRecording = true)
        assertEquals(
            MediaInteractionPolicy.Block.CaptureWhileVideo,
            MediaInteractionPolicy.canCapture(state),
        )
        assertEquals(
            MediaInteractionPolicy.Block.AlreadyRecording,
            MediaInteractionPolicy.canStartVideo(state),
        )
    }

    @Test
    fun preparing_blocksCapture_butAllowsStop() {
        val state = idle.copy(videoPreparing = true)
        assertEquals(
            MediaInteractionPolicy.Block.CaptureWhilePreparing,
            MediaInteractionPolicy.canCapture(state),
        )
        assertNull(MediaInteractionPolicy.canStopVideo(state))
    }

    @Test
    fun capturing_blocksVideoStart() {
        val state = idle.copy(stillCapturing = true)
        assertEquals(
            MediaInteractionPolicy.Block.VideoWhileCapturing,
            MediaInteractionPolicy.canStartVideo(state),
        )
        assertEquals(
            MediaInteractionPolicy.Block.CaptureWhileCapturing,
            MediaInteractionPolicy.canCapture(state),
        )
    }

    @Test
    fun audio_blocksVideoAndCapture() {
        val state = idle.copy(audioRecording = true)
        assertEquals(
            MediaInteractionPolicy.Block.VideoWhileAudio,
            MediaInteractionPolicy.canStartVideo(state),
        )
        assertEquals(
            MediaInteractionPolicy.Block.CaptureWhileAudio,
            MediaInteractionPolicy.canCapture(state),
        )
    }

    @Test
    fun video_blocksAudio() {
        val state = idle.copy(videoRecording = true)
        assertEquals(
            MediaInteractionPolicy.Block.AudioWhileVideo,
            MediaInteractionPolicy.canStartAudio(state),
        )
    }

    @Test
    fun lowBattery_blocksNewVideoAndCapture() {
        val state = idle.copy(lowBatteryBlock = true)
        assertNotNull(MediaInteractionPolicy.canStartVideo(state))
        assertNotNull(MediaInteractionPolicy.canCapture(state))
        assertNull(MediaInteractionPolicy.canStopVideo(state.copy(videoRecording = true)))
    }

    @Test
    fun aiBusy_blocksNewVideo() {
        val state = idle.copy(aiBusy = true)
        assertEquals(MediaInteractionPolicy.Block.AiBusy, MediaInteractionPolicy.canStartVideo(state))
        assertNull(MediaInteractionPolicy.canCapture(state))
    }

    @Test
    fun screenOffSamePolicyAsForeground() {
        // 息屏侧键与亮屏共用 State，无单独放宽
        val recording = idle.copy(videoRecording = true)
        assertEquals(
            MediaInteractionPolicy.Block.CaptureWhileVideo,
            MediaInteractionPolicy.canCapture(recording),
        )
    }
}
