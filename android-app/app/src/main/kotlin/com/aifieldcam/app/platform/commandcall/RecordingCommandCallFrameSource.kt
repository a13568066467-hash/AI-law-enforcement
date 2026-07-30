package com.aifieldcam.app.platform.commandcall

import com.aifieldcam.app.platform.NativeRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 从本机循环录像同会话 YUV ImageReader 旁路取 I420，供指挥连线上行。
 * 相机线程直推，禁止 [NativeRecorder.grabSingleFrame]，不做主线程轮询。
 */
class RecordingCommandCallFrameSource : CommandCallFrameSource {

    private val running = AtomicBoolean(false)

    @Volatile
    private var onFrame: ((CommandCallVideoFrame) -> Unit)? = null

    override fun start(onFrame: (CommandCallVideoFrame) -> Unit) {
        this.onFrame = onFrame
        if (!running.compareAndSet(false, true)) return
        NativeRecorder.setBypassFrameCallback { i420 ->
            if (!running.get()) return@setBypassFrameCallback
            val sink = this.onFrame ?: return@setBypassFrameCallback
            sink(
                CommandCallVideoFrame(
                    width = i420.width,
                    height = i420.height,
                    i420Bytes = i420.i420,
                ),
            )
        }
    }

    override fun stop() {
        running.set(false)
        NativeRecorder.setBypassFrameCallback(null)
        onFrame = null
    }

    override fun openedSecondCamera(): Boolean = false

    companion object {
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
