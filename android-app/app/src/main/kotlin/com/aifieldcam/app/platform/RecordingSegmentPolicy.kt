package com.aifieldcam.app.platform

/**
 * 录像分片策略：单文件达 [MAX_SEGMENT_BYTES] 后保存并自动续录下一段。
 * 默认 1GB/片，远低于 FAT32 4GB 硬限制；MediaRecorder 用 setMaxFileSize，
 * V2 管线由 [RecordingPipelineWatchdog] 按文件大小触发相同 reason。
 */
object RecordingSegmentPolicy {

    /** 单文件分片上限：1 GiB（约 1080p@8Mbps 录 ~17 分钟一片） */
    const val MAX_SEGMENT_BYTES = 1024L * 1024 * 1024

    /** Session 内热换 muxer，相机/编码器不重启（循环录像） */
    const val SEAMLESS_SEGMENT_ROTATE = true

    private const val ROLLOVER_MARKER = "分段保存"

    /** 第一段 stop 完成后延迟开第二段（相机释放 + 文件句柄关闭） */
    const val CONTINUE_DELAY_MS = 1_200L

    fun rolloverReason(): String =
        "录像分段保存（单文件已达 ${MAX_SEGMENT_BYTES / (1024 * 1024)}MB 上限）"

    fun isSegmentRollover(reason: String): Boolean = reason.contains(ROLLOVER_MARKER)

    /** 分段切换间隙：NativeRecorder 已停但下一段尚未 start，红灯应保持闪烁 */
    fun shouldShowRecordingLed(isRecording: Boolean, segmentRolloverActive: Boolean): Boolean =
        isRecording || segmentRolloverActive

    fun isSeamlessRotateEnabled(): Boolean = SEAMLESS_SEGMENT_ROTATE

    fun maxSegmentBytes(): Long = MAX_SEGMENT_BYTES

    fun continueDelayMs(): Long = CONTINUE_DELAY_MS
}
