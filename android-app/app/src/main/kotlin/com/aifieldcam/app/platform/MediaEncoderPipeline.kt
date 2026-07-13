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
 * H.264 编码管线 — 替换黑盒 MediaRecorder，使编码输出可双路分发：
 *   - [MediaMuxer] → MP4 文件（本机录制）
 *   - [NAL queue]   → 推流通道（GB28181 / WebRTC）
 *
 * 参数（与现有 NativeRecorder 一致）：
 *   - 编码器: H.264 (video/avc)
 *   - 分辨率: 1920 × 1080
 *   - 帧率:   30 fps
 *   - 码率:   8 Mbps
 *   - I 帧间隔: 1s
 *
 * 用法：
 *   1. val surface = start(outputFile, width, height, fps, bitrate)
 *   2. Camera2 以 surface 为 target 建 session + 发送 repeating request
 *   3. 消费者通过 subscribeNalConsumer() 订阅 H.264 NAL 单元
 *   4. 调用 stop() 停止编码，关闭文件
 */
object MediaEncoderPipeline {

    private const val TAG = "H264Pipeline"
    private const val MIME_VIDEO = "video/avc"
    private const val I_FRAME_INTERVAL_SEC = 1
    /** 无推流消费者时不入队；有推流时最多保留约 2s @30fps，防止无人消费撑爆堆 */
    private const val MAX_NAL_QUEUE_FRAMES = 60

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 编码状态 ──

    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    @Volatile
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var muxerVideoTrack = -1
    private var outputFile: File? = null
    private var muxerStarted = false
    private var cachedVideoFormat: MediaFormat? = null
    private var segmentStartPtsUs: Long = 0L
    private val segmentBytesWritten = AtomicLong(0)
    private val rotating = AtomicBoolean(false)

    /** 推流分发是否在拉 NAL；仅此时才把帧拷入 nalQueue */
    private val nalRelayEnabled = AtomicBoolean(false)

    private val encoding = AtomicBoolean(false)

    /** 当前分片写满（未停录），主线程/NativeRecorder 响应 */
    @Volatile
    var onSegmentLimitReached: (() -> Unit)? = null

    /** 编码输出 NAL 单元队列（线程安全，推流消费者轮询） */
    @Volatile
    var nalQueue: ConcurrentLinkedQueue<ByteArray> = ConcurrentLinkedQueue()
        private set

    /** 编码总帧数（诊断用） */
    @Volatile
    var encodedFrameCount: Long = 0
        private set

    /** 编码总字节数（诊断用） */
    @Volatile
    var encodedByteCount: AtomicLong = AtomicLong(0)
        private set

    /** NAL 单元消费者注册表 */
    private val nalConsumers = ConcurrentHashMap<String, (ByteArray) -> Unit>()

    /** 最近一次 NAL 产出时间（Watchdog 用） */
    @Volatile
    var lastNalProducedMs: Long = 0
        private set

    /** 编码器异常回调（主线程） */
    @Volatile
    var onEncoderError: ((String) -> Unit)? = null

    // ── 公开接口 ──

    fun isEncoding(): Boolean = encoding.get()

    /**
     * 启动编码管线。
     * @return [Surface] 供 Camera2 作为 target 使用
     */
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
        // 启动编码线程
        encoderThread = HandlerThread("H264Encoder").apply { start() }
        encoderHandler = Handler(encoderThread!!.looper)

        this.outputFile = outputFile
        this.nalQueue = ConcurrentLinkedQueue()
        this.encodedFrameCount = 0L
        this.encodedByteCount = AtomicLong(0)
        this.lastNalProducedMs = 0L
        this.cachedVideoFormat = null
        this.segmentStartPtsUs = 0L
        this.segmentBytesWritten.set(0)

        // 1. 初始化 MediaMuxer（提前创建，Track 在 codec 回调中添加）
        mediaMuxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        muxerVideoTrack = -1
        muxerStarted = false

        // 2. 配置 MediaCodec
        val inputSurface = configureAndStartCodec(width, height, fps, bitrate)

