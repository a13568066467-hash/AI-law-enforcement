package com.aifieldcam.app.platform.commandcall

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

/** Camera2 YUV_420_888 → I420 / NV21 / JPEG 工具（指挥连线旁路）。 */
object YuvFrameUtil {

    data class I420Frame(
        val width: Int,
        val height: Int,
        val i420: ByteArray,
    )

    fun imageToI420(image: Image): I420Frame? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val ySize = width * height
        val uvSize = ySize / 4
        val out = ByteArray(ySize + uvSize * 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, width, height, out, 0)
        copyPlane(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, width / 2, height / 2, out, ySize)
        copyPlane(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, width / 2, height / 2, out, ySize + uvSize)
        return I420Frame(width, height, out)
    }

    fun scaleI420(src: I420Frame, targetW: Int, targetH: Int): I420Frame {
        if (src.width == targetW && src.height == targetH) return src
        val tw = targetW.coerceAtLeast(2) and 1.inv()
        val th = targetH.coerceAtLeast(2) and 1.inv()
        val ySize = tw * th
        val uvSize = ySize / 4
        val out = ByteArray(ySize + uvSize * 2)
        // 最近邻，足够用于旁路缩放
        for (y in 0 until th) {
            val sy = (y * src.height) / th
            for (x in 0 until tw) {
                val sx = (x * src.width) / tw
                out[y * tw + x] = src.i420[sy * src.width + sx]
            }
        }
        val srcY = src.width * src.height
        val srcUv = srcY / 4
        val dstU = ySize
        val dstV = ySize + uvSize
        for (y in 0 until th / 2) {
            val sy = (y * (src.height / 2)) / (th / 2)
            for (x in 0 until tw / 2) {
                val sx = (x * (src.width / 2)) / (tw / 2)
                val si = sy * (src.width / 2) + sx
                out[dstU + y * (tw / 2) + x] = src.i420[srcY + si]
                out[dstV + y * (tw / 2) + x] = src.i420[srcY + srcUv + si]
            }
        }
        return I420Frame(tw, th, out)
    }

    fun i420ToJpeg(frame: I420Frame, quality: Int = 70): ByteArray? {
        val nv21 = i420ToNv21(frame) ?: return null
        return try {
            val yuv = YuvImage(nv21, ImageFormat.NV21, frame.width, frame.height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, frame.width, frame.height), quality, out)
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    fun i420ToNv21(frame: I420Frame): ByteArray? {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0 || frame.i420.size < w * h * 3 / 2) return null
        val ySize = w * h
        val out = ByteArray(ySize + ySize / 2)
        System.arraycopy(frame.i420, 0, out, 0, ySize)
        val uBase = ySize
        val vBase = ySize + ySize / 4
        var o = ySize
        for (i in 0 until ySize / 4) {
            out[o++] = frame.i420[vBase + i]
            out[o++] = frame.i420[uBase + i]
        }
        return out
    }

    private fun copyPlane(
        src: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        out: ByteArray,
        offset: Int,
    ) {
        val row = ByteArray(rowStride)
        var dst = offset
        for (r in 0 until height) {
            src.position(r * rowStride)
            val toRead = minOf(rowStride, src.remaining())
            src.get(row, 0, toRead)
            if (pixelStride == 1) {
                System.arraycopy(row, 0, out, dst, width)
                dst += width
            } else {
                var col = 0
                while (col < width) {
                    out[dst++] = row[col * pixelStride]
                    col++
                }
            }
        }
    }
}
