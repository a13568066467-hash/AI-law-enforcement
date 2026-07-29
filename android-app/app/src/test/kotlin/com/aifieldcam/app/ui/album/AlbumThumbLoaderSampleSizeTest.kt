package com.aifieldcam.app.ui.album

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumThumbLoaderSampleSizeTest {

    @Test
    fun downsamplesLargePhotoTowardThumbTarget() {
        // 4000x3000 → need sample ≥ 8 to land near 160px
        val size = AlbumThumbLoader.sampleSize(4000, 3000, 160, 160)
        assertEquals(16, size)
    }

    @Test
    fun leavesSmallPhotoUnsampled() {
        assertEquals(1, AlbumThumbLoader.sampleSize(120, 120, 160, 160))
    }
}
