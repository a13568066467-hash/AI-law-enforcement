package com.aifieldcam.app.platform.commandcall

import android.os.Handler
import android.os.Looper
import com.aifieldcam.app.platform.NativeRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 从本机循环录像同会话 YUV ImageReader 旁路取 I420，供指挥连线上行。
 * 禁止 [NativeRecorder.grabSingleFrame]。
 */
class RecordingCommandCallFrameSource(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : CommandCallFrameSource {

    private val running = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private var onFrame: ((CommandCallVideoFrame) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            scheduleNext()
            if (!NativeRecorder.isRecording()) return
            if (!inFlight.compareAndSet(false, true)) return
            NativeRecorder.grabRecordingI420Frame { i420 ->
                try {
                    if (!running.get()) return@grabRecordingI420Frame
                    if (i420 != null && i420.i420.isNotEmpty()) {
                        onFrame?.invoke(
                            CommandCallVideoFrame(
                                width = i420.width,
                                height = i420.height,
                                i420Bytes = i420.i420,
                            ),
                        )
                    }
                } finally {
                    inFlight.set(false)
                }
            }
        }
    }

    override fun start(onFrame: (CommandCallVideoFrame) -> Unit) {
        this.onFrame = onFrame
        if (!running.compareAndSet(false, true)) return
        inFlight.set(false)
        mainHandler.post(tick)
    }

    override fun stop() {
        running.set(false)
        inFlight.set(false)
        mainHandler.removeCallbacks(tick)
        onFrame = null
    }

    override fun openedSecondCamera(): Boolean = false

    private fun scheduleNext() {
        if (running.get()) {
            mainHandler.postDelayed(tick, intervalMs)
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 33L

        /** 兼容旧 JPEG 缩放测试 / 回退路径。 */
        val BitmapJpegScaler = CommandCallJpegScaler { jpeg, targetW, targetH ->
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            var sample = 1
            val srcLong = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            val dstLong = maxOf(targetW, targetH).coerceAtLeast(1)
            while (srcLong / (sample * 2) >= dstLong) {
                sample *= 2
            }
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                ?: return@CommandCallJpegScaler jpeg
            val scaled =
                if (bitmap.width == targetW && bitmap.height == targetH) {
                    bitmap
                } else {
                    android.graphics.Bitmap.createScaledBitmap(bitmap, targetW, targetH, false).also {
                        if (it !== bitmap) bitmap.recycle()
                    }
                }
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 45, out)
            scaled.recycle()
            out.toByteArray()
        }
    }
}
