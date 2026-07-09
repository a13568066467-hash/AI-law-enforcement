package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aifieldcam.app.util.TtsSpeaker
import java.util.concurrent.atomic.AtomicLong

/**
 * 推流管线健康监控。
 *
 * 监控项：
 *   - NAL 队列空闲（30s 无新帧 → WARN）
 *   - 分发失败累计（≥50 次 → 自动关闭推流）
 *   - 编码器帧数停滞（60s 无增长 → 自动关闭推流）
 *
 * 启动：推流开始时调用 [start]
 * 停止：推流结束时调用 [stop]
 */
object StreamingPipelineWatchdog {

    private const val TAG = "StreamWatchdog"
    private const val TICK_MS = 5_000L

    /** NAL 队列空闲告警阈值 */
    private const val NAL_IDLE_WARN_MS = 30_000L

    /** 帧数停滞自动关闭阈值 */
    private const val FRAME_STALL_MS = 60_000L

    /** 分发失败关闭阈值 */
    private const val DISPATCH_FAILURE_LIMIT = 50

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var monitoring = false
    private var lastFrameCount = AtomicLong(0)
    private var lastFrameTimeMs = 0L

    @Volatile
    var onStopStreaming: (() -> Unit)? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!monitoring) return
            try {
                checkHealth()
            } catch (e: Exception) {
                Log.w(TAG, "tick error: ${e.message}")
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (monitoring) return
        monitoring = true
        lastFrameCount.set(MediaEncoderPipeline.encodedFrameCount)
        lastFrameTimeMs = System.currentTimeMillis()
        handler.postDelayed(tickRunnable, TICK_MS)
        Log.i(TAG, "streaming watchdog started")
    }

    fun stop() {
        monitoring = false
        handler.removeCallbacks(tickRunnable)
        Log.i(TAG, "streaming watchdog stopped")
    }

    private fun checkHealth() {
        // 1. NAL 队列空闲检测
        val lastNalMs = MediaEncoderPipeline.lastNalProducedMs
        if (lastNalMs > 0) {
            val idleMs = System.currentTimeMillis() - lastNalMs
            if (idleMs > NAL_IDLE_WARN_MS) {
                Log.w(TAG, "NAL queue idle for ${idleMs / 1000}s")
            }
        }

        // 2. 分发失败检测
        val failures = VideoStreamManager.dispatchFailures
        if (failures >= DISPATCH_FAILURE_LIMIT) {
            Log.e(TAG, "dispatch failures reached $failures ≥ $DISPATCH_FAILURE_LIMIT, stopping stream")
            handler.post {
                TtsSpeaker.speak("推流异常，已自动关闭")
                onStopStreaming?.invoke()
            }
            stop()
            return
        }

        // 3. 编码器帧数停滞检测
        val currentFrames = MediaEncoderPipeline.encodedFrameCount
        val prevFrames = lastFrameCount.get()
        if (currentFrames == prevFrames && prevFrames > 0) {
            val stallMs = System.currentTimeMillis() - lastFrameTimeMs
            if (stallMs > FRAME_STALL_MS) {
                Log.e(TAG, "encoder stalled for ${stallMs / 1000}s, stopping stream")
                handler.post {
                    TtsSpeaker.speak("编码器异常，推流已停止")
                    onStopStreaming?.invoke()
                }
                stop()
                return
            }
        } else {
            lastFrameCount.set(currentFrames)
            lastFrameTimeMs = System.currentTimeMillis()
        }
    }
}
