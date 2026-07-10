package com.aifieldcam.app.platform

/**
 * 分片 MP4 时间戳：新文件从 0 起算，编码器 PTS 保持单调。
 */
object SegmentPtsAdjuster {

    fun segmentStartForFirstFrame(presentationTimeUs: Long, currentStartUs: Long): Long =
        if (currentStartUs == 0L) presentationTimeUs else currentStartUs

    fun adjustedPresentationUs(presentationTimeUs: Long, segmentStartUs: Long): Long =
        (presentationTimeUs - segmentStartUs).coerceAtLeast(0L)
}
