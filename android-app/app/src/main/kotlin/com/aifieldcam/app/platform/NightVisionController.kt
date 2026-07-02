package com.aifieldcam.app.platform

import android.util.Log

/**
 * 光感 / 红外：按用户要求 **不自动开启红外**。
 * 夜视仅能通过后续显式开关调用 [Ze69Hardware.setNightVision]（当前未绑定侧键）。
 */
object NightVisionController {

    private const val TAG = "NightVision"

    /** 已禁用：不根据 ALS 自动开红外 */
    fun startAmbientMonitoring() {
        Log.i(TAG, "auto IR disabled (manual only)")
    }

    fun stopAmbientMonitoring() {
        Ze69Hardware.setNightVision(false)
    }

    fun onRecordingStarted() {
        // 录像时不自动开红外
    }

    fun onRecordingStopped() {
        // no-op
    }
}
