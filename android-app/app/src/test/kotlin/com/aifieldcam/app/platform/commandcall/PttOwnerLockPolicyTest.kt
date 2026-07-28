package com.aifieldcam.app.platform.commandcall

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * DOWN 锁定归属：中途 isEncodedStreaming 翻转时 UP 仍应按 DOWN 时的 owner 收尾。
 */
class PttOwnerLockPolicyTest {

    @Before
    fun reset() {
        CommandCallAiSuppressLatch.resetForTests()
    }

    @Test
    fun locked_ai_owner_stays_ai_even_if_stream_starts() {
        val locked = CommandCallIntercomPolicy.pttOwner(
            commandCallActive = false,
            videoStreaming = false,
        )
        assertEquals(CommandCallPttOwner.AI_OR_LIGHT, locked)
        val recomputed = CommandCallIntercomPolicy.pttOwner(
            commandCallActive = false,
            videoStreaming = true,
        )
        assertEquals(CommandCallPttOwner.VIDEO_STREAM, recomputed)
        assertEquals(CommandCallPttOwner.AI_OR_LIGHT, locked)
    }

    @Test
    fun latch_makes_down_owner_command_call_before_in_room() {
        CommandCallAiSuppressLatch.arm()
        val owner = CommandCallIntercomPolicy.pttOwner(
            commandCallActive = CommandCallAiSuppressLatch.blocksAiRealtime(inCall = false),
            videoStreaming = false,
        )
        assertEquals(CommandCallPttOwner.COMMAND_CALL, owner)
    }
}
