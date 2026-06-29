package com.aifieldcam.app.platform

import android.util.Log
import java.io.File

/**
 * ZE69 / DSJ-ZECN6A1 平台 sysfs 驱动（适配文档 ZE69-驱动控制接口.txt）
 * 需系统签名或 root 方可写入；普通 App 调用会静默失败并打日志。
 */
object Ze69Hardware {

    private const val TAG = "Ze69Hardware"
    private const val ALS_BASE = "/sys/devices/platform/odm/odm:camera_als"

    val isZe69Platform: Boolean
        get() = File(ALS_BASE).exists()

    fun setRgbRed(on: Boolean) = writeSysfs("$ALS_BASE/rgb_red_led", if (on) "1" else "0")

    fun setRgbGreen(on: Boolean) = writeSysfs("$ALS_BASE/rgb_green_led", if (on) "1" else "0")

    fun setRgbBlue(on: Boolean) = writeSysfs("$ALS_BASE/rgb_blue_led", if (on) "1" else "0")

    fun setRgRed(on: Boolean) = writeSysfs("$ALS_BASE/rg_red_led", if (on) "1" else "0")

    fun setRgGreen(on: Boolean) = writeSysfs("$ALS_BASE/rg_green_led", if (on) "1" else "0")

    fun setLaser(on: Boolean) = writeSysfs("$ALS_BASE/leise_led", if (on) "1" else "0")

    fun setIrBrightness(level: Int) {
        val v = level.coerceIn(0, 255)
        writeSysfs("$ALS_BASE/ir_led", v.toString())
    }

    fun setIrCut(open: Boolean) = writeSysfs("$ALS_BASE/ir_door", if (open) "1" else "0")

    /** 红外夜视：IR_CUT + 红外灯（规格有效距离 ≥5m） */
    fun setNightVision(on: Boolean, brightness: Int = DeviceProfile.IR_BRIGHTNESS_NIGHT) {
        if (!isZe69Platform) return
        if (on) {
            setIrCut(true)
            setIrBrightness(brightness)
        } else {
            setIrBrightness(0)
            setIrCut(false)
        }
    }

    fun readAlsData(): Int? {
        return try {
            File("$ALS_BASE/als_data").readText().trim().toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** 录像状态：红绿灯绿灯（规格「录像状态灯提示」） */
    fun setRecordingIndicator(on: Boolean) {
        if (!isZe69Platform) return
        setRgGreen(on)
        if (on) {
            setRgRed(false)
        }
    }

    /** AI 聆听：蓝灯指示 */
    fun setAiListeningIndicator(on: Boolean) {
        if (!isZe69Platform) return
        setRgbBlue(on)
    }

    /** 低电量：红灯警示 */
    fun setLowBatteryIndicator(on: Boolean) {
        if (!isZe69Platform) return
        setRgbRed(on)
    }

    fun resetAllIndicators() {
        if (!isZe69Platform) return
        setRgbRed(false)
        setRgbGreen(false)
        setRgbBlue(false)
        setRgRed(false)
        setRgGreen(false)
        setLaser(false)
        setNightVision(false)
    }

    private fun writeSysfs(path: String, value: String): Boolean {
        return try {
            File(path).writeText(value)
            true
        } catch (e: Exception) {
            Log.w(TAG, "write $path=$value failed: ${e.message}")
            false
        }
    }
}
