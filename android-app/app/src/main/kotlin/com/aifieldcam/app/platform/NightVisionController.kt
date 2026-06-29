package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 依据 DSJ-ZECN6A1 规格：光传感器检测环境亮度，自动控制红外灯与 IR_CUT。
 */
object NightVisionController {

    private const val TAG = "NightVision"
    private const val POLL_MS = 2_000L

    private val handler = Handler(Looper.getMainLooper())
    private var monitoring = false
    private var forceNight = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!monitoring) return
            applyFromAls()
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** 常驻光感监测（App 在前台或执法仪主控运行时） */
    fun startAmbientMonitoring() {
        if (!Ze69Hardware.isZe69Platform) return
        if (monitoring) return
        monitoring = true
        handler.post(pollRunnable)
        Log.i(TAG, "ambient ALS monitoring started")
    }

    fun stopAmbientMonitoring() {
        monitoring = false
        forceNight = false
        handler.removeCallbacks(pollRunnable)
        Ze69Hardware.setNightVision(false)
        Log.i(TAG, "ambient ALS monitoring stopped")
    }

    /** 录像/夜巡时强制保持夜视能力（仍依据 ALS 调亮度） */
    fun onRecordingStarted() {
        if (!Ze69Hardware.isZe69Platform) return
        forceNight = true
        startAmbientMonitoring()
        applyFromAls()
    }

    fun onRecordingStopped() {
        forceNight = false
        applyFromAls()
    }

    private fun applyFromAls() {
        val als = Ze69Hardware.readAlsData()
        if (als == null) {
            if (forceNight) {
                Ze69Hardware.setNightVision(true, DeviceProfile.IR_BRIGHTNESS_NIGHT)
            }
            return
        }
        val dark = als <= DeviceProfile.ALS_NIGHT_THRESHOLD
        when {
            dark || forceNight -> {
                val level = if (dark) {
                    mapAlsToIrLevel(als)
                } else {
                    DeviceProfile.IR_BRIGHTNESS_NIGHT / 2
                }
                Ze69Hardware.setNightVision(true, level)
            }
            else -> Ze69Hardware.setNightVision(false)
        }
    }

    /** ALS 越低红外越亮，保证约 5m 夜视距离 */
    private fun mapAlsToIrLevel(als: Int): Int {
        val t = DeviceProfile.ALS_NIGHT_THRESHOLD.coerceAtLeast(1)
        val ratio = 1f - (als.coerceIn(0, t).toFloat() / t)
        return (DeviceProfile.IR_BRIGHTNESS_NIGHT * ratio)
            .toInt()
            .coerceIn(64, 255)
    }
}
