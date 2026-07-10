package com.aifieldcam.app.platform

/**
 * 循环录像存储：开录/换片前确保下一片（默认 1GB）可写，不足则删最旧 MP4（含相册副本）。
 */
object LoopRecordingStorage {

    private const val TAG = "LoopRecording"
    /** 除分片大小外额外预留空间（MB） */
    internal const val RESERVE_MB = 256L

    internal fun bytesNeededForNextSegment(segmentBytes: Long = RecordingSegmentPolicy.maxSegmentBytes()): Long =
        segmentBytes + RESERVE_MB * 1024 * 1024

    internal fun freeBytes(dir: java.io.File): Long =
        com.aifieldcam.app.util.MediaStorageLocator.storageUsage(dir).availableBytes

    /**
     * 删最旧可删片直到剩余空间 ≥ 下一片需求。
     * @return 是否满足（false = 无可删文件且仍不足）
     */
    fun ensureSpaceForNextSegment(
        context: android.content.Context,
        videoDir: java.io.File,
        protectedPath: String?,
        segmentBytes: Long = RecordingSegmentPolicy.maxSegmentBytes(),
        onDeleted: (java.io.File) -> Unit = {},
    ): Boolean {
        val needBytes = bytesNeededForNextSegment(segmentBytes)
        var available = freeBytes(videoDir)
        if (available >= needBytes) return true

        var deletedCount = 0
        while (available < needBytes) {
            val candidates = StorageRetentionWatchdog.selectPurgeCandidates(
                videoDir.listFiles(),
                protectedPath,
            )
            if (candidates.isEmpty()) {
                android.util.Log.w(
                    TAG,
                    "cannot free space: need=${needBytes / (1024 * 1024)}MB " +
                        "free=${available / (1024 * 1024)}MB",
                )
                return false
            }
            val victim = candidates.first()
            com.aifieldcam.app.util.GallerySaver.deleteVideoFromGallery(context, victim)
            val size = victim.length()
            if (!victim.delete()) {
                android.util.Log.w(TAG, "failed to delete ${victim.absolutePath}")
                return false
            }
            deletedCount++
            onDeleted(victim)
            available = freeBytes(videoDir)
            android.util.Log.i(TAG, "loop purge ${victim.name} (${size}B) free=${available / (1024 * 1024)}MB")
        }
        if (deletedCount > 0) {
            android.util.Log.i(TAG, "loop purge done: deleted=$deletedCount free=${available / (1024 * 1024)}MB")
        }
        return true
    }
}
