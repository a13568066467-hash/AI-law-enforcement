package com.aifieldcam.app.platform.commandcall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue 3 — 控制器绑定共摄：进房旁路约 720p，退房清理；不二次开相机。
 */
class CommandCallControllerCoCaptureTest {

    private lateinit var source: FakeCommandCallFrameSource

    @Before
    fun setUp() {
        CommandCallController.resetForTests()
        source = FakeCommandCallFrameSource()
        CommandCallController.useFrameSourceFactoryForTests { source }
        CommandCallController.useJpegScalerForTests(IdentityCommandCallJpegScaler)
    }

    @Test
    fun start_binds_co_capture_end_unbinds_without_second_camera() {
        val creds = CommandCallCredentials(1600152450, "room-1", "device-1", "sig")
        assertTrue(CommandCallController.onCallStart("call-1", creds))
        assertTrue(CommandCallController.isInCall())
        assertTrue(CommandCallController.isCoCaptureActive())
        assertTrue(source.isStarted())

        source.emit(CommandCallVideoFrame(1920, 1080, byteArrayOf(4, 5, 6)))
        val fake = CommandCallRoom.current() as FakeCommandCallRoomAdapter
        assertEquals(1, fake.pushedFrameCount)
        assertEquals(1280, fake.pushedFrames.first().width)
        assertEquals(720, fake.pushedFrames.first().height)
        assertFalse(source.openedSecondCamera())

        CommandCallController.onCallEnd("call-1")
        assertFalse(CommandCallController.isInCall())
        assertFalse(CommandCallController.isCoCaptureActive())
        assertFalse(source.isStarted())
    }
}
