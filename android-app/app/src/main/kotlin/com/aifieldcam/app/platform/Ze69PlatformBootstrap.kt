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

        BatteryIndicatorController.register(context)
        DeviceStatusIndicator.refresh()
    }

    fun onApplicationTerminate() {
        NightVisionController.stopAmbientMonitoring()
        Ze69Hardware.resetAllIndicators()
    }
}
