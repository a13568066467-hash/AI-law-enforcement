package com.aifieldcam.app.platform.commandcall

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.aifieldcam.app.platform.NativeRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 从本机循环录像同会话 ImageReader 旁路取 JPEG，供指挥连线上行。
 * 仅使用 [NativeRecorder.grabRecordingFrame]，禁止 [NativeRecorder.grabSingleFrame]。
 *
 * 默认约 15fps（66ms）：先排下一拍再取帧，避免「取帧耗时 + 间隔」叠成更高端到端延迟。
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
            NativeRecorder.grabRecordingFrame { jpeg ->
                try {
                    if (!running.get()) return@grabRecordingFrame
                    if (jpeg != null && jpeg.isNotEmpty()) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                        val w = bounds.outWidth.coerceAtLeast(1)
                        val h = bounds.outHeight.coerceAtLeast(1)
                        onFrame?.invoke(
                            CommandCallVideoFrame(
                                width = w,
                                height = h,
                                jpegBytes = jpeg,
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
        /** ~15fps，优先降端到端画面滞后。 */
        const val DEFAULT_INTERVAL_MS: Long = 66L

        /** 生产用 Bitmap 缩放到目标宽高；滤镜关闭 + 较低 JPEG 质量以减 CPU。 */
        val BitmapJpegScaler = CommandCallJpegScaler { jpeg, targetW, targetH ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            var sample = 1
            val srcLong = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            val dstLong = maxOf(targetW, targetH).coerceAtLeast(1)
            while (srcLong / (sample * 2) >= dstLong) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                ?: return@CommandCallJpegScaler jpeg
            val scaled =
                if (bitmap.width == targetW && bitmap.height == targetH) {
                    bitmap
                } else {
                    Bitmap.createScaledBitmap(bitmap, targetW, targetH, false).also {
                        if (it !== bitmap) bitmap.recycle()
                    }
                }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 55, out)
            scaled.recycle()
            out.toByteArray()
        }
    }
}
