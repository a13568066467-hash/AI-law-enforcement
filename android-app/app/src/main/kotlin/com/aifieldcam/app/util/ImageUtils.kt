package com.aifieldcam.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageUtils {

    data class ReadResult(
        val bytes: ByteArray?,
        val error: String?,
    )

    fun readJpegBytes(
        context: Context,
        uri: Uri,
        maxSide: Int = 1280,
        quality: Int = 85,
    ): ReadResult {
        return try {
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (mime == "image/jpeg" || mime == "image/jpg") {
                readJpegDirect(context, uri, maxSide, quality)
            } else if (Build.VERSION.SDK_INT >= 28) {
                readWithImageDecoder(context, uri, maxSide, quality)
            } else {
                readWithBitmapFactory(context, uri, maxSide, quality)
            }
        } catch (e: Exception) {
            ReadResult(null, "图片处理失败: ${e.message ?: "未知错误"}")
        }
    }

    private fun readJpegDirect(
        context: Context,
        uri: Uri,
        maxSide: Int,
        quality: Int,
    ): ReadResult {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return ReadResult(null, "无法打开照片，请检查相册权限")
        if (raw.size < 100) return ReadResult(null, "照片文件过小或已损坏")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ReadResult(raw, null)
        }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= maxSide) return ReadResult(raw, null)

        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: return ReadResult(raw, null)
        return encodeScaled(bitmap, maxSide, quality)
    }

    private fun readWithImageDecoder(
        context: Context,
        uri: Uri,
        maxSide: Int,
        quality: Int,
    ): ReadResult {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSide) {
                val ratio = maxSide.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * ratio).roundToInt().coerceAtLeast(1),
                    (info.size.height * ratio).roundToInt().coerceAtLeast(1),
                )
            }
        }
        return encodeScaled(bitmap, maxSide, quality)
    }

    @Suppress("DEPRECATION")
    private fun readWithBitmapFactory(
        context: Context,
        uri: Uri,
        maxSide: Int,
        quality: Int,
    ): ReadResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return ReadResult(null, "无法打开照片，请检查相册权限")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ReadResult(null, "不支持的图片格式，请换 JPG/PNG 照片")
        }

        val sample = max(1, max(bounds.outWidth, bounds.outHeight) / maxSide)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return ReadResult(null, "图片解码失败，请换一张照片重试")

        return encodeScaled(bitmap, maxSide, quality)
    }

    private fun encodeScaled(bitmap: Bitmap, maxSide: Int, quality: Int): ReadResult {
        val scaled = scaleDown(bitmap, maxSide)
        if (scaled !== bitmap) bitmap.recycle()

        val bytes = ByteArrayOutputStream().use { out ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                scaled.recycle()
                return ReadResult(null, "图片压缩失败")
            }
            scaled.recycle()
            out.toByteArray()
        }
        if (bytes.size < 100) return ReadResult(null, "处理后图片无效，请换一张照片")
        return ReadResult(bytes, null)
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = max(w, h)
        if (longest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / longest
        val nw = (w * ratio).roundToInt().coerceAtLeast(1)
        val nh = (h * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }
}
