package com.aifieldcam.app.platform.commandcall

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 只保留最新一帧再投递：推送跟不上时丢弃中间帧，避免队列堆高延迟。
 * 纯逻辑，便于单测。
 */
internal class LatestFramePump<T>(
    private val deliver: (T) -> Unit,
) {
    private val pending = AtomicReference<T?>(null)
    private val draining = AtomicBoolean(false)

    fun offer(item: T) {
        pending.set(item)
        drain()
    }

    fun clear() {
        pending.set(null)
    }

    private fun drain() {
        if (!draining.compareAndSet(false, true)) return
        try {
            while (true) {
                val item = pending.getAndSet(null) ?: break
                deliver(item)
            }
        } finally {
            draining.set(false)
            if (pending.get() != null) {
                drain()
            }
        }
    }
}
