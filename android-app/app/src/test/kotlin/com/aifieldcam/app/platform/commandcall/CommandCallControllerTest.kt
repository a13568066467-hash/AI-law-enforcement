package com.aifieldcam.app.platform.commandcall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 呼叫/监看骨架：占用持房 + 按需推流，不依赖真 TRTC。
 */
class CommandCallControllerTest {

    @Before
    fun setUp() {
        CommandCallController.resetForTests()
    }

    @Test
    fun occupyHoldsRoomWithoutCallMode_sessionEndStaysHolding() {
        val creds = CommandCallCredentials(
            sdkAppId = 1600152450,
            roomId = "room-1",
            userId = "device-1",
            userSig = "sig",
        )

        assertTrue(CommandCallController.onOccupyRoom("room-1", creds))
        assertTrue(CommandCallController.isHolding())
        assertTrue(CommandCallRoom.current().isInRoom())
        assertFalse(CommandCallController.isInCall())
        assertFalse(CommandCallController.isWatching())

        assertTrue(CommandCallController.onWatchStart("call-1", creds))
        assertTrue(CommandCallController.isWatching())

        CommandCallController.onSessionEnd("call-1")
        assertTrue(CommandCallController.isHolding())
        assertTrue(CommandCallRoom.current().isInRoom())

        CommandCallController.onOccupyRoomEnd()
        assertFalse(CommandCallRoom.current().isInRoom())
        assertEquals(CommandCallController.Mode.IDLE, CommandCallController.currentMode())
    }

    @Test
    fun startJoinsFakeRoomAndEndLeavesBusinessButKeepsOccupyRoom() {
        val creds = CommandCallCredentials(
            sdkAppId = 1600152450,
            roomId = "room-1",
            userId = "device-1",
            userSig = "sig",
        )

        assertTrue(CommandCallController.onCallStart("call-1", creds))
        assertTrue(CommandCallController.isInCall())
        assertEquals("call-1", CommandCallController.activeCallId())
        assertTrue(CommandCallRoom.current().isInRoom())

        CommandCallController.onCallEnd("call-1")

        assertFalse(CommandCallController.isInCall())
        assertEquals("", CommandCallController.activeCallId())
        assertTrue(CommandCallRoom.current().isInRoom())
        assertTrue(CommandCallController.isHolding())
    }

    @Test
    fun secondStartWhileInCallIsRejected() {
        val first = CommandCallCredentials(1, "r1", "u1", "s1")
        val second = CommandCallCredentials(1, "r2", "u2", "s2")
        assertTrue(CommandCallController.onCallStart("c1", first))

        assertFalse(CommandCallController.onCallStart("c2", second))

        assertEquals("c1", CommandCallController.activeCallId())
        assertEquals(first, (CommandCallRoom.current() as FakeCommandCallRoomAdapter).lastJoinedCredentials)
    }

    @Test
    fun watchingDoesNotCountAsInCallForIntercomGate() {
        val creds = CommandCallCredentials(1, "r1", "u1", "s1")
        assertTrue(CommandCallController.onWatchStart("w2", creds))
        assertTrue(CommandCallController.isWatching())
        assertFalse(CommandCallController.isInCall())
        // AI realtime 门闩依赖 isInCall=false，监看期间应允许 AI
        assertTrue(
            CommandCallAiPriority.allowsAiRealtime(CommandCallController.isInCall()),
        )
    }
}
