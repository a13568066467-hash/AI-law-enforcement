package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpPacketizerTest {

    @Test
    fun stripStartCode_4byte() {
        val raw = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        val nal = RtpPacketizer.stripStartCode(raw)
        assertEquals(2, nal.size)
        assertEquals(0x67.toByte(), nal[0])
    }

    @Test
    fun nalType_sps() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00)
        assertEquals(7, RtpPacketizer.nalType(sps))
        assertTrue(RtpPacketizer.isParameterSet(sps))
        assertFalse(RtpPacketizer.isIdr(sps))
    }

    @Test
    fun packetize_small_singleRtp() {
        val nal = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val packets = RtpPacketizer.packetize(nal, sequence = 10, timestamp = 90_000, ssrc = 1)
        assertEquals(1, packets.size)
        val p = packets[0]
        assertEquals(0x80.toByte(), p[0])
        assertEquals((0x80 or 96).toByte(), p[1]) // marker + PT
        assertEquals(10.toByte(), p[3])
        assertEquals(0x65.toByte(), p[12])
    }

    @Test
    fun packetize_large_usesFuA() {
        val payload = ByteArray(RtpPacketizer.MAX_PAYLOAD + 50) { 0x11 }
        val nal = byteArrayOf(0, 0, 0, 1, 0x65) + payload
        val packets = RtpPacketizer.packetize(nal, sequence = 0, timestamp = 0, ssrc = 42)
        assertTrue(packets.size >= 2)
        // FU indicator type = 28
        assertEquals(28, packets[0][12].toInt() and 0x1F)
        // first FU header has S bit
        assertTrue(packets[0][13].toInt() and 0x80 != 0)
        // last has E bit and marker
        val last = packets.last()
        assertTrue(last[13].toInt() and 0x40 != 0)
        assertTrue(last[1].toInt() and 0x80 != 0)
    }
}
