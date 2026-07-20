package com.aifieldcam.app.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PcmTeeBridgeTest {

    @Before
    fun reset() {
        PcmTeeBridge.clear()
    }

    @Test
    fun attach_then_push_collects_pcm_for_ptt_without_second_mic() {
        assertTrue(PcmTeeBridge.attach())
        assertTrue(PcmTeeBridge.isTeeActive())
        PcmTeeBridge.push(byteArrayOf(1, 2, 3, 4))
        PcmTeeBridge.push(byteArrayOf(5, 6))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), PcmTeeBridge.detachAndTake())
        assertFalse(PcmTeeBridge.isTeeActive())
        assertNull(PcmTeeBridge.detachAndTake())
    }

    @Test
    fun double_attach_fails() {
        assertTrue(PcmTeeBridge.attach())
        assertFalse(PcmTeeBridge.attach())
        PcmTeeBridge.clear()
    }

    @Test
    fun push_without_attach_is_noop() {
        PcmTeeBridge.push(byteArrayOf(9, 9))
        assertNull(PcmTeeBridge.detachAndTake())
    }

    @Test
    fun streaming_subscriber_receives_chunks_without_blocking_producer() {
        val received = mutableListOf<Byte>()
        val latch = CountDownLatch(1)
        val subscription = PcmTeeBridge.subscribe { pcm ->
            synchronized(received) { received.addAll(pcm.toList()) }
            latch.countDown()
        }

        assertTrue(subscription != null)
        PcmTeeBridge.push(byteArrayOf(7, 8, 9))
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        PcmTeeBridge.unsubscribe(subscription!!)

        assertArrayEquals(
            byteArrayOf(7, 8, 9),
            synchronized(received) { received.toByteArray() },
        )
        assertFalse(PcmTeeBridge.isTeeActive())
    }
}
