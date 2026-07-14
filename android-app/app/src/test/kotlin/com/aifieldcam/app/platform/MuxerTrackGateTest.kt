package com.aifieldcam.app.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuxerTrackGateTest {

    @Test
    fun muxer_starts_only_when_video_and_audio_ready() {
        assertFalse(MuxerTrackGate.canStart(videoReady = false, audioReady = false))
        assertFalse(MuxerTrackGate.canStart(videoReady = true, audioReady = false))
        assertFalse(MuxerTrackGate.canStart(videoReady = false, audioReady = true))
        assertTrue(MuxerTrackGate.canStart(videoReady = true, audioReady = true))
    }
}
