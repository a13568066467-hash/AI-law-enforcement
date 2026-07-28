package com.aifieldcam.app.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：Realtime 断联后按住 PTT/「按住说话」无反应。
 * 根因候选：断联降级 CONNECTING 时未停麦 → beginCapture 静默 return。
 */
class PttCaptureLifecycleTest {

    @Test
    fun release_while_connecting_must_stop_capture() {
        val action = PttCaptureLifecycle.onRelease(RealtimeVoicePhase.CONNECTING)
        assertTrue(
            "CONNECTING 松手必须停麦（可能从 LISTENING 断联降级，麦仍在采）",
            action.stopCapture,
        )
        assertFalse(action.tryCommit)
    }

    @Test
    fun disconnect_while_listening_and_pressed_must_stop_capture() {
        assertTrue(
            PttCaptureLifecycle.mustStopCaptureOnDisconnect(
                pressed = true,
                phase = RealtimeVoicePhase.LISTENING,
            ),
        )
    }

    @Test
    fun beginCapture_when_already_capturing_cannot_start_silently() {
        assertFalse(
            PttCaptureLifecycle.canBeginCapture(pressed = true, alreadyCapturing = true),
        )
        assertTrue(
            PttCaptureLifecycle.shouldRestartCapture(pressed = true, alreadyCapturing = true),
        )
    }

    @Test
    fun release_while_listening_stops_and_commits() {
        val action = PttCaptureLifecycle.onRelease(RealtimeVoicePhase.LISTENING)
        assertTrue(action.stopCapture)
        assertTrue(action.tryCommit)
    }
}
