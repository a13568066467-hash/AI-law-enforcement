package com.aifieldcam.app.ui.common

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.View
import android.widget.TextView
import com.aifieldcam.app.data.SessionManager

object ThemisTopBar {

    /**
     * 仅表示本机录像（含启动中/分段间隙）。
     * 拍照走红灯闪一下，不显示「录制中」。
     */
    fun shouldShowRecording(
        recording: Boolean,
        recorderBusy: Boolean,
        videoSaving: Boolean,
        stillCapturing: Boolean = false,
    ): Boolean {
        if (stillCapturing && !recording) return false
        return recording || (recorderBusy && !videoSaving && !stillCapturing)
    }

    fun bind(
        session: SessionManager,
        statusDot: View,
        statusText: TextView,
        batteryText: TextView,
        context: Context,
    ) {
        val active = shouldShowRecording(
            session.isRecording(),
            session.isRecorderBusy(),
            session.isVideoSaving(),
            session.isStillCapturing(),
        )
        statusDot.visibility = if (active) View.VISIBLE else View.GONE
        statusText.visibility = if (active) View.VISIBLE else View.GONE
        statusText.text = if (active) "录制中" else ""
        batteryText.text = batteryPercentText(context)
    }

    private fun batteryPercentText(context: Context): String {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return "--"
        return "${level * 100 / scale}%"
    }
}
