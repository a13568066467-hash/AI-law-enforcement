package com.aifieldcam.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import kotlin.math.sqrt

/**
 * 本地 32×32 指纹（离线参考）；云端注册/登录使用 InsightFace 或 OpenCV SFace 算法比对。
 */
object FaceFingerprint {

    private const val SIZE = 32
    /** 与后端 PATROL_FACE_MATCH_THRESHOLD 默认一致（简易 32×32 指纹，非专业人脸 SDK） */
    const val MATCH_THRESHOLD = 0.55f

    fun fromJpeg(jpeg: ByteArray): String {
        val vector = vectorFromJpeg(jpeg)
        return vector.joinToString(",")
    }

    fun fromBase64(b64: String): String {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return fromJpeg(bytes)
    }

    fun similarity(a: String, b: String): Float {
        val va = parseVector(a)
        val vb = parseVector(b)
        if (va.isEmpty() || vb.isEmpty() || va.size != vb.size) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in va.indices) {
            dot += va[i] * vb[i]
            na += va[i] * va[i]
            nb += vb[i] * vb[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    fun matches(stored: String, capturedJpeg: ByteArray): Boolean {
        val current = fromJpeg(capturedJpeg)
        return similarity(stored, current) >= MATCH_THRESHOLD
    }

    private fun vectorFromJpeg(jpeg: ByteArray): List<Double> {
        val bitmap = decodeAndNormalize(jpeg) ?: return emptyList()
        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (!bitmap.isRecycled) bitmap.recycle()
        val gray = pixels.map { p ->
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (0.299 * r + 0.587 * g + 0.114 * b)
        }
        val mean = gray.average()
        val std = sqrt(gray.map { (it - mean) * (it - mean) }.average()).coerceAtLeast(1.0)
        return gray.map { (it - mean) / std }
    }

    private fun decodeAndNormalize(jpeg: ByteArray): Bitmap? {
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val squared = centerCropSquare(raw)
        if (squared != raw && !raw.isRecycled) raw.recycle()
        val scaled = Bitmap.createScaledBitmap(squared, SIZE, SIZE, true)
        if (scaled != squared && !squared.isRecycled) squared.recycle()
        return scaled
    }

    private fun centerCropSquare(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        return Bitmap.createBitmap(src, x, y, side, side)
    }

    private fun parseVector(raw: String): DoubleArray {
        if (raw.isBlank()) return DoubleArray(0)
        return raw.split(",").mapNotNull { it.toDoubleOrNull() }.toDoubleArray()
    }

    fun jpegToBase64(jpeg: ByteArray): String =
        Base64.encodeToString(jpeg, Base64.NO_WRAP)

    fun mirrorJpeg(jpeg: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val matrix = Matrix().apply { preScale(-1f, 1f) }
        val mirrored = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val out = java.io.ByteArrayOutputStream()
        mirrored.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (!mirrored.isRecycled) mirrored.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
        return out.toByteArray()
    }

    /** 按 ML Kit 人脸框裁剪并扩边，使注册/登录采样区域一致，提高室外通过率。 */
    fun cropToFace(jpeg: ByteArray, box: android.graphics.Rect, paddingRatio: Float = 0.45f): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val padX = (box.width() * paddingRatio).toInt()
        val padY = (box.height() * paddingRatio).toInt()
        var left = (box.left - padX).coerceAtLeast(0)
        var top = (box.top - padY).coerceAtLeast(0)
        var right = (box.right + padX).coerceAtMost(bitmap.width)
        var bottom = (box.bottom + padY).coerceAtMost(bitmap.height)
        if (right - left < 8 || bottom - top < 8) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return jpeg
        }
        val side = maxOf(right - left, bottom - top)
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        left = (cx - side / 2).coerceAtLeast(0)
        top = (cy - side / 2).coerceAtLeast(0)
        right = (left + side).coerceAtMost(bitmap.width)
        bottom = (top + side).coerceAtMost(bitmap.height)
        left = (right - side).coerceAtLeast(0)
        top = (bottom - side).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val out = java.io.ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (cropped != bitmap && !cropped.isRecycled) cropped.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
        return out.toByteArray()
    }
}
