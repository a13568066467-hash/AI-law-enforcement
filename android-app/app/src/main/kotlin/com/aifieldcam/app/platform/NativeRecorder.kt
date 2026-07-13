package com.aifieldcam.app.platform

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.CamcorderProfile
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.aifieldcam.app.util.PhoneCameraHelper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DSJ-ZECN6A1 本机 Camera2 + MediaRecorder。
 *
 * 说明：直接调用本机摄像头硬件，不启动系统「相机」App（com.mediatek.camera）。
 * 相机回调在 [cameraHandler] 线程；阻塞等待在 [cameraExecutor]，避免同线程死锁。
 */
object NativeRecorder {

    private const val TAG = "NativeRecorder"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 编码管线异常或看门狗检测到文件停涨时回调（主线程） */
    @Volatile
    var onPipelineInterrupted: ((String) -> Unit)? = null

    /** V2 编码管线开关：true 时使用 MediaEncoderPipeline（MediaCodec+MediaMuxer），
     *  false 时保持旧 MediaRecorder。循环录像模式下强制 true。 */
    @Volatile
    var useMediaEncoderPipeline: Boolean = false

    /** 热换片完成：旧文件已 finalize，主线程回调（录像不中断） */
    @Volatile
    var onSegmentRotated: ((File) -> Unit)? = null

    private var recordingAppContext: Context? = null
    private val segmentRotatePending = AtomicBoolean(false)

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordOutputFile: File? = null
    private var recordStartedAt: Long = 0L
    /** 录像中实时抓帧 ImageReader（PTT 长按触发）。
     * 本设备 HAL 会向 session 所有 surface 推帧，因此必须持续 drain。
     * maxImages=8 避免 buffer queue 阻塞。 */
    @Volatile
    private var frameReader: ImageReader? = null
    /** drain 线程最近一次抓到的 JPEG 帧 */
    @Volatile
    private var lastDrainedFrame: ByteArray? = null
    /** 限制 JPEG 拷贝频率，避免 30fps 大对象分配导致 GC 卡顿 */
    private var lastFrameCopyMs = 0L
    private val frameCopyIntervalMs = 800L

    private val recording = AtomicBoolean(false)
    private val opening = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    /** 录像中重复请求帧数计数器 */
    @Volatile
    private var repeatFrameSeq: Long = 0L
    private var lastRepeatFrameMs: Long = 0L
    /** 连续 CaptureFailure 计数器（首次/偶发失败不触发管线中断） */
    @Volatile
    private var consecutiveFailures = 0
    /** 连续失败阈值：≥8 次（约 250ms@30fps）且无成功帧时才判定管线死亡 */
    private const val FAILURE_DEATH_THRESHOLD = 8
    private var openingSinceMs = 0L
    private var capturingSinceMs = 0L

    /** NAL 单元消费者注册表，推流通道从 MediaEncoderPipeline 订阅 */
    private val nalConsumers = ConcurrentHashMap<String, (ByteArray) -> Unit>()

    // ── 持久化回调：避免匿名内部类在 open 后丢失事件 ──
    private var activeCameraCallback: CameraDevice.StateCallback? = null
    private var activeSessionCallback: CameraCaptureSession.StateCallback? = null
    private var activeCaptureCallback: CameraCaptureSession.CaptureCallback? = null

    fun isRecording(): Boolean = recording.get()

    fun isCapturing(): Boolean = capturing.get()

    fun isPreparing(): Boolean = opening.get() && !recording.get()

    fun isBusy(): Boolean = recording.get() || opening.get() || capturing.get()

    fun currentOutputFile(): File? = recordOutputFile ?: MediaEncoderPipeline.currentOutputFile()

    fun isSeamlessLoopMode(): Boolean =
        DeviceProfile.CONTINUOUS_LOOP_RECORDING &&
            RecordingSegmentPolicy.isSeamlessRotateEnabled() &&
            useMediaEncoderPipeline

