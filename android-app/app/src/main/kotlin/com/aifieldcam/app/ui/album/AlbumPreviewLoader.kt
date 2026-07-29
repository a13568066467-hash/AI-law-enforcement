package com.aifieldcam.app.ui.album

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Screen-sized preview decode cache for the album viewer.
 * Thumbs ([AlbumThumbLoader]) are used as instant placeholders while this loads.
 */
object AlbumPreviewLoader {
    private const val MAX_CACHE = 8
    private val memo = ThumbMemoizer<Bitmap>(MAX_CACHE)
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val taskSeq = AtomicInteger(0)

    data class Request(val token: Int, val future: Future<*>)

    fun peek(path: String): Bitmap? = memo.get(path)

    fun loadAsync(
        path: String,
        reqW: Int,
        reqH: Int,
        onReady: (token: Int, path: String, bitmap: Bitmap?) -> Unit,
    ): Request {
        val token = taskSeq.incrementAndGet()
        val future = executor.submit {
            val bitmap = memo.getOrLoad(path) { decodeSampled(path, reqW, reqH) }
            onReady(token, path, bitmap)
        }
        return Request(token, future)
    }

    fun prefetch(path: String, reqW: Int, reqH: Int) {
        if (memo.get(path) != null) return
        executor.execute {
            memo.getOrLoad(path) { decodeSampled(path, reqW, reqH) }
        }
    }

    fun cancel(request: Request?) {
        request?.future?.cancel(false)
    }

    private fun decodeSampled(path: String, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var halfW = bounds.outWidth / 2
        var halfH = bounds.outHeight / 2
        while (halfW / sample >= reqW && halfH / sample >= reqH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(path, opts)
    }
}
