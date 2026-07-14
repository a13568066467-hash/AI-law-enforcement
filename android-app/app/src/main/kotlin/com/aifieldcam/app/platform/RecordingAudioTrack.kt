package com.aifieldcam.app.platform

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 循环录像伴随音：AudioRecord → AAC，全程与视频共会话。
 * 热换 muxer 时采集/编码不停；PCM 同时旁路给 [PcmTeeBridge]（PTT 共麦）。
 *
 * 注意：不得因编码/写 muxer 短暂繁忙而丢弃 PCM，否则音轨短于视频，听感变成「开头有声后面哑」。
 */
internal class RecordingAudioTrack(
    private val onEncodedSample: (ByteArray, Long, Int) -> Unit,
    private val onFormatReady: (MediaFormat) -> Unit,
    private val onFatalError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "RecAudioTrack"
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL_COUNT = 1
        private const val AAC_BITRATE = 64_000
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val TIMEOUT_US = 10_000L
        /** 约 20ms PCM 一块，降低单次阻塞与丢包窗口 */
        private const val READ_FRAMES = 320
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    private var captureThread: HandlerThread? = null
    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var loopDone: CountDownLatch? = null

    @Volatile
    var cachedFormat: MediaFormat? = null
        private set

    private var presentationUs: Long = 0L
    private val bytesPerSample = 2
    private var formatNotified = false
    private val pcmBytesFed = AtomicLong(0)
    private val aacBytesOut = AtomicLong(0)

    fun start() {
        if (!running.compareAndSet(false, true)) {
            throw IllegalStateException("伴随音已在运行")
        }
        try {
            startInternal()
        } catch (e: Exception) {
            stopQuietly()
            throw e
        }
    }

    private fun startInternal() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) throw IllegalStateException("麦克风缓冲不可用")
        val readBytes = READ_FRAMES * bytesPerSample
        val bufSize = maxOf(minBuf, readBytes * 4)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("无法初始化麦克风")
        }

        val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, readBytes * 2)
        }
        val aac = MediaCodec.createEncoderByType(MIME)
        aac.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aac.start()
        codec = aac
        audioRecord = recorder
        presentationUs = 0L
        formatNotified = false
        pcmBytesFed.set(0)
        aacBytesOut.set(0)
        loopDone = CountDownLatch(1)

        captureThread = HandlerThread("RecAudioCapture").apply { start() }
        recorder.startRecording()
        Handler(captureThread!!.looper).post {
            try {
                captureLoop(readBytes)
            } finally {
                loopDone?.countDown()
            }
        }
        Log.i(TAG, "accompanying audio started ${SAMPLE_RATE}Hz AAC ${AAC_BITRATE / 1000}kbps")
    }

    private fun captureLoop(readSize: Int) {
        val pcm = ByteArray(readSize)
        val aac = codec ?: return
        val recorder = audioRecord ?: return
        val outInfo = MediaCodec.BufferInfo()
        while (running.get()) {
            val read = try {
                recorder.read(pcm, 0, pcm.size)
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord read failed: ${e.message}")
                notifyFatal("麦克风读取失败: ${e.message}")
                break
            }
            if (read > 0) {
                PcmTeeBridge.push(pcm, 0, read)
                if (!feedEncoderBlocking(aac, pcm, read, eos = false)) {
                    Log.w(TAG, "feedEncoder failed, dropping ${read}B (risk: short audio)")
                } else {
                    pcmBytesFed.addAndGet(read.toLong())
                }
            }
            drainEncoderFully(aac, outInfo)
        }
        feedEncoderBlocking(aac, ByteArray(0), 0, eos = true)
        drainEncoderFully(aac, outInfo)
        val fedMs = pcmBytesFed.get() * 1000L / (SAMPLE_RATE * bytesPerSample)
        Log.i(TAG, "audio capture end: pcmFed=${pcmBytesFed.get()}B (~${fedMs}ms) aacOut=${aacBytesOut.get()}B")
    }

    /** 阻塞直到送入编码器（或停录），避免超时丢 PCM 导致音轨短于视频。 */
    private fun feedEncoderBlocking(
        aac: MediaCodec,
        pcm: ByteArray,
        length: Int,
        eos: Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            try {
                val index = aac.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val input = aac.getInputBuffer(index) ?: return false
                    input.clear()
                    if (length > 0) {
                        input.put(pcm, 0, length)
                    }
                    val pts = presentationUs
                    if (length > 0) {
                        val samples = length / bytesPerSample
                        presentationUs += samples * 1_000_000L / SAMPLE_RATE
                    }
                    val flags = if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    aac.queueInputBuffer(index, 0, length, pts, flags)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "feedEncoder: ${e.message}")
                return false
            }
            if (!running.get() && !eos) return false
            if (System.nanoTime() > deadline) return false
            // 喂不进去时先往外排，腾出输入缓冲
            drainEncoderFully(aac, MediaCodec.BufferInfo())
        }
    }

    private fun drainEncoderFully(aac: MediaCodec, outInfo: MediaCodec.BufferInfo) {
        var spins = 0
        while (spins++ < 32) {
            if (!drainEncoderOnce(aac, outInfo)) break
        }
    }

    /** @return true 若仍可能有更多输出 */
    private fun drainEncoderOnce(
        aac: MediaCodec,
        outInfo: MediaCodec.BufferInfo,
    ): Boolean {
        try {
            when (val status = aac.dequeueOutputBuffer(outInfo, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = aac.outputFormat
                    cachedFormat = fmt
                    if (!formatNotified) {
                        formatNotified = true
                        onFormatReady(fmt)
                    }
                    return true
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> return true
                else -> {
                    if (status < 0) return false
                    if (outInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        aac.releaseOutputBuffer(status, false)
                        return true
                    }
                    if (outInfo.size > 0) {
                        val buffer = aac.getOutputBuffer(status)
                        if (buffer != null) {
                            val copy = ByteArray(outInfo.size)
                            val pos = buffer.position()
                            buffer.position(outInfo.offset)
                            buffer.get(copy)
                            buffer.position(pos)
                            aacBytesOut.addAndGet(copy.size.toLong())
                            onEncodedSample(copy, outInfo.presentationTimeUs, outInfo.flags)
                        }
                    }
                    val eos = outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    aac.releaseOutputBuffer(status, false)
                    return !eos
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "drainEncoder: ${e.message}")
            notifyFatal("伴随音编码错误: ${e.message}")
            return false
        }
    }

    fun stop() {
        stopQuietly()
    }

    private fun stopQuietly() {
        running.set(false)
        try {
            loopDone?.await(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        captureThread?.quitSafely()
        captureThread = null
        loopDone = null
        cachedFormat = null
        presentationUs = 0L
        formatNotified = false
        PcmTeeBridge.clear()
        Log.i(TAG, "accompanying audio stopped")
    }

    private fun notifyFatal(msg: String) {
        if (!running.compareAndSet(true, false)) return
        mainHandler.post { onFatalError(msg) }
    }
}
