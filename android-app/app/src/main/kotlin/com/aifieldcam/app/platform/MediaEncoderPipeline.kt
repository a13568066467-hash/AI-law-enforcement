package com.aifieldcam.app.platform

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * H.264 + AAC 编码管线 — 替换黑盒 MediaRecorder：
 *   - [MediaMuxer] → 有声 MP4（本机循环录像）
 *   - [NAL queue]   → 推流通道（GB28181 / WebRTC）
 *   - [PcmTeeBridge] → 录像中 PTT 旁路共麦
 *
 * 参数：
 *   - 视频: H.264 1080p / 30fps / 8Mbps
 *   - 伴随音: AAC 16kHz mono（开录失败则整次失败）
 */
object MediaEncoderPipeline {

    private const val TAG = "H264Pipeline"
    private const val MIME_VIDEO = "video/avc"
    private const val I_FRAME_INTERVAL_SEC = 1
    private const val MAX_NAL_QUEUE_FRAMES = 60
    private const val MAX_PENDING_SAMPLES = 200

    private val mainHandler = Handler(Looper.getMainLooper())

    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    @Volatile
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var muxerVideoTrack = -1
    private var muxerAudioTrack = -1
    private var outputFile: File? = null
    private var muxerStarted = false
    private var cachedVideoFormat: MediaFormat? = null
    private var cachedAudioFormat: MediaFormat? = null
    private var segmentStartPtsUs: Long = 0L
    private var segmentAudioStartPtsUs: Long = 0L
    private val segmentBytesWritten = AtomicLong(0)
    private val rotating = AtomicBoolean(false)
    private val muxerLock = Any()
    private val pendingSamples = ConcurrentLinkedQueue<PendingSample>()

    private var audioTrack: RecordingAudioTrack? = null

    private val nalRelayEnabled = AtomicBoolean(false)
    private val encoding = AtomicBoolean(false)

    @Volatile
    var onSegmentLimitReached: (() -> Unit)? = null

    @Volatile
    var nalQueue: ConcurrentLinkedQueue<ByteArray> = ConcurrentLinkedQueue()
        private set

    @Volatile
    var encodedFrameCount: Long = 0
        private set

    @Volatile
    var encodedByteCount: AtomicLong = AtomicLong(0)
        private set

    private val nalConsumers = ConcurrentHashMap<String, (ByteArray) -> Unit>()

    @Volatile
    var lastNalProducedMs: Long = 0
        private set

    @Volatile
    var onEncoderError: ((String) -> Unit)? = null

    private data class PendingSample(
        val isAudio: Boolean,
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    fun isEncoding(): Boolean = encoding.get()

    /** 是否可向 PTT 提供共麦 PCM（录像伴随音运行中）。 */
    fun canProvidePcmTee(): Boolean = encoding.get() && audioTrack != null

    fun start(
        outputFile: File,
        width: Int = DeviceProfile.VIDEO_WIDTH,
        height: Int = DeviceProfile.VIDEO_HEIGHT,
        fps: Int = DeviceProfile.VIDEO_FPS,
        bitrate: Int = 8_000_000,
    ): Surface {
        if (encoding.get()) {
            throw IllegalStateException("编码器已在运行")
        }
        encoderThread = HandlerThread("H264Encoder").apply { start() }
        encoderHandler = Handler(encoderThread!!.looper)

        this.outputFile = outputFile
        this.nalQueue = ConcurrentLinkedQueue()
        this.encodedFrameCount = 0L
        this.encodedByteCount = AtomicLong(0)
        this.lastNalProducedMs = 0L
        this.cachedVideoFormat = null
        this.cachedAudioFormat = null
        this.segmentStartPtsUs = 0L
        this.segmentAudioStartPtsUs = 0L
        this.segmentBytesWritten.set(0)
        this.pendingSamples.clear()
        this.muxerVideoTrack = -1
        this.muxerAudioTrack = -1
        this.muxerStarted = false

        mediaMuxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )

        try {
            startAccompanyingAudio()
        } catch (e: Exception) {
            releaseMuxerUnlocked()
            cleanup()
            throw IllegalStateException(e.message ?: "无法启动录像伴随音", e)
        }

        val inputSurface = try {
            configureAndStartCodec(width, height, fps, bitrate)
        } catch (e: Exception) {
            audioTrack?.stop()
            audioTrack = null
            releaseMuxerUnlocked()
            cleanup()
            throw e
        }

        encoding.set(true)
        Log.i(
            TAG,
            "pipeline started → ${outputFile.name} " +
                "(${width}x${height}@${fps}fps ${bitrate / 1_000}kbps + AAC)",
        )
        return inputSurface
    }

