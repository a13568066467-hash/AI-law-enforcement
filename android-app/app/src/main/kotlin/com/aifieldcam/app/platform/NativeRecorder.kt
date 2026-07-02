package com.aifieldcam.app.platform

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
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

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordOutputFile: File? = null
    private var recordStartedAt: Long = 0L

    private val recording = AtomicBoolean(false)
    private val opening = AtomicBoolean(false)
    private var openingSinceMs = 0L

    fun isRecording(): Boolean = recording.get()

    fun isPreparing(): Boolean = opening.get() && !recording.get()

    fun isBusy(): Boolean = recording.get() || opening.get()

    /**
     * 纠正卡死的「启动中/录像中」标志（App 回到前台或侧键无响应时调用）。
     * @return 是否发生了状态复位
     */
    fun reconcileStaleState(maxOpeningMs: Long = 20_000L): Boolean {
        var changed = false
        if (opening.get() && !recording.get()) {
            val elapsed = System.currentTimeMillis() - openingSinceMs
            if (openingSinceMs == 0L || elapsed > maxOpeningMs) {
                Log.w(TAG, "reset stale opening (${elapsed}ms)")
                opening.set(false)
            openingSinceMs = 0L
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
        return changed
    }

    fun forceReset() {
        opening.set(false)
        openingSinceMs = 0L
        recording.set(false)
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
                    onStopped(null, "录像文件为空")
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
        if (!ensureThread()) {
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
        val recorder = buildMediaRecorder(manager, cameraId, outputFile, size)
        mediaRecorder = recorder
        val recordSurface = recorder.surface

        val device = openCameraBlocking(manager, cameraId)
        cameraDevice = device

        val session = createSessionBlocking(device, listOf(recordSurface))
        captureSession = session

        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(recordSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }.build()
        session.setRepeatingRequest(request, null, cameraHandler)
        recorder.start()
    }

    private fun stopRecordingInternal() {
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
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun cleanupRecordingQuietly() {
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
    ): MediaRecorder {
        val orientation = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val cameraIdInt = cameraId.toIntOrNull()
        val profile = if (cameraIdInt != null &&
            CamcorderProfile.hasProfile(cameraIdInt, CamcorderProfile.QUALITY_1080P)
        ) {
            CamcorderProfile.get(cameraIdInt, CamcorderProfile.QUALITY_1080P)
        } else {
            null
        }
        return MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile.absolutePath)
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
