package com.aifieldcam.app.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aifieldcam.app.util.GallerySaver
import com.aifieldcam.app.util.MediaStorageLocator
import java.io.File
import java.util.concurrent.Executors

/**
 * 录像期间轮询存储使用率：≥85% 时按时间从旧到新删本地录像（含相册副本），直到 <50%。
 * 与 [RecordingPipelineWatchdog] 的 1GB 停录兜底并存；不通知用户。
 */
object StorageRetentionWatchdog {

    private const val TAG = "StorageRetention"
    /** 每 60 秒检查一次 */
    private const val TICK_MS = 60_000L
    /** 触发清理的使用率（含） */
    internal const val TRIGGER_USED_PERCENT = 85
    /** 清理目标：使用率低于此值后停止 */
    internal const val TARGET_USED_PERCENT = 50

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val purgeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "StorageRetention").apply { isDaemon = true }
    }

    private var appContext: Context? = null
    private var watchDir: File? = null
    private var purgeRunning = false

    /** 文件已从磁盘删除后回调（主线程），用于同步 SessionManager 列表 */
    var onFileDeleted: ((File) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!shouldPoll()) {
                scheduleNext()
                return
            }
            val ctx = appContext ?: return
            val dir = watchDir ?: return
            if (!shouldTriggerPurge(MediaStorageLocator.storageUsage(dir).usedPercent)) {
                scheduleNext()
                return
            }
            if (purgeRunning) {
                scheduleNext()
                return
            }
            purgeRunning = true
            purgeExecutor.execute {
                try {
                    runPurge(ctx, dir)
                } finally {
                    purgeRunning = false
                    handler.post { scheduleNext() }
                }
            }
        }

        private fun scheduleNext() {
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start(context: Context, videoDir: File) {
        stop()
        appContext = context.applicationContext
        watchDir = videoDir
        handler.postDelayed(tick, TICK_MS)
        Log.i(TAG, "started on ${videoDir.absolutePath}")
    }

    fun stop() {
        handler.removeCallbacks(tick)
        appContext = null
        watchDir = null
        purgeRunning = false
    }

    internal fun shouldPoll(): Boolean =
        NativeRecorder.isRecording() && RecordingForegroundHold.count() > 0

    internal fun shouldTriggerPurge(usedPercent: Int): Boolean =
        usedPercent >= TRIGGER_USED_PERCENT

    internal fun shouldContinuePurge(usedPercent: Int): Boolean =
        usedPercent >= TARGET_USED_PERCENT

    internal fun selectPurgeCandidates(
        files: Array<File>?,
        protectedPath: String?,
    ): List<File> =
        files
            ?.asSequence()
            ?.filter { file ->
                file.isFile &&
                    file.extension.equals("mp4", ignoreCase = true) &&
                    file.length() > 0L &&
                    file.absolutePath != protectedPath
            }
            ?.sortedBy { it.lastModified() }
            ?.toList()
            ?: emptyList()

    private fun runPurge(context: Context, dir: File) {
        val protected = NativeRecorder.currentOutputFile()?.absolutePath
        var usage = MediaStorageLocator.storageUsage(dir)
        if (!shouldTriggerPurge(usage.usedPercent)) return

        var deletedCount = 0
        while (shouldContinuePurge(usage.usedPercent)) {
            val candidates = selectPurgeCandidates(dir.listFiles(), protected)
            if (candidates.isEmpty()) {
                Log.w(TAG, "no deletable videos at ${usage.usedPercent}% used")
                break
            }
            var progressed = false
            for (file in candidates) {
                if (!shouldContinuePurge(usage.usedPercent)) break
                GallerySaver.deleteVideoFromGallery(context, file)
                val sizeBytes = file.length()
                if (file.delete()) {
                    deletedCount++
                    progressed = true
                    handler.post { onFileDeleted?.invoke(file) }
                    Log.i(TAG, "purged ${file.name} (${sizeBytes}B)")
                } else {
                    Log.w(TAG, "failed to delete ${file.absolutePath}")
                }
                usage = MediaStorageLocator.storageUsage(dir)
            }
            if (!progressed) break
        }
        Log.i(
            TAG,
            "purge done: deleted=$deletedCount used=${usage.usedPercent}% free=${usage.availableBytes / (1024 * 1024)}MB",
        )
    }

    internal fun tickIntervalMs(): Long = TICK_MS
}