        encoding.set(true)
        Log.i(TAG, "pipeline started → ${outputFile.name} (${width}x${height}@${fps}fps ${bitrate / 1_000}kbps)")
        return inputSurface
    }

    /**
     * 停止编码管线。
     * 阻塞等待编码器排空所有缓冲帧并写入文件。
     */
    fun stop() {
        if (!encoding.compareAndSet(true, false)) {
            Log.w(TAG, "stop: not encoding")
            return
        }
        Log.i(TAG, "stopping pipeline, encoded ${encodedFrameCount} frames, ${encodedByteCount.get() / 1024}KB")

        val latch = CountDownLatch(1)
        encoderHandler?.post {
            try {
                // 发送 EOS 信号 → MediaCodec 排空缓冲区
                mediaCodec?.signalEndOfInputStream()
                drainCodecUntilEos()
            } catch (e: Exception) {
                Log.e(TAG, "stop error: ${e.message}")
            } finally {
                releaseCodec()
                releaseMuxer()
                latch.countDown()
            }
        }

        // 最多等 10s
        try {
            latch.await(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Log.w(TAG, "stop interrupted, force release")
        }
        cleanup()
        Log.i(TAG, "pipeline stopped")
    }

    /**
     * 热换输出文件：停止当前 muxer 并 finalize MP4，**不**停止 MediaCodec / Camera Session。
     * 必须在编码线程调用；对外用 [rotateSegmentBlocking]。
     * @return 已 finalize 的旧文件；失败返回 null
     */
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
        val format = cachedVideoFormat
        if (format == null) {
            Log.e(TAG, "rotateSegment: no cached video format")
            return null
        }
        releaseMuxer()
        outputFile = newFile
        segmentStartPtsUs = 0L
        segmentBytesWritten.set(0)
        mediaMuxer = MediaMuxer(newFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerVideoTrack = mediaMuxer!!.addTrack(format)
        mediaMuxer!!.start()
        muxerStarted = true
        Log.i(TAG, "segment rotated ${previous.name} → ${newFile.name}")
        return previous
    }

    /** 推流开始/停止时由 VideoStreamManager 调用，避免本机录像时 NAL 队列无限增长导致 OOM */
    fun setNalRelayEnabled(enabled: Boolean) {
        nalRelayEnabled.set(enabled)
        if (!enabled) {
            nalQueue.clear()
        }
        Log.i(TAG, "nal relay ${if (enabled) "enabled" else "disabled"}")
    }

    private fun needsNalRelay(): Boolean =
        nalRelayEnabled.get() || nalConsumers.isNotEmpty()

    /** 订阅 NAL 单元（推流消费者） */
    fun subscribeNalConsumer(name: String, consumer: (ByteArray) -> Unit) {
        nalConsumers[name] = consumer
        Log.d(TAG, "nal consumer subscribed: $name (total=${nalConsumers.size})")
    }

    /** 取消订阅 */
    fun unsubscribeNalConsumer(name: String) {
        nalConsumers.remove(name)
        Log.d(TAG, "nal consumer unsubscribed: $name (total=${nalConsumers.size})")
    }

    // ── 内部实现 ──

    private fun configureAndStartCodec(width: Int, height: Int, fps: Int, bitrate: Int): Surface {
        val format = MediaFormat.createVideoFormat(MIME_VIDEO, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)

            // 码率控制：CBR（恒定码率，推流友好）
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

            // Profile: Baseline (兼容性最好)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }

        val codec = MediaCodec.createEncoderByType(MIME_VIDEO)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec = codec

        val surface = codec.createInputSurface()

        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Surface 输入模式：不向 codec 投喂 ByteBuffer，忽略此回调
            }

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

                    // 仅推流时拷贝 NAL；纯本机录像只写 muxer，避免堆上无限堆积 ByteArray
                    if (needsNalRelay()) {
                        val nalData = ByteArray(info.size)
                        val savedPos = buffer.position()
                        buffer.position(info.offset)
                        buffer.get(nalData, 0, info.size)
                        buffer.position(savedPos)
                        dispatchNalToConsumers(nalData)
                    }

                    if (muxerStarted) {
                        writeToMuxer(buffer, info)
                    }

                    encodedByteCount.addAndGet(info.size.toLong())
                    encodedFrameCount++
                    lastNalProducedMs = System.currentTimeMillis()
                }

                codec.releaseOutputBuffer(index, false)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "codec output format: $format")
                cachedVideoFormat = format
                // MediaCodec 输出格式就绪 → 添加 muxer track 并启动
                val muxer = mediaMuxer ?: return
                if (muxerStarted) return
                muxerVideoTrack = muxer.addTrack(format)
                muxer.start()
                muxerStarted = true
                Log.i(TAG, "muxer started, videoTrack=$muxerVideoTrack")
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

    private fun writeToMuxer(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val muxer = mediaMuxer ?: return
        if (muxerVideoTrack < 0) return
        try {
            segmentStartPtsUs = SegmentPtsAdjuster.segmentStartForFirstFrame(
                info.presentationTimeUs,
                segmentStartPtsUs,
            )
            val adjustedPts = SegmentPtsAdjuster.adjustedPresentationUs(
                info.presentationTimeUs,
                segmentStartPtsUs,
            )
            val outInfo = MediaCodec.BufferInfo()
            outInfo.set(info.offset, info.size, adjustedPts, info.flags)
            muxer.writeSampleData(muxerVideoTrack, buffer, outInfo)
            val written = info.size.toLong()
            segmentBytesWritten.addAndGet(written)
            maybeNotifySegmentLimit()
        } catch (e: Exception) {
            Log.w(TAG, "muxer write failed: ${e.message}")
        }
    }

    private fun maybeNotifySegmentLimit() {
        if (segmentBytesWritten.get() < RecordingSegmentPolicy.maxSegmentBytes()) return
        if (rotating.get()) return
        val cb = onSegmentLimitReached ?: return
        mainHandler.post { cb.invoke() }
    }

    /** 排空编码器直到 EOS（在 encoderHandler 线程执行） */
    private fun drainCodecUntilEos() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val startMs = System.currentTimeMillis()
        val deadlineMs = startMs + 10_000L // 最多等 10s

        while (System.currentTimeMillis() < deadlineMs) {
            val status = try {
                codec.dequeueOutputBuffer(bufferInfo, 100_000) // 100ms 超时
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
                                if (muxerStarted) {
                                    writeToMuxer(buffer, bufferInfo)
                                }
                                encodedByteCount.addAndGet(bufferInfo.size.toLong())
                                encodedFrameCount++
                                lastNalProducedMs = System.currentTimeMillis()
                            }
                        }
                        codec.releaseOutputBuffer(status, false)

                        // BUFFER_FLAG_END_OF_STREAM → 排空完成
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

    private fun releaseMuxer() {
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
        muxerStarted = false
        segmentStartPtsUs = 0L
    }

    private fun cleanup() {
        nalQueue.clear()
        nalConsumers.clear()
        lastNalProducedMs = 0L
        outputFile = null
        cachedVideoFormat = null
        segmentBytesWritten.set(0)
        segmentStartPtsUs = 0L
        rotating.set(false)
        nalRelayEnabled.set(false)
        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null
    }
}
