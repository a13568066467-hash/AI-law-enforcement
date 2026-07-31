package com.aifieldcam.app.platform.commandcall

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandCallVideoScaleTest {

    @Test
    fun keeps_1080p_when_max_long_side_is_1080() {
        assertEquals(
            1920 to 1080,
            CommandCallVideoScale.targetSize(1920, 1080, COMMAND_CALL_VIDEO_MAX_LONG_SIDE),
        )
    }

    @Test
    fun already_small_unchanged() {
        assertEquals(640 to 360, CommandCallVideoScale.targetSize(640, 360, 1280))
    }

    @Test
    fun portrait_scales_by_long_edge() {
        assertEquals(720 to 1280, CommandCallVideoScale.targetSize(1080, 1920, 1280))
    }
}
