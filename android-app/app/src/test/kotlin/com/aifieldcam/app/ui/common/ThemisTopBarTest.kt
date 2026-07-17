package com.aifieldcam.app.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemisTopBarTest {

    @Test
    fun recordingStateIsVisible() {
        assertTrue(ThemisTopBar.shouldShowRecording(true, false, false))
        assertTrue(ThemisTopBar.shouldShowRecording(false, true, false))
    }

    @Test
    fun idleAndSavingStatesAreHidden() {
        assertFalse(ThemisTopBar.shouldShowRecording(false, false, false))
        assertFalse(ThemisTopBar.shouldShowRecording(false, true, true))
    }
}
