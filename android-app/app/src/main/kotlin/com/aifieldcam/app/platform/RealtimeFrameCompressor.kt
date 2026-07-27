package com.aifieldcam.app.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 将抓拍 JPEG 压到 Qwen Omni Realtime 建议体积（编码前 ≤190KB，约 480p）。
 */
internal object RealtimeFrameCompressor {
    private const val MAX_SIDE = 480
    private const val MAX_RAW_BYTES = 190_000
    private val QUALITIES = intArrayOf(70, 55, 40)

    fun compressForRealtime(jpeg: ByteArray): ByteArray? {
        if (jpeg.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_SIDE * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        val scaled = scaleToMaxSide(decoded, MAX_SIDE)
        if (scaled !== decoded) decoded.recycle()

        try {
            for (q in QUALITIES) {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, q, out)) continue
                val bytes = out.toByteArray()
                if (bytes.size in 1..MAX_RAW_BYTES) return bytes
            }
            return null
        } finally {
            scaled.recycle()
        }
    }

    private fun scaleToMaxSide(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val ratio = maxSide.toFloat() / longest
        val w = (src.width * ratio).roundToInt().coerceAtLeast(1)
        val h = (src.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
