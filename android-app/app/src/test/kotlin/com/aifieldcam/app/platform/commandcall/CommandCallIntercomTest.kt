package com.aifieldcam.app.platform.commandcall

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommandCallIntercomTest {

    private lateinit var room: FakeCommandCallRoomAdapter
    private lateinit var capture: FakeCommandCallAudioCapture

    @Before
    fun setUp() {
        CommandCallController.resetForTests()
        room = FakeCommandCallRoomAdapter()
        CommandCallRoom.use(room)
        capture = FakeCommandCallAudioCapture()
        CommandCallIntercom.resetForTests(capture)
        assertTrue(
            CommandCallController.onCallStart(
                "cc-i4",
                CommandCallCredentials(1, "r", "u", "s"),
            ),
        )
        assertTrue(room.isLocalAudioMuted())
    }

    @Test
    fun long_press_uplink_unmutes_and_pushes_pcm() {
        CommandCallIntercom.startUplink()

        assertTrue(CommandCallIntercom.isTalking())
        assertFalse(room.isLocalAudioMuted())
        assertTrue(capture.started)
        capture.emit(byteArrayOf(1, 2, 3, 4))
        assertEquals(1, room.pushedPcmCount)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), room.pushedPcmChunks.first())
    }

    @Test
    fun release_stops_uplink_and_mutes() {
        CommandCallIntercom.startUplink()
        capture.emit(byteArrayOf(9))
        CommandCallIntercom.stopUplink()

        assertFalse(CommandCallIntercom.isTalking())
        assertTrue(room.isLocalAudioMuted())
        assertTrue(capture.stopped)
        capture.emit(byteArrayOf(8, 8))
        assertEquals(1, room.pushedPcmCount)
    }

    @Test
    fun call_end_force_stops_talking() {
        CommandCallIntercom.startUplink()
        CommandCallController.onCallEnd("cc-i4")

        assertFalse(CommandCallIntercom.isTalking())
        assertFalse(CommandCallController.isInCall())
        assertTrue(capture.stopped)
    }

    @Test
    fun muted_by_default_rejects_pcm_until_uplink() {
        room.pushAudioPcm(byteArrayOf(7))
        assertEquals(0, room.pushedPcmCount)
    }
}

/** 可注入假采音：不碰 AudioRecord / PcmTee。 */
class FakeCommandCallAudioCapture : CommandCallAudioCapture {
    @Volatile
    var started = false
        private set

    @Volatile
    var stopped = false
        private set

    private var onPcm: ((ByteArray) -> Unit)? = null

    override fun start(
        onPcm: (ByteArray) -> Unit,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        this.onPcm = onPcm
        started = true
        stopped = false
        onStarted()
    }

    override fun stop(onStopped: () -> Unit) {
        stopped = true
        onPcm = null
        onStopped()
    }

    fun emit(pcm: ByteArray) {
        onPcm?.invoke(pcm)
    }
}
