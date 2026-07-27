package com.aifieldcam.app.platform

import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeFrameCompressorTest {
    @Test
    fun emptyReturnsNull() {
        assertNull(RealtimeFrameCompressor.compressForRealtime(ByteArray(0)))
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(RealtimeFrameCompressor.compressForRealtime(byteArrayOf(1, 2, 3, 4)))
    }
}