    /** 由 Watchdog / 编码管线在分片写满时调用 */
    fun requestSegmentRotate() {
        if (!recording.get() || !isSeamlessLoopMode()) return
        if (!segmentRotatePending.compareAndSet(false, true)) return
        cameraExecutor.execute {
            try {
                rotateRecordingSegmentInternal()
            } finally {
                segmentRotatePending.set(false)
            }
        }
    }

    /** 最近一次帧投递距今毫秒数（Watchdog 判断 FAT32 元数据延迟） */
    fun lastRepeatFrameAgeMs(): Long {
        val lastMs = lastRepeatFrameMs
        return if (lastMs == 0L) Long.MAX_VALUE
        else System.currentTimeMillis() - lastMs
    }

    /**
     * 录像中实时抓一帧 JPEG（PTT 长按触发）。
     * 直接返回 drain 线程中最近缓存的最新帧，瞬时返回，零延时。
     * @param onFrame 主线程回调；返回 null 表示无可用帧或当前未在录像
     */
    fun grabRecordingFrame(onFrame: (ByteArray?) -> Unit) {
        if (!recording.get() || frameReader == null) {
            mainHandler.post { onFrame(null) }
            return
        }
        val frame = lastDrainedFrame
        mainHandler.post { onFrame(frame) }
    }

