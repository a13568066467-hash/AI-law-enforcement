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
    fun preview_only_must_not_be_passed_as_video_streaming() {
        // RecorderKeyDispatcher 应传 isEncodedStreaming()，而非含 HTTP 预览的 isStreaming()。
        // 录像+监看 JPEG 时 encoded=false → AI；真 GB28181/WebRTC 时 encoded=true → VIDEO_STREAM。
        assertEquals(
            CommandCallPttOwner.AI_OR_LIGHT,
            CommandCallIntercomPolicy.pttOwner(commandCallActive = false, videoStreaming = false),
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
