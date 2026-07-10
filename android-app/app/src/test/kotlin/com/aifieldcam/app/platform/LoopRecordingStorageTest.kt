package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopRecordingStorageTest {

    @Test
    fun bytesNeeded_includesReserveBeyondSegment() {
        val segment = RecordingSegmentPolicy.maxSegmentBytes()
        val need = LoopRecordingStorage.bytesNeededForNextSegment(segment)
        assertTrue(need > segment)
        assertEquals(segment + LoopRecordingStorage.RESERVE_MB * 1024 * 1024, need)
    }
}
