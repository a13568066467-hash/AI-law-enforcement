package com.aifieldcam.app.platform

import android.content.Context
import android.util.Log

/**
 * 启动时探测 ZE69 节点、开启光感夜视与低电监测。
 */
object Ze69PlatformBootstrap {

    private const val TAG = "Ze69Bootstrap"

    fun onApplicationCreate(context: Context) {
        if (!DeviceProfile.isDsjZecn6a1 && !Ze69Hardware.isZe69Platform) return

        val probe = Ze69Hardware.probeNodes()
        Log.i(TAG, "platform=${probe.platformPresent} writable=${probe.writableCount}/${probe.writeNodes.size} als=${probe.alsSample}")

        // 探测 LED 节点是否实际可写（sysfs 权限）
        val ledsOk = Ze69Hardware.probeLedWritability()
        Log.i(TAG, "LEDs writable=$ledsOk")

        // 启动时强制关闭红外（硬件可能残留上次状态）
        Ze69Hardware.setNightVision(false)
        Log.i(TAG, "IR disabled on startup")

        BatteryIndicatorController.register(context)
        DeviceStatusIndicator.refresh()
        RecorderKeyAccessibility.ensureEnabledSilently(context)
    }

    fun onApplicationTerminate() {
        NightVisionController.stopAmbientMonitoring()
        Ze69Hardware.resetAllIndicators()
    }
}
