package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusLedPriorityTest {

    @Test
    fun commandCall_redSteady_beats_videoBlink() {
        assertEquals(
            StatusLedPattern.COMMAND_CALL_RED_STEADY,
            StatusLedPriority.resolve(
                commandCallPtt = false,
                aiListening = false,
                commandCallActive = true,
                videoStreaming = false,
                videoRecording = true,
                audioRecording = false,
                charging = false,
                fullCharge = false,
            ),
        )
    }

    @Test
    fun commandCallPtt_yellow_beats_commandCallRed() {
        assertEquals(
            StatusLedPattern.COMMAND_CALL_PTT_YELLOW_STEADY,
            StatusLedPriority.resolve(
                commandCallPtt = true,
                aiListening = false,
                commandCallActive = true,
                videoStreaming = false,
                videoRecording = true,
                audioRecording = false,
                charging = false,
                fullCharge = false,
            ),
        )
    }

    @Test
    fun aiListening_yellow_when_not_in_command_call() {
        assertEquals(
            StatusLedPattern.AI_LISTEN_YELLOW_STEADY,
            StatusLedPriority.resolve(
                commandCallPtt = false,
                aiListening = true,
                commandCallActive = false,
                videoStreaming = false,
                videoRecording = false,
                audioRecording = false,
                charging = false,
                fullCharge = false,
            ),
        )
    }

    @Test
    fun afterCall_standby_allows_normal_modes() {
        assertEquals(
            StatusLedPattern.STANDBY_GREEN_STEADY,
            StatusLedPriority.resolve(
                commandCallPtt = false,
                aiListening = false,
                commandCallActive = false,
                videoStreaming = false,
                videoRecording = false,
                audioRecording = false,
                charging = false,
                fullCharge = false,
            ),
        )
        assertEquals(
            StatusLedPattern.VIDEO_RED_BLINK,
            StatusLedPriority.resolve(
                commandCallPtt = false,
                aiListening = false,
                commandCallActive = false,
                videoStreaming = false,
                videoRecording = true,
                audioRecording = false,
                charging = false,
                fullCharge = false,
            ),
        )
    }
}
