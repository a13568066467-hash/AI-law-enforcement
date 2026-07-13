package com.aifieldcam.app.util

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 从视频中等间隔抽取 JPEG 帧。
 * 采样密度：max(1, round(时长秒数 / 60)) 张，上限 [MAX_FRAMES]。
 */
object VideoFrameExtractor {

    private const val TAG = "VideoFrameExtractor"
    /** 单次抽帧上限 */
    const val MAX_FRAMES = 24
    /** 抽帧 JPEG 质量（0-100），兼顾画质与体积 */
    private const val JPEG_QUALITY = 80
    /** 抽帧缩放宽（节省内存与传输量） */
    private const val FRAME_MAX_WIDTH = 640

    data class Result(
        val frames: List<ByteArray>,
        val durationMs: Long,
        val error: String? = null,
    )

    fun extract(file: File): Result {
        if (!file.exists() || file.length() == 0L) {
            return Result(emptyList(), 0L, "视频文件不存在或为空")
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)

            val rawDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = rawDuration?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            if (durationMs <= 0L) {
                return Result(emptyList(), 0L, "无法获取视频时长")
            }

            val durationSec = durationMs / 1000.0
            val frameCount = min(
                max(1, (durationSec / 60.0).roundToInt()),
                MAX_FRAMES,
            )

            Log.i(TAG, "extracting $frameCount frames from ${file.name} (${durationMs}ms)")

            val frames = mutableListOf<ByteArray>()
            for (i in 0 until frameCount) {
                val timeUs: Long = if (frameCount == 1) {
                    durationMs / 2 * 1000L
                } else {
                    (durationMs * 1000L * i / (frameCount - 1))
                }
                val bitmap = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
                if (bitmap != null) {
                    val scaled = if (bitmap.width > FRAME_MAX_WIDTH) {
                        val ratio = FRAME_MAX_WIDTH.toFloat() / bitmap.width
                        val h = (bitmap.height * ratio).roundToInt()
                        Bitmap.createScaledBitmap(bitmap, FRAME_MAX_WIDTH, h, true)
                    } else {
                        bitmap
                    }
                    val jpeg = bitmapToJpeg(scaled)
                    frames.add(jpeg)
                    Log.d(TAG, "frame $i/${frameCount} at ${timeUs / 1000}ms, ${jpeg.size} bytes")
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                } else {
                    Log.w(TAG, "frame $i/${frameCount} returned null, skipping")
                }
            }

            if (frames.isEmpty()) {
                Result(emptyList(), durationMs, "抽帧失败，所有帧均为空")
            } else {
                Result(frames, durationMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "extract failed for ${file.name}: ${e.message}")
            Result(emptyList(), 0L, "抽帧异常: ${e.message}")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }
}