    private fun startAccompanyingAudio() {
        val track = RecordingAudioTrack(
            onEncodedSample = { buffer, info -> writeAudioSample(buffer, info) },
            onFormatReady = { format ->
                synchronized(muxerLock) {
                    cachedAudioFormat = format
                    tryStartMuxerLocked()
                }
            },
            onFatalError = { err ->
                mainHandler.post { onEncoderError?.invoke(err) }
            },
        )
        track.start()
        audioTrack = track
    }

    fun stop() {
        if (!encoding.compareAndSet(true, false)) {
            Log.w(TAG, "stop: not encoding")
            return
        }
        Log.i(TAG, "stopping pipeline, encoded ${encodedFrameCount} frames, ${encodedByteCount.get() / 1024}KB")

        // 先停伴随音并冲刷 AAC，再 video EOS
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "audio stop: ${e.message}")
        }
        audioTrack = null

        val latch = CountDownLatch(1)
        encoderHandler?.post {
            try {
                mediaCodec?.signalEndOfInputStream()
                drainCodecUntilEos()
            } catch (e: Exception) {
                Log.e(TAG, "stop error: ${e.message}")
            } finally {
                releaseCodec()
                synchronized(muxerLock) {
                    releaseMuxerUnlocked()
                }
                latch.countDown()
            }
        }

        try {
            latch.await(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Log.w(TAG, "stop interrupted, force release")
        }
        cleanup()
        Log.i(TAG, "pipeline stopped")
    }

    fun rotateSegmentBlocking(newFile: File): File? {
        if (!encoding.get()) return null
        if (!rotating.compareAndSet(false, true)) return null
        val latch = CountDownLatch(1)
        var oldFile: File? = null
        encoderHandler?.post {
            try {
                oldFile = rotateSegmentOnEncoderThread(newFile)
            } finally {
                rotating.set(false)
                latch.countDown()
            }
        }
        try {
            latch.await(8, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Log.w(TAG, "rotateSegment interrupted")
        }
        return oldFile
    }

    fun currentOutputFile(): File? = outputFile

    fun currentSegmentBytes(): Long = segmentBytesWritten.get()

    private fun rotateSegmentOnEncoderThread(newFile: File): File? {
        val previous = outputFile ?: return null
        val vFormat = cachedVideoFormat
        val aFormat = cachedAudioFormat
        if (vFormat == null || aFormat == null) {
            Log.e(TAG, "rotateSegment: missing track format v=${vFormat != null} a=${aFormat != null}")
            return null
        }
        synchronized(muxerLock) {
            releaseMuxerUnlocked()
            outputFile = newFile
            segmentStartPtsUs = 0L
            segmentAudioStartPtsUs = 0L
            segmentBytesWritten.set(0)
            mediaMuxer = MediaMuxer(newFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerVideoTrack = mediaMuxer!!.addTrack(vFormat)
            muxerAudioTrack = mediaMuxer!!.addTrack(aFormat)
            mediaMuxer!!.start()
            muxerStarted = true
        }
        Log.i(TAG, "segment rotated ${previous.name} → ${newFile.name} (A/V)")
        return previous
    }

    fun setNalRelayEnabled(enabled: Boolean) {
        nalRelayEnabled.set(enabled)
        if (!enabled) {
            nalQueue.clear()
        }
        Log.i(TAG, "nal relay ${if (enabled) "enabled" else "disabled"}")
    }

    private fun needsNalRelay(): Boolean =
        nalRelayEnabled.get() || nalConsumers.isNotEmpty()

    fun subscribeNalConsumer(name: String, consumer: (ByteArray) -> Unit) {
        nalConsumers[name] = consumer
        Log.d(TAG, "nal consumer subscribed: $name (total=${nalConsumers.size})")
    }

    fun unsubscribeNalConsumer(name: String) {
        nalConsumers.remove(name)
        Log.d(TAG, "nal consumer unsubscribed: $name (total=${nalConsumers.size})")
    }

    private fun configureAndStartCodec(width: Int, height: Int, fps: Int, bitrate: Int): Surface {
        val format = MediaFormat.createVideoFormat(MIME_VIDEO, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }

        val codec = MediaCodec.createEncoderByType(MIME_VIDEO)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec = codec

        val surface = codec.createInputSurface()

        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    codec.releaseOutputBuffer(index, false)
                    return
                }

                if (info.size > 0) {
                    val buffer = codec.getOutputBuffer(index) ?: run {
                        codec.releaseOutputBuffer(index, false)
                        return
                    }

                    if (needsNalRelay()) {
                        val nalData = ByteArray(info.size)
                        val savedPos = buffer.position()
                        buffer.position(info.offset)
                        buffer.get(nalData, 0, info.size)
                        buffer.position(savedPos)
                        dispatchNalToConsumers(nalData)
                    }

                    writeVideoSample(buffer, info)

                    encodedByteCount.addAndGet(info.size.toLong())
                    encodedFrameCount++
                    lastNalProducedMs = System.currentTimeMillis()
                }

                codec.releaseOutputBuffer(index, false)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "codec output format: $format")
                synchronized(muxerLock) {
                    cachedVideoFormat = format
                    tryStartMuxerLocked()
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "codec error: ${e.message}")
                mainHandler.post {
                    onEncoderError?.invoke("编码器错误: ${e.message}")
                }
            }
        }, encoderHandler)

        codec.start()
        Log.i(TAG, "codec started: ${codec.codecInfo.name}")
        return surface
    }

    private fun tryStartMuxerLocked() {
        if (muxerStarted) return
        val muxer = mediaMuxer ?: return
        val v = cachedVideoFormat
        val a = cachedAudioFormat
        if (!MuxerTrackGate.canStart(v != null, a != null)) return
        muxerVideoTrack = muxer.addTrack(v!!)
        muxerAudioTrack = muxer.addTrack(a!!)
        muxer.start()
        muxerStarted = true
        Log.i(TAG, "muxer started videoTrack=$muxerVideoTrack audioTrack=$muxerAudioTrack")
        flushPendingLocked()
    }

    private fun flushPendingLocked() {
        while (true) {
            val sample = pendingSamples.poll() ?: break
            writeSampleLocked(sample.isAudio, sample.data, sample.presentationTimeUs, sample.flags)
        }
    }

    private fun enqueuePending(isAudio: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        while (pendingSamples.size >= MAX_PENDING_SAMPLES) {
            pendingSamples.poll()
        }
        val data = ByteArray(info.size)
        val pos = buffer.position()
        buffer.position(info.offset)
        buffer.get(data)
        buffer.position(pos)
        pendingSamples.offer(PendingSample(isAudio, data, info.presentationTimeUs, info.flags))
    }

    private fun writeVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            if (!muxerStarted) {
                enqueuePending(isAudio = false, buffer, info)
                return
            }
            writeSampleLocked(
                isAudio = false,
                data = null,
                presentationTimeUs = info.presentationTimeUs,
                flags = info.flags,
                buffer = buffer,
                offset = info.offset,
                size = info.size,
            )
        }
    }

    private fun writeAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            if (!muxerStarted) {
                enqueuePending(isAudio = true, buffer, info)
                return
            }
            writeSampleLocked(
                isAudio = true,
                data = null,
                presentationTimeUs = info.presentationTimeUs,
                flags = info.flags,
                buffer = buffer,
                offset = info.offset,
                size = info.size,
            )
        }
    }

    private fun writeSampleLocked(
        isAudio: Boolean,
        data: ByteArray?,
        presentationTimeUs: Long,
        flags: Int,
        buffer: ByteBuffer? = null,
        offset: Int = 0,
        size: Int = data?.size ?: 0,
    ) {
        val muxer = mediaMuxer ?: return
        val track = if (isAudio) muxerAudioTrack else muxerVideoTrack
        if (track < 0) return
        try {
            val startPts: Long
            val adjusted: Long
            if (isAudio) {
                segmentAudioStartPtsUs = SegmentPtsAdjuster.segmentStartForFirstFrame(
                    presentationTimeUs,
                    segmentAudioStartPtsUs,
                )
                startPts = segmentAudioStartPtsUs
                adjusted = SegmentPtsAdjuster.adjustedPresentationUs(presentationTimeUs, startPts)
            } else {
                segmentStartPtsUs = SegmentPtsAdjuster.segmentStartForFirstFrame(
                    presentationTimeUs,
                    segmentStartPtsUs,
                )
                startPts = segmentStartPtsUs
                adjusted = SegmentPtsAdjuster.adjustedPresentationUs(presentationTimeUs, startPts)
            }
            val outInfo = MediaCodec.BufferInfo()
            if (data != null) {
                val bb = ByteBuffer.wrap(data)
                outInfo.set(0, data.size, adjusted, flags)
                muxer.writeSampleData(track, bb, outInfo)
                segmentBytesWritten.addAndGet(data.size.toLong())
            } else if (buffer != null) {
                outInfo.set(offset, size, adjusted, flags)
                muxer.writeSampleData(track, buffer, outInfo)
                segmentBytesWritten.addAndGet(size.toLong())
            }
            maybeNotifySegmentLimit()
        } catch (e: Exception) {
            Log.w(TAG, "muxer write ${if (isAudio) "audio" else "video"} failed: ${e.message}")
        }
    }

    private fun dispatchNalToConsumers(nalData: ByteArray) {
        while (nalQueue.size >= MAX_NAL_QUEUE_FRAMES) {
            nalQueue.poll()
        }
        nalQueue.offer(nalData)

        for ((name, consumer) in nalConsumers) {
            try {
                consumer(nalData)
            } catch (e: Exception) {
                Log.w(TAG, "nal consumer $name failed: ${e.message}")
            }
        }
    }

    private fun maybeNotifySegmentLimit() {
        if (segmentBytesWritten.get() < RecordingSegmentPolicy.maxSegmentBytes()) return
        if (rotating.get()) return
        val cb = onSegmentLimitReached ?: return
        mainHandler.post { cb.invoke() }
    }

    private fun drainCodecUntilEos() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val deadlineMs = System.currentTimeMillis() + 10_000L

        while (System.currentTimeMillis() < deadlineMs) {
            val status = try {
                codec.dequeueOutputBuffer(bufferInfo, 100_000)
            } catch (e: Exception) {
                Log.w(TAG, "dequeue error after EOS: ${e.message}")
                break
            }

            when (status) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "format changed during drain")
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                else -> {
                    if (status >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            codec.releaseOutputBuffer(status, false)
                            continue
                        }
                        if (bufferInfo.size > 0) {
                            val buffer = codec.getOutputBuffer(status)
                            if (buffer != null) {
                                if (needsNalRelay()) {
                                    val nalData = ByteArray(bufferInfo.size)
                                    val savedPos = buffer.position()
                                    buffer.position(bufferInfo.offset)
                                    buffer.get(nalData, 0, bufferInfo.size)
                                    buffer.position(savedPos)
                                    dispatchNalToConsumers(nalData)
                                }
                                writeVideoSample(buffer, bufferInfo)
                                encodedByteCount.addAndGet(bufferInfo.size.toLong())
                                encodedFrameCount++
                                lastNalProducedMs = System.currentTimeMillis()
                            }
                        }
                        codec.releaseOutputBuffer(status, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.i(TAG, "EOS received, drain complete")
                            break
                        }
                    }
                }
            }
        }
    }

    private fun releaseCodec() {
        try {
            mediaCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaCodec?.release()
        } catch (_: Exception) {
        }
        mediaCodec = null
    }

    private fun releaseMuxerUnlocked() {
        try {
            if (muxerStarted) {
                mediaMuxer?.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "muxer stop failed: ${e.message}")
        }
        try {
            mediaMuxer?.release()
        } catch (_: Exception) {
        }
        mediaMuxer = null
        muxerVideoTrack = -1
        muxerAudioTrack = -1
        muxerStarted = false
        segmentStartPtsUs = 0L
        segmentAudioStartPtsUs = 0L
    }

    private fun cleanup() {
        nalQueue.clear()
        nalConsumers.clear()
        pendingSamples.clear()
        lastNalProducedMs = 0L
        outputFile = null
        cachedVideoFormat = null
        cachedAudioFormat = null
        segmentBytesWritten.set(0)
        segmentStartPtsUs = 0L
        segmentAudioStartPtsUs = 0L
        rotating.set(false)
        nalRelayEnabled.set(false)
        audioTrack = null
        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null
    }
}
