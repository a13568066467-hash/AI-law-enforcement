package com.aifieldcam.app.ui.common

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.View
import android.widget.TextView
import com.aifieldcam.app.data.SessionManager

object ThemisTopBar {

    fun shouldShowRecording(
        recording: Boolean,
        recorderBusy: Boolean,
        videoSaving: Boolean,
    ): Boolean = recording || (recorderBusy && !videoSaving)

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
