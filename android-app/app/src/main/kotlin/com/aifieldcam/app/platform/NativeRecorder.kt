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

    private const val MAX_FILE_BYTES_FAT32 = 3_500_000_000L // 安全低于 FAT32 4GB 限制

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

    private val recording = AtomicBoolean(false)
    private val opening = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    /** MediaRecorder 因 setMaxFileSize 已达自动停止，stopRecording 时需跳过 mediaRecorder.stop() */
    private val fileSizeReached = AtomicBoolean(false)
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

    // ── 持久化回调：避免匿名内部类在 open 后丢失事件 ──
    private var activeCameraCallback: CameraDevice.StateCallback? = null
    private var activeSessionCallback: CameraCaptureSession.StateCallback? = null
    private var activeCaptureCallback: CameraCaptureSession.CaptureCallback? = null

    fun isRecording(): Boolean = recording.get()

    fun isCapturing(): Boolean = capturing.get()

    fun isPreparing(): Boolean = opening.get() && !recording.get()

    fun isBusy(): Boolean = recording.get() || opening.get() || capturing.get()

    fun currentOutputFile(): File? = recordOutputFile

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
        val outputFile = PhoneCameraHelper.newVideoFile(appContext)
        recordOutputFile = outputFile
        recordStartedAt = System.currentTimeMillis()
        cameraExecutor.execute {
            try {
                // 新录像：清除上一个分段标记
                fileSizeReached.set(false)
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
            try {
                stopRecordingInternal()
                recording.set(false)
                val bytes = file?.length() ?: 0L
                if (file != null && file.exists() && bytes > 0L) {
                    Log.i(TAG, "recording stopped -> ${file.name} (${bytes / 1024}KB)")
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
        if (profile != null) {
            Log.i(
                TAG,
                "CamcorderProfile 1080p: ${profile.videoFrameWidth}x${profile.videoFrameHeight} " +
                    "@${profile.videoFrameRate}fps bitrate=${profile.videoBitRate} duration=${profile.duration}",
            )
        }
        val recorder = buildMediaRecorder(manager, cameraId, outputFile, size, profile)
        mediaRecorder = recorder
        val recordSurface = recorder.surface

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
        // 持续 drain ImageReader，防止 buffer 堆积触发 BQDUMP TIMED_OUT
        reader.setOnImageAvailableListener({ rdr ->
            var image: android.media.Image? = null
            try {
                image = rdr.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    if (bytes.isNotEmpty()) lastDrainedFrame = bytes
                }
            } catch (_: Exception) {
            } finally {
                image?.close()
            }
        }, cameraHandler)
        session.setRepeatingRequest(request, activeCaptureCallback, cameraHandler)
        recorder.start()
    }

    private fun stopRecordingInternal() {
        val alreadyStopped = fileSizeReached.getAndSet(false)
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
        if (!alreadyStopped) {
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "MediaRecorder.stop: ${e.message}")
            }
        } else {
            Log.i(TAG, "MediaRecorder already auto-stopped by filesize/duration limit")
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
        fileSizeReached.set(false)
        try {
            stopRecordingInternal()
        } catch (_: Exception) {
        }
        recordOutputFile = null
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
            setMaxFileSize(MAX_FILE_BYTES_FAT32)  // FAT32 4GB 限制前自动分段
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
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    // FAT32 4GB 限制：MediaRecorder 已自动停止并关闭文件
                    fileSizeReached.set(true)
                    mainHandler.post {
                        onPipelineInterrupted?.invoke(
                            "录像分段保存（已超过单文件 ${MAX_FILE_BYTES_FAT32 / (1024 * 1024)}MB 上限）",
                        )
                    }
                } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    fileSizeReached.set(true)
                    mainHandler.post {
                        onPipelineInterrupted?.invoke("录像达到最大时长，已自动保存")
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
