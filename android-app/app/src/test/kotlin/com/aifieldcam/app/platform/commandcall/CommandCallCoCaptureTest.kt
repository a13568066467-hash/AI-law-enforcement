package com.aifieldcam.app.platform.commandcall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommandCallCoCaptureTest {

    private lateinit var room: FakeCommandCallRoomAdapter
    private lateinit var source: FakeCommandCallFrameSource

    @Before
    fun setUp() {
        CommandCallCoCapture.unbind()
        CommandCallRoom.resetToFake()
        room = FakeCommandCallRoomAdapter()
        CommandCallRoom.use(room)
        source = FakeCommandCallFrameSource()
        assertTrue(
            room.join(
                CommandCallCredentials(1, "r", "u", "s"),
            ),
        )
    }

    @Test
    fun bind_scales_i420_1080p_to_540p_long_edge() {
        val ySize = 1920 * 1080
        val i420 = ByteArray(ySize + ySize / 2)
        CommandCallCoCapture.bind(room, source, IdentityCommandCallJpegScaler)
        assertTrue(CommandCallCoCapture.isActive())
        assertTrue(room.customVideoEnabled)
        assertTrue(source.isStarted())

        source.emit(
            CommandCallVideoFrame(
                width = 1920,
                height = 1080,
                i420Bytes = i420,
            ),
        )

        assertEquals(1, room.pushedFrameCount)
        val pushed = room.pushedFrames.first()
        assertEquals(960, pushed.width)
        assertEquals(540, pushed.height)
        assertTrue(pushed.hasI420)
        assertFalse(source.openedSecondCamera())
    }

    @Test
    fun unbind_stops_source_and_disables_custom_video() {
        CommandCallCoCapture.bind(room, source)
        source.emit(CommandCallVideoFrame(640, 360, i420Bytes = ByteArray(640 * 360 * 3 / 2)))
        assertEquals(1, room.pushedFrameCount)

        CommandCallCoCapture.unbind()

        assertFalse(CommandCallCoCapture.isActive())
        assertFalse(source.isStarted())
        assertFalse(room.customVideoEnabled)
        source.emit(CommandCallVideoFrame(640, 360, i420Bytes = ByteArray(8)))
        assertEquals(0, room.pushedFrameCount)
    }
}
