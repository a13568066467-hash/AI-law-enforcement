package com.aifieldcam.app.platform.commandcall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class YuvFrameUtilTest {

    @Test
    fun scale_i420_halves_dimensions() {
        val w = 4
        val h = 4
        val ySize = w * h
        val src = ByteArray(ySize + ySize / 2) { it.toByte() }
        val scaled = YuvFrameUtil.scaleI420(YuvFrameUtil.I420Frame(w, h, src), 2, 2)
        assertEquals(2, scaled.width)
        assertEquals(2, scaled.height)
        assertEquals(2 * 2 * 3 / 2, scaled.i420.size)
    }

    @Test
    fun i420_to_nv21_roundtrip_size() {
        val frame = YuvFrameUtil.I420Frame(4, 4, ByteArray(4 * 4 * 3 / 2))
        val nv21 = YuvFrameUtil.i420ToNv21(frame)
        assertNotNull(nv21)
        assertEquals(4 * 4 * 3 / 2, nv21!!.size)
    }
}