    /**
     * 息屏/非录像状态下临时打开相机抓一帧 JPEG（PTT 长按触发）。
     * 与 [captureStill] 共用互斥锁（capturing），与录像互斥。
     * @param onFrame 主线程回调；返回 null 表示抓帧失败
     */
    fun grabSingleFrame(context: Context, onFrame: (ByteArray?) -> Unit) {
        if (!ensureThread()) {
            mainHandler.post { onFrame(null) }
            return
        }
        if (!capturing.compareAndSet(false, true)) {
            mainHandler.post { onFrame(null) }
            return
        }
        capturingSinceMs = System.currentTimeMillis()
        val appContext = context.applicationContext
        cameraExecutor.execute {
            try {
                val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = chooseCameraId(manager)
                    ?: throw IllegalStateException("未找到后置摄像头")
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val jpegOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val size = chooseStillSize(manager, cameraId)
                val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
                val latch = java.util.concurrent.CountDownLatch(1)
                var jpeg: ByteArray? = null
                reader.setOnImageAvailableListener({ imageReader ->
                    var image: android.media.Image? = null
                    try {
                        image = imageReader.acquireLatestImage()
                        if (image != null) {
                            val buffer = image.planes[0].buffer
                            jpeg = ByteArray(buffer.remaining())
                            buffer.get(jpeg)
                        }
                    } finally {
                        image?.close()
                        latch.countDown()
                    }
                }, cameraHandler)
                val device = openCameraBlocking(manager, cameraId)
                try {
                    val session = createSessionBlocking(device, listOf(reader.surface))
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                    }.build()
                    session.capture(request, null, cameraHandler)
                    if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
                        Log.w(TAG, "grabSingleFrame timeout")
                    }
                    session.close()
                } finally {
                    reader.close()
                    device.close()
                }
                Log.i(TAG, "grabSingleFrame done: ${jpeg?.size ?: 0} bytes")
                mainHandler.post { onFrame(jpeg) }
            } catch (e: Exception) {
                Log.e(TAG, "grabSingleFrame failed", e)
                mainHandler.post { onFrame(null) }
            } finally {
                capturing.set(false)
                capturingSinceMs = 0L
            }
        }
    }

    /**
     * 纠正卡死的「启动中/录像中」标志（App 回到前台或侧键无响应时调用）。
     * @return 是否发生了状态复位
     */
    fun reconcileStaleState(maxOpeningMs: Long = 20_000L, maxCapturingMs: Long = 15_000L): Boolean {
        var changed = false
        if (opening.get() && !recording.get()) {
            val elapsed = System.currentTimeMillis() - openingSinceMs
            if (openingSinceMs == 0L || elapsed > maxOpeningMs) {
                Log.w(TAG, "reset stale opening (${elapsed}ms)")
                opening.set(false)
                openingSinceMs = 0L
                if (cameraExecutor.isShutdown) {
                    ensureThread()
                }
                cameraExecutor.execute { cleanupRecordingQuietly() }
                changed = true
            }
        }
        if (recording.get() && cameraDevice == null && mediaRecorder == null && !opening.get()) {
            Log.w(TAG, "reset stale recording flag (no active session)")
            recording.set(false)
            changed = true
        }
        if (capturing.get()) {
            val elapsed = System.currentTimeMillis() - capturingSinceMs
            if (capturingSinceMs == 0L || elapsed > maxCapturingMs) {
                Log.w(TAG, "reset stale capturing (${elapsed}ms)")
                capturing.set(false)
                capturingSinceMs = 0L
                changed = true
            }
        }
        return changed
    }

    fun forceReset() {
        opening.set(false)
        openingSinceMs = 0L
        recording.set(false)
        capturing.set(false)
        capturingSinceMs = 0L
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.execute { cleanupRecordingQuietly() }
        }
    }

    fun startRecording(
        context: Context,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!DeviceProfile.isDsjZecn6a1) {
            onError("非执法仪本机模式")
            return
        }
        if (recording.get()) {
            onError("已在录像中")
            return
        }
        if (opening.get()) {
            onError("正在启动录像，请稍候")
            return
        }
        if (capturing.get()) {
            onError("正在拍照，请稍候")
            return
        }
        if (!opening.compareAndSet(false, true)) {
            onError("正在启动录像，请稍候")
            return
        }
        openingSinceMs = System.currentTimeMillis()
        if (!ensureThread()) {
            opening.set(false)
            openingSinceMs = 0L
            onError("相机线程未就绪")
            return
        }
        val appContext = context.applicationContext
        if (DeviceProfile.CONTINUOUS_LOOP_RECORDING && RecordingSegmentPolicy.isSeamlessRotateEnabled()) {
            useMediaEncoderPipeline = true
        }
        recordingAppContext = appContext
        val outputFile = PhoneCameraHelper.newVideoFile(appContext)
        recordOutputFile = outputFile
        recordStartedAt = System.currentTimeMillis()
        cameraExecutor.execute {
            try {
                openForRecording(appContext, outputFile)
                recording.set(true)
                opening.set(false)
                openingSinceMs = 0L
                Log.i(TAG, "recording started -> ${outputFile.name}")
                onStarted()
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                opening.set(false)
                openingSinceMs = 0L
                cleanupRecordingQuietly()
                onError(e.message ?: "无法启动本机录像")
            }
        }
    }

    fun stopRecording(onStopped: (File?, String) -> Unit) {
        if (isPreparing()) {
            cancelPrepare(onStopped)
            return
        }
        if (!recording.get()) {
            onStopped(null, "当前未在录像")
            return
        }
        if (!ensureThread()) {
            recording.set(false)
            opening.set(false)
            openingSinceMs = 0L
            cleanupRecordingQuietly()
            onStopped(null, "相机线程未就绪")
            return
        }
        cameraExecutor.execute {
            val file = recordOutputFile
            val startedAt = recordStartedAt
            val stopStartedMs = System.currentTimeMillis()
            try {
                stopRecordingInternal()
                recording.set(false)
                val elapsedMs = System.currentTimeMillis() - stopStartedMs
                val bytes = file?.length() ?: 0L
                if (file != null && file.exists() && bytes > 0L) {
                    Log.i(
                        TAG,
                        "recording stopped -> ${file.name} (${bytes / 1024}KB) finalize=${elapsedMs}ms",
                    )
                    onStopped(file, "")
                } else {
                    onStopped(null, if (bytes == 0L) "录像文件为空" else "停止录像失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopRecording failed", e)
                recording.set(false)
                cleanupRecordingQuietly()
                onStopped(null, e.message ?: "停止录像失败")
            } finally {
                recordOutputFile = null
                recordStartedAt = startedAt
            }
        }
    }

    private fun cancelPrepare(onStopped: (File?, String) -> Unit) {
        if (!ensureThread()) {
            opening.set(false)
            openingSinceMs = 0L
            cleanupRecordingQuietly()
            onStopped(null, "")
            return
        }
        cameraExecutor.execute {
            opening.set(false)
            openingSinceMs = 0L
            cleanupRecordingQuietly()
            onStopped(null, "")
        }
    }

    fun captureStill(
        context: Context,
        onCaptured: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!DeviceProfile.isDsjZecn6a1) {
            onError("非执法仪本机模式")
            return
        }
        if (recording.get()) {
            onError("录像中无法拍照")
            return
        }
        if (opening.get()) {
            onError("正在启动录像，请稍候")
            return
        }
        if (!capturing.compareAndSet(false, true)) {
            onError("正在拍照，请稍候")
            return
        }
        capturingSinceMs = System.currentTimeMillis()
        if (!ensureThread()) {
            capturing.set(false)
            capturingSinceMs = 0L
            onError("相机线程未就绪")
            return
        }
        val appContext = context.applicationContext
        val outputFile = PhoneCameraHelper.newPhotoFile(appContext)
        cameraExecutor.execute {
            try {
                captureStillInternal(appContext, outputFile)
                if (outputFile.exists() && outputFile.length() > 0L) {
                    Log.i(TAG, "still captured -> ${outputFile.name}")
                    onCaptured(outputFile)
                } else {
                    onError("拍照失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "captureStill failed", e)
                onError(e.message ?: "拍照失败")
            } finally {
                capturing.set(false)
            }
        }
    }

    fun release() {
        val executor = cameraExecutor
        if (ensureThread()) {
            executor.execute {
                try {
                    if (recording.get()) {
                        stopRecordingInternal()
                    }
                } catch (_: Exception) {
                }
                recording.set(false)
                opening.set(false)
            openingSinceMs = 0L
                cameraDevice?.close()
                cameraDevice = null
            }
        }
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        executor.shutdown()
    }

    private fun ensureThread(): Boolean {
        if (cameraThread?.isAlive == true && cameraHandler != null && !cameraExecutor.isShutdown) {
            return true
        }
        cameraThread?.quitSafely()
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdownNow()
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraThread = HandlerThread("NativeRecorder-CB").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        return cameraHandler != null
    }

    private fun openForRecording(context: Context, outputFile: File) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = chooseCameraId(manager)
            ?: throw IllegalStateException("未找到后置摄像头")
        val size = chooseVideoSize(manager, cameraId)
        val cameraIdInt = cameraId.toIntOrNull()
        val profile = if (cameraIdInt != null &&
            CamcorderProfile.hasProfile(cameraIdInt, CamcorderProfile.QUALITY_1080P)
        ) {
            CamcorderProfile.get(cameraIdInt, CamcorderProfile.QUALITY_1080P)
        } else {
            null
        }
        val fps = profile?.videoFrameRate ?: DeviceProfile.VIDEO_FPS
        val bitrate = profile?.videoBitRate ?: 8_000_000
        if (profile != null) {
            Log.i(
                TAG,
                "CamcorderProfile 1080p: ${profile.videoFrameWidth}x${profile.videoFrameHeight} " +
                    "@${profile.videoFrameRate}fps bitrate=${profile.videoBitRate} duration=${profile.duration}",
            )
        }

        // ── V2 编码管线分支 ──
        val recordSurface: Surface
        if (useMediaEncoderPipeline) {
            // 使用 MediaCodec + MediaMuxer 管线（支持推流）
            Log.i(TAG, "using MediaEncoderPipeline for recording")
            recordSurface = MediaEncoderPipeline.start(
                outputFile = outputFile,
                width = size.width,
                height = size.height,
                fps = fps,
                bitrate = bitrate,
            )
            // 同步管道错误回调
            MediaEncoderPipeline.onEncoderError = { err ->
                mainHandler.post {
                    onPipelineInterrupted?.invoke(err)
                }
            }
            MediaEncoderPipeline.onSegmentLimitReached = {
                requestSegmentRotate()
            }
        } else {
            // 旧 MediaRecorder 路径
            val recorder = buildMediaRecorder(manager, cameraId, outputFile, size, profile)
            mediaRecorder = recorder
            recordSurface = recorder.surface
        }

        // PTT 长按抓帧：同会话多路输出（录像+ImageReader）
        // maxImages=8 避免本设备 HAL 向所有 surface 推帧时 buffer 溢出
        val reader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.JPEG, 8,
        )
        frameReader = reader
        lastDrainedFrame = null

        // ── 持久化相机回调：息屏时相机断开可感知 ──
        activeCameraCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {} // 已在 openCameraBlocking 外处理

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "CameraDevice disconnected during recording")
                camera.close()
                mainHandler.post {
                    onPipelineInterrupted?.invoke("录像已中断（相机断开），已自动保存")
                }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "CameraDevice error during recording: $error")
                camera.close()
                mainHandler.post {
                    onPipelineInterrupted?.invoke("录像已中断（相机错误 $error），已自动保存")
                }
            }
        }

        val device = openCameraBlocking(manager, cameraId)
        cameraDevice = device

        // ── 持久化会话回调：息屏时系统关闭会话可感知 ──
        activeSessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {} // 已在 createSessionBlocking 外处理

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "CameraCaptureSession configure failed")
            }

            override fun onClosed(session: CameraCaptureSession) {
                if (!recording.get()) return
                Log.w(TAG, "CameraCaptureSession closed during recording")
                mainHandler.post {
                    onPipelineInterrupted?.invoke("录像已中断（相机会话关闭），已自动保存")
                }
            }
        }

        val session = createSessionBlocking(device, listOf(recordSurface, reader.surface))
        captureSession = session

        // ── 持久化帧投递回调：检测重复请求失败 ──
        repeatFrameSeq = 0L
        lastRepeatFrameMs = System.currentTimeMillis()
        consecutiveFailures = 0
        activeCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
            @Suppress("DEPRECATION")
            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                if (failure.reason == CaptureFailure.REASON_ERROR) {
                    consecutiveFailures++
                    Log.w(
                        TAG,
                        "capture failed (consecutive=$consecutiveFailures): reason=$failure.reason",
                    )
                    if (consecutiveFailures >= FAILURE_DEATH_THRESHOLD) {
                        mainHandler.post {
                            onPipelineInterrupted?.invoke("录像帧投递失败（连续${consecutiveFailures}次），已自动保存")
                        }
                    }
                } else {
                    Log.w(TAG, "capture failed: reason=$failure.reason")
                }
            }

            override fun onCaptureSequenceCompleted(
                session: CameraCaptureSession,
                sequenceId: Int,
                frameNumber: Long,
            ) {
                if (consecutiveFailures > 0) {
                    Log.d(TAG, "frames resumed after $consecutiveFailures failures (frame=$frameNumber)")
                }
                consecutiveFailures = 0
                repeatFrameSeq = frameNumber
                lastRepeatFrameMs = System.currentTimeMillis()
            }
        }

        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(recordSurface)
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        }.build()
        // 持续 drain ImageReader，防止 buffer 堆积；仅周期性拷贝 JPEG 供 PTT/预览
        reader.setOnImageAvailableListener({ rdr ->
            var image: android.media.Image? = null
            try {
                image = rdr.acquireLatestImage() ?: return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastFrameCopyMs >= frameCopyIntervalMs) {
                    val buffer = image.planes[0].buffer
                    val size = buffer.remaining()
                    if (size > 0) {
                        val bytes = ByteArray(size)
                        buffer.get(bytes)
                        lastDrainedFrame = bytes
                        lastFrameCopyMs = now
                    }
                }
            } catch (_: Exception) {
            } finally {
                image?.close()
            }
        }, cameraHandler)
        session.setRepeatingRequest(request, activeCaptureCallback, cameraHandler)

        // 启动录制（管线模式下已完成 prepare，旧路径递归 prepare 在 buildMediaRecorder 中）
        if (!useMediaEncoderPipeline) {
            mediaRecorder?.start()
        }
    }

    private fun stopRecordingInternal() {
        // 清理持久化回调引用，避免泄漏
        activeCaptureCallback = null
        activeSessionCallback = null
        activeCameraCallback = null
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            Thread.sleep(200)
        } catch (_: InterruptedException) {
        }
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null

        if (useMediaEncoderPipeline) {
            // 管线路径：停止 MediaEncoderPipeline（内部会排空、写文件、释放）
            MediaEncoderPipeline.onEncoderError = null
            MediaEncoderPipeline.onSegmentLimitReached = null
            MediaEncoderPipeline.stop()
        } else {
            // 旧 MediaRecorder 路径
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "MediaRecorder.stop: ${e.message}")
            }
            try {
                mediaRecorder?.reset()
            } catch (_: Exception) {
            }
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {
            }
            mediaRecorder = null
        }

        try {
            frameReader?.setOnImageAvailableListener(null, null)
            frameReader?.close()
        } catch (_: Exception) {
        }
        frameReader = null
        lastDrainedFrame = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun cleanupRecordingQuietly() {
        try {
            stopRecordingInternal()
        } catch (_: Exception) {
        }
        // 管线模式下确保彻底释放（stopRecordingInternal 可能因异常未正常清理）
        if (useMediaEncoderPipeline) {
            try {
                MediaEncoderPipeline.onEncoderError = null
                MediaEncoderPipeline.onSegmentLimitReached = null
            } catch (_: Exception) {
            }
        }
        recordOutputFile = null
        recordingAppContext = null
    }

    private fun rotateRecordingSegmentInternal() {
        val ctx = recordingAppContext ?: return
        val oldFile = recordOutputFile ?: return
        val videoDir = PhoneCameraHelper.videoDir(ctx)
        val spaceOk = LoopRecordingStorage.ensureSpaceForNextSegment(
            context = ctx,
            videoDir = videoDir,
            protectedPath = oldFile.absolutePath,
        ) { deleted ->
            mainHandler.post { StorageRetentionWatchdog.onFileDeleted?.invoke(deleted) }
        }
        if (!spaceOk) {
            mainHandler.post {
                onPipelineInterrupted?.invoke("存储空间不足，录像已自动保存")
            }
            return
        }
        val newFile = PhoneCameraHelper.newVideoFile(ctx)
        val finalized = MediaEncoderPipeline.rotateSegmentBlocking(newFile)
        if (finalized == null) {
            Log.e(TAG, "seamless segment rotate failed")
            return
        }
        recordOutputFile = newFile
        recordStartedAt = System.currentTimeMillis()
        Log.i(TAG, "seamless segment ${finalized.name} → ${newFile.name}")
        mainHandler.post { onSegmentRotated?.invoke(finalized) }
    }

    private fun captureStillInternal(context: Context, outputFile: File) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = chooseCameraId(manager)
            ?: throw IllegalStateException("未找到后置摄像头")
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val jpegOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val size = chooseStillSize(manager, cameraId)
        val reader = android.media.ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.JPEG,
            2,
        )
        val latch = java.util.concurrent.CountDownLatch(1)
        var captureError: Exception? = null
        reader.setOnImageAvailableListener({ imageReader ->
            var image: android.media.Image? = null
            try {
                image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                FileOutputStream(outputFile).use { it.write(bytes) }
            } catch (e: Exception) {
                captureError = e
            } finally {
                image?.close()
                latch.countDown()
            }
        }, cameraHandler)

        val device = openCameraBlocking(manager, cameraId)
        try {
            val session = createSessionBlocking(device, listOf(reader.surface))
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            }.build()
            session.capture(request, null, cameraHandler)
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw IllegalStateException("拍照超时")
            }
            if (captureError != null) throw captureError!!
            session.close()
        } finally {
            reader.close()
            device.close()
        }
    }

    private fun openCameraBlocking(manager: CameraManager, cameraId: String): CameraDevice {
        val handler = cameraHandler
            ?: throw IllegalStateException("相机回调线程未就绪")
        val latch = java.util.concurrent.CountDownLatch(1)
        var opened: CameraDevice? = null
        var openError: Exception? = null
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opened = camera
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                openError = IllegalStateException("相机已断开")
                latch.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                openError = IllegalStateException("相机打开失败($error)")
                latch.countDown()
            }
        }, handler)
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw IllegalStateException("相机打开超时，请检查是否被其他应用占用")
        }
        if (openError != null) throw openError!!
        return opened ?: throw IllegalStateException("相机打开失败")
    }

    private fun createSessionBlocking(
        device: CameraDevice,
        surfaces: List<Surface>,
    ): CameraCaptureSession {
        val handler = cameraHandler
            ?: throw IllegalStateException("相机回调线程未就绪")
        val latch = java.util.concurrent.CountDownLatch(1)
        var sessionOut: CameraCaptureSession? = null
        var sessionError: Exception? = null
        device.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    sessionOut = session
                    latch.countDown()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    sessionError = IllegalStateException("相机会话配置失败")
                    latch.countDown()
                }
            },
            handler,
        )
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw IllegalStateException("相机会话配置超时")
        }
        if (sessionError != null) throw sessionError!!
        return sessionOut ?: throw IllegalStateException("相机会话配置失败")
    }

    private fun buildMediaRecorder(
        manager: CameraManager,
        cameraId: String,
        outputFile: File,
        size: Size,
        profile: CamcorderProfile?,
    ): MediaRecorder {
        val orientation = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        return MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile.absolutePath)
            setMaxDuration(0)
            setMaxFileSize(RecordingSegmentPolicy.maxSegmentBytes())
            if (profile != null) {
                setVideoEncodingBitRate(profile.videoBitRate)
                setVideoFrameRate(profile.videoFrameRate)
                setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight)
            } else {
                setVideoEncodingBitRate(8_000_000)
                setVideoFrameRate(DeviceProfile.VIDEO_FPS)
                setVideoSize(size.width, size.height)
            }
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOrientationHint(orientation)
            setOnInfoListener { _, what, extra ->
                Log.w(TAG, "MediaRecorder info what=$what extra=$extra")
                when (what) {
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> {
                        mainHandler.post {
                            onPipelineInterrupted?.invoke("录像达到最大时长，已自动保存")
                        }
                    }
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                        mainHandler.post {
                            onPipelineInterrupted?.invoke(RecordingSegmentPolicy.rolloverReason())
                        }
                    }
                }
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaRecorder error what=$what extra=$extra")
                // 编码致命错误：停止录像，不上报告警（避免触发无意义重启）
                mainHandler.post {
                    onPipelineInterrupted?.invoke("录像编码致命错误，已停止")
                }
            }
            prepare()
        }
    }

    private fun chooseCameraId(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    private fun chooseVideoSize(manager: CameraManager, cameraId: String): Size {
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(DeviceProfile.VIDEO_WIDTH, DeviceProfile.VIDEO_HEIGHT)
        val target = Size(DeviceProfile.VIDEO_WIDTH, DeviceProfile.VIDEO_HEIGHT)
        val sizes = map.getOutputSizes(MediaRecorder::class.java)
            ?: return target
        return sizes.minByOrNull { size ->
            kotlin.math.abs(size.width - target.width) + kotlin.math.abs(size.height - target.height)
        } ?: target
    }

    private fun chooseStillSize(manager: CameraManager, cameraId: String): Size {
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(DeviceProfile.VIDEO_WIDTH, DeviceProfile.VIDEO_HEIGHT)
        val target = Size(DeviceProfile.VIDEO_WIDTH, DeviceProfile.VIDEO_HEIGHT)
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return target
        return sizes.minByOrNull { size ->
            kotlin.math.abs(size.width - target.width) + kotlin.math.abs(size.height - target.height)
        } ?: target
    }
}
