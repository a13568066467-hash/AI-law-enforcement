package com.aifieldcam.mobile.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

object ImageBase64 {

    fun fromFile(path: String, maxEdge: Int = 640): String? {
        val file = File(path)
        if (!file.exists()) return null
        val raw = BitmapFactory.decodeFile(path) ?: return null
        val scaled = scaleDown(raw, maxEdge)
        if (scaled !== raw) raw.recycle()
        return encodeJpeg(scaled, quality = 85)
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val max = maxOf(w, h)
        if (max <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / max.toFloat()
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
