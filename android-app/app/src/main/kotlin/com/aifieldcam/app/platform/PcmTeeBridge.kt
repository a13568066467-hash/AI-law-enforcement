package com.aifieldcam.app.platform

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * 录像伴随音与 PTT 共麦：采集线程只推 PCM，PTT 附着期间旁路拷贝，不新开 AudioRecord。
 */
object PcmTeeBridge {

    private val activeCollector = AtomicReference<ByteArrayOutputStream?>(null)

    fun isTeeActive(): Boolean = activeCollector.get() != null

    /** PTT 开始：附着收集器。若已有附着则失败。 */
    fun attach(): Boolean {
        val stream = ByteArrayOutputStream(64 * 1024)
        return activeCollector.compareAndSet(null, stream)
    }

    /** 采集回调推送 PCM（16-bit LE）。无附着时为 no-op。 */
    fun push(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size) {
        val stream = activeCollector.get() ?: return
        if (length <= 0) return
        synchronized(stream) {
            stream.write(pcm, offset, length)
        }
    }

    /** PTT 结束：取走已收集 PCM 并拆除附着。 */
    fun detachAndTake(): ByteArray? {
        val stream = activeCollector.getAndSet(null) ?: return null
        synchronized(stream) {
            val bytes = stream.toByteArray()
            return if (bytes.isEmpty()) null else bytes
        }
    }

    fun clear() {
        activeCollector.set(null)
    }
}
