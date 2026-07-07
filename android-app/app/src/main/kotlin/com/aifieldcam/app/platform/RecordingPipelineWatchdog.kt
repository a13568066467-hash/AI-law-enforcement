package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 检测录像文件是否仍在增长；息屏后编码停但 UI 仍显示「录像中」时自动收尾。
 */
object RecordingPipelineWatchdog {

    private const val TAG = "RecordWatchdog"
    private const val TICK_MS = 5_000L
    /** 息屏 + FGS 下部分机型缓冲写盘较慢 */
    private const val STALL_MS = 25_000L
    private const val START_GRACE_MS = 15_000L

    private val handler = Handler(Looper.getMainLooper())
    private var lastBytes = 0L
    private var lastGrowthAtMs = 0L
    private var graceUntilMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!NativeRecorder.isRecording()) return
            val file = NativeRecorder.currentOutputFile()
            val bytes = file?.length() ?: 0L
            val now = System.currentTimeMillis()
            if (now < graceUntilMs) {
                handler.postDelayed(this, TICK_MS)
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
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        stop()
        lastBytes = NativeRecorder.currentOutputFile()?.length() ?: 0L
        lastGrowthAtMs = System.currentTimeMillis()
        graceUntilMs = lastGrowthAtMs + START_GRACE_MS
        handler.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    /** 单测读取阈值 */
    internal fun stallThresholdMs(): Long = STALL_MS

    internal fun startGraceMs(): Long = START_GRACE_MS
}
