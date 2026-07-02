package com.aifieldcam.app.platform

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * ZE69 / DSJ-ZECN6A1 平台 sysfs（docs/hardware/ZE69-驱动控制接口.txt）
 * 需系统签名或 root 方可写入；普通 App 调用会静默失败并打日志。
 */
object Ze69Hardware {

    private const val TAG = "Ze69Hardware"

    data class NodeProbe(
        val platformPresent: Boolean,
        val writeNodes: List<Pair<String, Boolean>>,
        val alsReadable: Boolean,
        val alsSample: Int?,
        val writableCount: Int,
    )

    val isZe69Platform: Boolean
        get() = File(Ze69SysfsPaths.ALS_BASE).exists()

    fun probeNodes(): NodeProbe {
        val writes = Ze69SysfsPaths.writeNodes.map { path ->
            path to File(path).exists()
        }
        val alsFile = File(Ze69SysfsPaths.ALS_DATA)
        val alsReadable = alsFile.canRead()
        val alsSample = if (alsReadable) readAlsData() else null
        return NodeProbe(
            platformPresent = isZe69Platform,
            writeNodes = writes,
            alsReadable = alsReadable,
            alsSample = alsSample,
            writableCount = writes.count { it.second },
        )
    }

    fun setRgbRed(on: Boolean) = writeSysfs(Ze69SysfsPaths.RGB_RED, if (on) "1" else "0")

    fun setRgbGreen(on: Boolean) = writeSysfs(Ze69SysfsPaths.RGB_GREEN, if (on) "1" else "0")

    fun setRgbBlue(on: Boolean) = writeSysfs(Ze69SysfsPaths.RGB_BLUE, if (on) "1" else "0")

    fun setRgRed(on: Boolean) = writeSysfs(Ze69SysfsPaths.RG_RED, if (on) "1" else "0")

    fun setRgGreen(on: Boolean) = writeSysfs(Ze69SysfsPaths.RG_GREEN, if (on) "1" else "0")

    fun setLaser(on: Boolean) = writeSysfs(Ze69SysfsPaths.LASER, if (on) "1" else "0")

    @Suppress("UNUSED_PARAMETER")
    fun setIrBrightness(level: Int) {
        writeSysfs(Ze69SysfsPaths.IR_LED, "0")
    }

    @Suppress("UNUSED_PARAMETER")
    fun setIrCut(open: Boolean) = writeSysfs(Ze69SysfsPaths.IR_CUT, "0")

    /** 红外补光已禁用：即使误调用开启，也只会落到关闭状态。 */
    @Suppress("UNUSED_PARAMETER")
    fun setNightVision(on: Boolean, brightness: Int = DeviceProfile.IR_BRIGHTNESS_NIGHT) {
        if (!isZe69Platform) return
        setIrBrightness(0)
        setIrCut(false)
    }

    fun readAlsData(): Int? {
        return try {
            File(Ze69SysfsPaths.ALS_DATA).readText().trim().toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** 说明书 PTT 短按：白光灯（sysfs 无独立白光灯节点，三色全亮近似） */
    fun setWhiteLight(on: Boolean) {
        if (!isZe69Platform) return
        setRgbRed(on)
        setRgbGreen(on)
        setRgbBlue(on)
    }

    /** @deprecated 使用 [DeviceStatusIndicator] */
    fun setRecordingIndicator(on: Boolean) {
        DeviceStatusIndicator.setVideoRecording(on)
    }

    /** PTT 长按 / AI 聆听：保留蓝灯（非说明书状态灯，仅辅助） */
    fun setAiListeningIndicator(on: Boolean) {
        if (!isZe69Platform) return
        setRgbBlue(on)
    }

    /** @deprecated 使用 [DeviceStatusIndicator] */
    fun setLowBatteryIndicator(on: Boolean) {
        if (!isZe69Platform) return
        if (on) setRgbRed(true)
    }

    /** @deprecated 使用 [DeviceStatusIndicator.pulsePhotoCapture] */
    fun pulseCaptureFlash() {
        DeviceStatusIndicator.pulsePhotoCapture()
    }

    fun resetAllIndicators() {
        if (!isZe69Platform) return
        setWhiteLight(false)
        setAiListeningIndicator(false)
        setNightVision(false)
        DeviceStatusIndicator.resetAll()
    }

    private fun writeSysfs(path: String, value: String): Boolean {
        return try {
            FileOutputStream(path).use { it.write(value.toByteArray()) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "write $path=$value failed: ${e.message}")
            false
        }
    }
}
