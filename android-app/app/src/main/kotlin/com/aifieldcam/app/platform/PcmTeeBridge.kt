package com.aifieldcam.app.platform

import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 录像伴随音与 PTT 共麦：采集线程只推 PCM，PTT 附着期间旁路拷贝，不新开 AudioRecord。
 */
object PcmTeeBridge {

    class Subscription internal constructor(internal val sink: StreamingSink)

    private sealed interface Sink {
        fun offer(pcm: ByteArray, offset: Int, length: Int)
        fun close()
    }

    private class LegacySink : Sink {
        val stream = ByteArrayOutputStream(64 * 1024)

        override fun offer(pcm: ByteArray, offset: Int, length: Int) {
            synchronized(stream) {
                stream.write(pcm, offset, length)
            }
        }

        override fun close() = Unit
    }

    internal class StreamingSink(
        private val onChunk: (ByteArray) -> Unit,
    ) : Sink {
        private val running = AtomicBoolean(true)
        private val queue = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_CHUNKS)
        private val worker = Thread(::runLoop, "PcmTeeSubscriber").apply {
            isDaemon = true
            start()
        }

        override fun offer(pcm: ByteArray, offset: Int, length: Int) {
            if (!running.get()) return
            val copy = pcm.copyOfRange(offset, offset + length)
            if (!queue.offer(copy)) {
                queue.poll()
                queue.offer(copy)
            }
        }

        override fun close() {
            running.set(false)
            queue.clear()
            worker.interrupt()
        }

        private fun runLoop() {
            while (running.get()) {
                val chunk = try {
                    queue.poll(250, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue
                onChunk(chunk)
            }
        }
    }

    private val activeSink = AtomicReference<Sink?>(null)

    fun isTeeActive(): Boolean = activeSink.get() != null

    /** 兼容整段采集调用；实时语音应使用 [subscribe]。 */
    fun attach(): Boolean {
        return activeSink.compareAndSet(null, LegacySink())
    }

    /** 实时旁路订阅。回调在独立线程执行，绝不阻塞录像采集线程。 */
    fun subscribe(onChunk: (ByteArray) -> Unit): Subscription? {
        val sink = StreamingSink(onChunk)
        if (!activeSink.compareAndSet(null, sink)) {
            sink.close()
            return null
        }
        return Subscription(sink)
    }

    fun unsubscribe(subscription: Subscription) {
        if (activeSink.compareAndSet(subscription.sink, null)) {
            subscription.sink.close()
        }
    }

    /** 采集回调推送 PCM（16-bit LE）。无附着时为 no-op。 */
    fun push(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size) {
        if (length <= 0 || offset < 0 || offset + length > pcm.size) return
        activeSink.get()?.offer(pcm, offset, length)
    }

    /** PTT 结束：取走已收集 PCM 并拆除附着。 */
    fun detachAndTake(): ByteArray? {
        val sink = activeSink.get() as? LegacySink ?: return null
        if (!activeSink.compareAndSet(sink, null)) return null
        synchronized(sink.stream) {
            val bytes = sink.stream.toByteArray()
            return if (bytes.isEmpty()) null else bytes
        }
    }

    fun clear() {
        activeSink.getAndSet(null)?.close()
    }

    private const val MAX_QUEUED_CHUNKS = 32
}
