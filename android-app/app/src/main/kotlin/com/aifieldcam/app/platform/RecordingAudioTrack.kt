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

/**
 * 循环录像伴随音：AudioRecord → AAC，全程与视频共会话。
 * 热换 muxer 时采集/编码不停；PCM 同时旁路给 [PcmTeeBridge]（PTT 共麦）。
 */
internal class RecordingAudioTrack(
    private val onEncodedSample: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
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
        val bufSize = maxOf(minBuf, SAMPLE_RATE * bytesPerSample)
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
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
        }
        val aac = MediaCodec.createEncoderByType(MIME)
        aac.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aac.start()
        codec = aac
        audioRecord = recorder
        presentationUs = 0L
        formatNotified = false
        loopDone = CountDownLatch(1)

        captureThread = HandlerThread("RecAudioCapture").apply { start() }
        recorder.startRecording()
        Handler(captureThread!!.looper).post {
            try {
                captureLoop(bufSize)
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
                feedEncoder(aac, pcm, read, eos = false)
            }
            drainEncoder(aac, outInfo, writeSamples = true)
        }
        feedEncoder(aac, ByteArray(0), 0, eos = true)
        var spins = 0
        while (spins++ < 80) {
            if (!drainEncoder(aac, outInfo, writeSamples = true)) break
        }
    }

    private fun feedEncoder(aac: MediaCodec, pcm: ByteArray, length: Int, eos: Boolean) {
        try {
            val index = aac.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return
            val input = aac.getInputBuffer(index) ?: return
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
        } catch (e: Exception) {
            Log.w(TAG, "feedEncoder: ${e.message}")
        }
    }

    private fun drainEncoder(
        aac: MediaCodec,
        outInfo: MediaCodec.BufferInfo,
        writeSamples: Boolean,
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
                    if (outInfo.size > 0 && writeSamples) {
                        val buffer = aac.getOutputBuffer(status)
                        if (buffer != null) {
                            onEncodedSample(buffer, outInfo)
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
