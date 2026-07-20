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
 */
class RecordingCommandCallFrameSource(
    private val intervalMs: Long = 200L,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : CommandCallFrameSource {

    private val running = AtomicBoolean(false)
    private var onFrame: ((CommandCallVideoFrame) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (!NativeRecorder.isRecording()) {
                scheduleNext()
                return
            }
            NativeRecorder.grabRecordingFrame { jpeg ->
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
                scheduleNext()
            }
        }
    }

    override fun start(onFrame: (CommandCallVideoFrame) -> Unit) {
        this.onFrame = onFrame
        if (!running.compareAndSet(false, true)) return
        mainHandler.post(tick)
    }

    override fun stop() {
        running.set(false)
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
        /** 生产用 Bitmap 缩放到目标宽高（约 720p）。 */
        val BitmapJpegScaler = CommandCallJpegScaler { jpeg, targetW, targetH ->
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@CommandCallJpegScaler jpeg
            val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            if (scaled !== bitmap) bitmap.recycle()
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            scaled.recycle()
            out.toByteArray()
        }
    }
}
