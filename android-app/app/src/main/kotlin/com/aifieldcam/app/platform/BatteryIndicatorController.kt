package com.aifieldcam.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

object BatteryIndicatorController {

    private const val TAG = "BatteryIndicator"
    private var registered = false

    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        app.registerReceiver(receiver, filter)
        registered = true
        Log.i(TAG, "battery monitoring registered")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val isFull = status == BatteryManager.BATTERY_STATUS_FULL ||
                (isCharging && pct >= 99)
            BatteryPolicy.update(pct)
            if (Ze69Hardware.ledNodesWritable) {
                DeviceStatusIndicator.onBatteryChanged(isCharging, isFull)
            }
        }
    }
}
