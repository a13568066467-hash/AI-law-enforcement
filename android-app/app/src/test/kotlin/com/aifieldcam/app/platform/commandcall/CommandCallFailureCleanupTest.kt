package com.aifieldcam.app.platform.commandcall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue 6 — 进房失败半连接清理：对讲/共摄/房间态全部回到空闲。
 */
class CommandCallFailureCleanupTest {

    private lateinit var room: FakeCommandCallRoomAdapter
    private lateinit var source: FakeCommandCallFrameSource

    @Before
    fun setUp() {
        CommandCallController.resetForTests()
        room = FakeCommandCallRoomAdapter()
        CommandCallRoom.use(room)
        source = FakeCommandCallFrameSource()
        CommandCallController.useFrameSourceFactoryForTests { source }
        CommandCallController.useJpegScalerForTests(IdentityCommandCallJpegScaler)
    }

    @Test
    fun joinFailed_cleans_half_connection_and_records_reason() {
        room.failNextJoin = true
        val creds = CommandCallCredentials(1, "r", "u", "s")

        assertFalse(CommandCallController.onCallStart("call-fail", creds))

        assertFalse(CommandCallController.isInCall())
        assertEquals("", CommandCallController.activeCallId())
        assertEquals("join_failed", CommandCallController.lastFailureReason())
        assertEquals(CommandCallRoomState.IDLE, room.state)
        assertEquals(1, room.leaveCount)
        assertFalse(CommandCallController.isCoCaptureActive())
        assertFalse(source.isStarted())
        assertFalse(CommandCallIntercom.isTalking())
    }

    @Test
    fun successfulStart_then_end_clears_without_failure_reason() {
        val creds = CommandCallCredentials(1, "r", "u", "s")
        assertTrue(CommandCallController.onCallStart("call-ok", creds))
        assertTrue(CommandCallController.isInCall())

        CommandCallController.onCallEnd("call-ok")

        assertFalse(CommandCallController.isInCall())
        assertEquals("", CommandCallController.lastFailureReason())
        assertEquals(CommandCallRoomState.IDLE, room.state)
    }
}
