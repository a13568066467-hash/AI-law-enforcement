package com.aifieldcam.app.ui.album

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background thumbnail decode with in-memory LRU so RecyclerView rebinds do not re-decode.
 */
object AlbumThumbLoader {
    private const val MAX_CACHE_ENTRIES = 96
    private const val TARGET_PX = 160
    private val memo = ThumbMemoizer<Bitmap>(MAX_CACHE_ENTRIES)
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val taskSeq = AtomicInteger(0)

    data class Request(
        val token: Int,
        val future: Future<*>,
    )

    fun peek(path: String): Bitmap? = memo.get(path)

    /** Fire-and-forget warm-up for the first screen of grid cells. */
    fun prefetch(entries: List<Pair<String, Boolean>>) {
        if (entries.isEmpty()) return
        entries.forEach { (path, isVideo) ->
            if (memo.get(path) != null) return@forEach
            executor.execute {
                memo.getOrLoad(path) {
                    if (isVideo) decodeVideoThumb(path) else decodePhotoThumb(path)
                }
            }
        }
    }

    fun loadAsync(
        path: String,
        isVideo: Boolean,
        onReady: (token: Int, path: String, bitmap: Bitmap?) -> Unit,
    ): Request {
        val token = taskSeq.incrementAndGet()
        val future = executor.submit {
            val bitmap = memo.getOrLoad(path) {
                if (isVideo) decodeVideoThumb(path) else decodePhotoThumb(path)
            }
            onReady(token, path, bitmap)
        }
        return Request(token, future)
    }

    fun cancel(request: Request?) {
        request?.future?.cancel(false)
    }

    fun clearCache() {
        memo.clear()
    }

    /** Exposed for tests / diagnostics. */
    internal fun cacheLoadCount(): Int = memo.loadCount

    internal fun sampleSize(w: Int, h: Int, reqW: Int = TARGET_PX, reqH: Int = TARGET_PX): Int {
        var size = 1
        var halfW = w / 2
        var halfH = h / 2
        while (halfW / size >= reqW && halfH / size >= reqH) {
            size *= 2
        }
        return size.coerceAtLeast(1)
    }

    private fun decodePhotoThumb(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun decodeVideoThumb(path: String): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createVideoThumbnail(
                    File(path),
                    Size(TARGET_PX, TARGET_PX),
                    null,
                )
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(
                    path,
                    MediaStore.Images.Thumbnails.MICRO_KIND,
                )
            }
        } catch (_: Throwable) {
            null
        }
    }
}
