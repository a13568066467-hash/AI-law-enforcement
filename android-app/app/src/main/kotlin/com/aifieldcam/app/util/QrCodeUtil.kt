package com.aifieldcam.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeUtil {

    fun encode(content: String, sizePx: Int = 512): Bitmap? {
        if (content.isBlank() || sizePx <= 0) return null
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val row = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            bmp.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            bmp
        } catch (_: Exception) {
            null
        }
    }
}
