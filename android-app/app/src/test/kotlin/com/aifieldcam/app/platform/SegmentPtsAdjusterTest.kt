package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPtsAdjusterTest {

    @Test
    fun firstFrame_setsSegmentStart() {
        assertEquals(1_000_000L, SegmentPtsAdjuster.segmentStartForFirstFrame(1_000_000L, 0L))
    }

    @Test
    fun laterFrames_keepExistingStart() {
        assertEquals(1_000_000L, SegmentPtsAdjuster.segmentStartForFirstFrame(5_000_000L, 1_000_000L))
    }

    @Test
    fun adjustedPts_startsAtZeroForNewSegment() {
        assertEquals(0L, SegmentPtsAdjuster.adjustedPresentationUs(1_000_000L, 1_000_000L))
        assertEquals(500_000L, SegmentPtsAdjuster.adjustedPresentationUs(1_500_000L, 1_000_000L))
    }

    @Test
    fun adjustedPts_neverNegative() {
        assertEquals(0L, SegmentPtsAdjuster.adjustedPresentationUs(500_000L, 1_000_000L))
    }
}
