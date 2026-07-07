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
    private const val STALL_MS = 12_000L

    private val handler = Handler(Looper.getMainLooper())
    private var lastBytes = 0L
    private var lastGrowthAtMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!NativeRecorder.isRecording()) return
            val file = NativeRecorder.currentOutputFile()
            val bytes = file?.length() ?: 0L
            val now = System.currentTimeMillis()
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
        handler.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }
}
