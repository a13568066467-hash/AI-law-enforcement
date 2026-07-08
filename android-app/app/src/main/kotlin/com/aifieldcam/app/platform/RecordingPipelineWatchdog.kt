package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aifieldcam.app.util.MediaStorageLocator
import java.io.File

/**
 * 录像管线看门狗：三合一检测
 * 1. 文件增长停滞检测（息屏/相机被收回）
 * 2. 存储空间监测（剩余 < 1GB 时自动停止录像）
 * 3. FAT32 分段自动保存后无缝续录
 */
object RecordingPipelineWatchdog {

    private const val TAG = "RecordWatchdog"
    /** 每 5 秒检测一次 */
    private const val TICK_MS = 5_000L
    /** 存储空间告警阈值（MB） */
    private const val STORAGE_WARN_MB = 2_048L
    /** 存储空间停止阈值（MB），低于此值自动停止录像 */
    private const val STORAGE_STOP_MB = 1_024L
    /** 息屏 + FGS 下部分机型缓冲写盘较慢 */
    private const val STALL_MS = 25_000L
    private const val START_GRACE_MS = 15_000L

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var lastBytes = 0L
    private var lastGrowthAtMs = 0L
    private var graceUntilMs = 0L
    private var storageWarned = false
    private var watchDir: File? = null
    private var tickCount = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!NativeRecorder.isRecording()) return
            val file = NativeRecorder.currentOutputFile()
            val bytes = file?.length() ?: 0L
            val now = System.currentTimeMillis()
            tickCount++

            // ── 1. 文件增长停滞检测 ──
            if (now < graceUntilMs) {
                scheduleNext()
                return
            }
            if (bytes > lastBytes) {
                lastBytes = bytes
                lastGrowthAtMs = now
            } else if (now - lastGrowthAtMs >= STALL_MS) {
                Log.w(TAG, "file stalled at ${bytes}B for ${now - lastGrowthAtMs}ms")
                NativeRecorder.onPipelineInterrupted?.invoke(
                    "录像已中断（息屏或相机被收回），已自动保存",
                )
                return
            }

            // ── 2. 存储空间监测（每 10 次 tick ≈ 50 秒检查一次） ──
            if (tickCount % 10 == 0 && watchDir != null) {
                val freeMb = MediaStorageLocator.freeMb(watchDir!!)
                if (freeMb in 1..STORAGE_STOP_MB) {
                    Log.w(TAG, "storage low: ${freeMb}MB remaining, stopping recording")
                    NativeRecorder.onPipelineInterrupted?.invoke(
                        "存储空间不足（剩余 ${freeMb}MB），录像已自动保存",
                    )
                    return
                }
                if (freeMb in (STORAGE_STOP_MB + 1)..STORAGE_WARN_MB && !storageWarned) {
                    storageWarned = true
                    Log.w(TAG, "storage warning: ${freeMb}MB remaining")
                }
            }

            scheduleNext()
        }

        private fun scheduleNext() {
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start(videoDir: File) {
        stop()
        watchDir = videoDir
        storageWarned = false
        tickCount = 0
        lastBytes = NativeRecorder.currentOutputFile()?.length() ?: 0L
        lastGrowthAtMs = System.currentTimeMillis()
        graceUntilMs = lastGrowthAtMs + START_GRACE_MS
        handler.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        watchDir = null
        storageWarned = false
    }

    /** 单测读取阈值 */
    internal fun stallThresholdMs(): Long = STALL_MS

    internal fun startGraceMs(): Long = START_GRACE_MS

    internal fun storageStopMb(): Long = STORAGE_STOP_MB

    internal fun storageWarnMb(): Long = STORAGE_WARN_MB
}