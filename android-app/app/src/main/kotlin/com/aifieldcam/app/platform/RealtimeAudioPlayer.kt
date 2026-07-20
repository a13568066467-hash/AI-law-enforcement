package com.aifieldcam.app.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class RealtimeAudioPlayer {
    private val running = AtomicBoolean(true)
    private val queue = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_CHUNKS)
    private val lock = Any()
    private var track: AudioTrack? = null
    private val writer = Thread(::writeLoop, "RealtimeAudioPlayer").apply {
        isDaemon = true
        start()
    }

    fun enqueue(pcm24k: ByteArray) {
        if (!running.get() || pcm24k.isEmpty()) return
        val copy = pcm24k.copyOf()
        if (!queue.offer(copy)) {
            queue.poll()
            queue.offer(copy)
            Log.w(TAG, "audio queue full; dropped oldest chunk")
        }
    }

    /** 立即丢弃尚未播放的模型回答，播放器可继续接收下一轮音频。 */
    fun flushAndStop() {
        queue.clear()
        synchronized(lock) {
            try {
                track?.pause()
                track?.flush()
                track?.play()
            } catch (e: Exception) {
                Log.w(TAG, "flush player failed: ${e.message}")
            }
        }
    }

    fun release() {
        if (!running.compareAndSet(true, false)) return
        queue.clear()
        writer.interrupt()
        synchronized(lock) {
            try {
                track?.stop()
            } catch (_: Exception) {
            }
            track?.release()
            track = null
        }
    }

    private fun writeLoop() {
        while (running.get()) {
            val pcm = try {
                queue.poll(250, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            try {
                val player = ensureTrack()
                player.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            } catch (e: Exception) {
                Log.e(TAG, "play realtime audio failed", e)
            }
        }
    }

    private fun ensureTrack(): AudioTrack = synchronized(lock) {
        track?.let { return@synchronized it }
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "扬声器缓冲不可用" }
        AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(minBuffer, SAMPLE_RATE * 2 / 2),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        ).also {
            it.play()
            track = it
        }
    }

    companion object {
        private const val TAG = "RealtimeAudioPlayer"
        private const val SAMPLE_RATE = 24_000
        private const val MAX_QUEUED_CHUNKS = 64
    }
}
