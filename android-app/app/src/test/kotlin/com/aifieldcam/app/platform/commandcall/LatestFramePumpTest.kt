package com.aifieldcam.app.platform.commandcall

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LatestFramePumpTest {

    @Test
    fun drops_intermediate_when_deliver_is_slow() {
        val delivered = mutableListOf<Int>()
        val gate = AtomicInteger(0)
        lateinit var pump: LatestFramePump<Int>
        pump = LatestFramePump { v ->
            delivered.add(v)
            // 模拟推流慢：第一次投递期间又来了新帧
            if (gate.getAndIncrement() == 0) {
                pump.offer(2)
                pump.offer(3)
            }
        }
        pump.offer(1)
        assertEquals(listOf(1, 3), delivered)
    }

    @Test
    fun clear_drops_queued_item_before_drain() {
        val delivered = mutableListOf<Int>()
        val pump = LatestFramePump<Int> { v ->
            delivered.add(v)
            Thread.sleep(1)
        }
        // clear 在无 drain 时直接丢掉 pending
        pump.clear()
        assertEquals(emptyList<Int>(), delivered)
    }
}
