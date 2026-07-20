package com.aifieldcam.app.platform.commandcall

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandCallIntercomPolicyTest {

    @Test
    fun command_call_wins_over_video_stream_and_ai() {
        assertEquals(
            CommandCallPttOwner.COMMAND_CALL,
            CommandCallIntercomPolicy.pttOwner(commandCallActive = true, videoStreaming = true),
        )
        assertEquals(
            CommandCallPttOwner.COMMAND_CALL,
            CommandCallIntercomPolicy.pttOwner(commandCallActive = true, videoStreaming = false),
        )
    }

    @Test
    fun video_stream_when_not_in_command_call() {
        assertEquals(
            CommandCallPttOwner.VIDEO_STREAM,
            CommandCallIntercomPolicy.pttOwner(commandCallActive = false, videoStreaming = true),
        )
    }

    @Test
    fun default_is_ai_or_light() {
        assertEquals(
            CommandCallPttOwner.AI_OR_LIGHT,
            CommandCallIntercomPolicy.pttOwner(commandCallActive = false, videoStreaming = false),
        )
    }
}
