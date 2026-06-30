package com.aifieldcam.app.platform

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.aifieldcam.app.util.PhoneCameraHelper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DSJ-ZECN6A1 本机 Camera2 + MediaRecorder，不依赖 BLE。
 */
object NativeRecorder {

    private const val TAG = "NativeRecorder"

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordOutputFile: File? = null
    private var recordStartedAt: Long = 0L

    private val recording = AtomicBoolean(false)
    private val opening = AtomicBoolean(false)

    fun isRecording(): Boolean = recording.get()

    fun startRecording(
        context: Context,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!DeviceProfile.isDsjZecn6a1) {
            onError("非执法仪本机模式")
            return
        }
        if (recording.get() || !opening.compareAndSet(false, true)) {
            onError("已在录像中")
            return
        }
        ensureThread()
        val appContext = context.applicationContext
        val outputFile = PhoneCameraHelper.newVideoFile(appContext)
        recordOutputFile = outputFile
        recordStartedAt = System.currentTimeMillis()
        cameraHandler?.post {
            try {
                openForRecording(appContext, outputFile)
                recording.set(true)
                opening.set(false)
                onStarted()
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                opening.set(false)
                cleanupRecordingQuietly()
                onError(e.message ?: "无法启动本机录像")
            }
        }
    }

    fun stopRecording(onStopped: (File?, String) -> Unit) {
        if (!recording.get()) {
            onStopped(null, "当前未在录像")
            return
        }
        cameraHandler?.post {
            val file = recordOutputFile
            val startedAt = recordStartedAt
            try {
                stopRecordingInternal()
                recording.set(false)
                if (file != null && file.exists() && file.length() > 0L) {
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
        } ?: onStopped(null, "相机线程未就绪")
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
        ensureThread()
        val appContext = context.applicationContext
        val outputFile = PhoneCameraHelper.newPhotoFile(appContext)
        cameraHandler?.post {
            try {
                captureStillInternal(appContext, outputFile)
                if (outputFile.exists() && outputFile.length() > 0L) {
                    onCaptured(outputFile)
                } else {
                    onError("拍照失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "captureStill failed", e)
                onError(e.message ?: "拍照失败")
            }
        } ?: onError("相机线程未就绪")
    }

    fun release() {
        cameraHandler?.post {
            try {
                if (recording.get()) {
                    stopRecordingInternal()
                }
            } catch (_: Exception) {
            }
            recording.set(false)
            opening.set(false)
            cameraDevice?.close()
            cameraDevice = null
        }
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private fun ensureThread() {
        if (cameraThread?.isAlive == true) return
        cameraThread = HandlerThread("NativeRecorder").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun openForRecording(context: Context, outputFile: File) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = chooseCameraId(manager)
            ?: throw IllegalStateException("未找到后置摄像头")
        val size = chooseVideoSize(manager, cameraId)
        val recorder = buildMediaRecorder(outputFile, size)
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
        val size = chooseVideoSize(manager, cameraId)
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
                set(CaptureRequest.JPEG_ORIENTATION, 90)
            }.build()
            session.capture(request, null, cameraHandler)
            latch.await()
            if (captureError != null) throw captureError!!
            session.close()
        } finally {
            reader.close()
            device.close()
        }
    }

    private fun openCameraBlocking(manager: CameraManager, cameraId: String): CameraDevice {
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
        }, cameraHandler)
        latch.await()
        if (openError != null) throw openError!!
        return opened ?: throw IllegalStateException("相机打开失败")
    }

    private fun createSessionBlocking(
        device: CameraDevice,
        surfaces: List<Surface>,
    ): CameraCaptureSession {
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
            cameraHandler,
        )
        latch.await()
        if (sessionError != null) throw sessionError!!
        return sessionOut ?: throw IllegalStateException("相机会话配置失败")
    }

    private fun buildMediaRecorder(outputFile: File, size: Size): MediaRecorder {
        return MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile.absolutePath)
            setVideoEncodingBitRate(8_000_000)
            setVideoFrameRate(DeviceProfile.VIDEO_FPS)
            setVideoSize(size.width, size.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
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
}
