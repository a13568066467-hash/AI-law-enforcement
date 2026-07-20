package com.aifieldcam.app.platform.commandcall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue 1 — 假 TRTC 房间接缝：只断言可观察进房/退房行为，不依赖腾讯云 SDK。
 */
class FakeCommandCallRoomAdapterTest {

    private lateinit var adapter: FakeCommandCallRoomAdapter

    @Before
    fun setUp() {
        adapter = FakeCommandCallRoomAdapter()
        CommandCallRoom.resetToFake()
    }

    @Test
    fun join_with_credentials_enters_room_without_real_trtc() {
        val creds = CommandCallCredentials(
            sdkAppId = 1600152450,
            roomId = "room-1",
            userId = "device-42",
            userSig = "sig-test",
        )

        assertTrue(adapter.join(creds))

        assertTrue(adapter.isInRoom())
        assertEquals(CommandCallRoomState.IN_ROOM, adapter.state)
        assertEquals(creds, adapter.lastJoinedCredentials)
        assertEquals(1, adapter.joinCount)
    }

    @Test
    fun leave_clears_room_and_returns_to_idle() {
        adapter.join(
            CommandCallCredentials(
                sdkAppId = 1,
                roomId = "r",
                userId = "u",
                userSig = "s",
            ),
        )

        adapter.leave()

        assertFalse(adapter.isInRoom())
        assertEquals(CommandCallRoomState.IDLE, adapter.state)
        assertNull(adapter.lastJoinedCredentials)
        assertEquals(1, adapter.leaveCount)
    }

    @Test
    fun second_join_while_in_room_is_rejected() {
        val first = CommandCallCredentials(1, "r1", "u1", "s1")
        val second = CommandCallCredentials(1, "r2", "u2", "s2")
        assertTrue(adapter.join(first))

        assertFalse(adapter.join(second))

        assertEquals(first, adapter.lastJoinedCredentials)
        assertEquals(1, adapter.joinCount)
        assertEquals(CommandCallRoomState.IN_ROOM, adapter.state)
    }

    @Test
    fun command_call_room_defaults_to_fake_and_accepts_injection() {
        assertTrue(CommandCallRoom.current() is FakeCommandCallRoomAdapter)
        assertTrue(
            CommandCallRoom.current().join(
                CommandCallCredentials(1, "r", "u", "s"),
            ),
        )
        assertTrue(CommandCallRoom.current().isInRoom())

        val injected = FakeCommandCallRoomAdapter()
        CommandCallRoom.use(injected)
        assertTrue(CommandCallRoom.current() === injected)
        assertFalse(CommandCallRoom.current().isInRoom())
    }
}
