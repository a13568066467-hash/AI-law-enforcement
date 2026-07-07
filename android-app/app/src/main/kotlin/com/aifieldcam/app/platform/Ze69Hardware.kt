package com.aifieldcam.app.platform

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * ZE69 / DSJ-ZECN6A1 平台 sysfs（真机验证节点名称）
 *
 * 设备实际 LED 节点（权限 777，普通 App 可直接写入）：
 * - indicator_red_led   → 红色指示灯
 * - indicator_green_led → 绿色指示灯
 * - radium_spotlight    → 镭射灯/白光灯
 * - 无独立蓝灯节点（AI 聆听指示不亮灯）
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

    /** 指示灯 sysfs 节点是否实际可写 */
    @Volatile
    var ledNodesWritable: Boolean = true
        private set

    /** 探测 LED 节点可写性 */
    fun probeLedWritability(): Boolean {
        if (!isZe69Platform) {
            ledNodesWritable = false
            Log.i(TAG, "LED unavailable: not ZE69 platform")
            return false
        }
        val node = File(Ze69SysfsPaths.INDICATOR_GREEN)
        val original = try { node.readText().trim() } catch (_: Exception) { "0" }
        val testValue = if (original == "1") "0" else "1"
        val wrote = writeSysfs(Ze69SysfsPaths.INDICATOR_GREEN, testValue)
        if (wrote) {
            writeSysfs(Ze69SysfsPaths.INDICATOR_GREEN, original)
            ledNodesWritable = true
            Log.i(TAG, "LED nodes writable, indicator lights available")
        } else {
            ledNodesWritable = false
            Log.e(TAG, "LED nodes NOT writable. Check sysfs permissions.")
        }
        return ledNodesWritable
    }

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

    // ── 实际存在的 LED 控制（真机验证通过）──

    /** 红色指示灯（indicator_red_led） */
    fun setIndicatorRed(on: Boolean) = writeSysfs(Ze69SysfsPaths.INDICATOR_RED, if (on) "1" else "0")

    /** 绿色指示灯（indicator_green_led） */
    fun setIndicatorGreen(on: Boolean) = writeSysfs(Ze69SysfsPaths.INDICATOR_GREEN, if (on) "1" else "0")

    /** 白光灯/镭射灯（radium_spotlight） */
    fun setSpotlight(on: Boolean) = writeSysfs(Ze69SysfsPaths.RADIUM_SPOTLIGHT, if (on) "1" else "0")

    // ── 旧接口兼容（映射到真实节点）──

    @Deprecated("使用 setIndicatorRed")
    fun setRgRed(on: Boolean) = setIndicatorRed(on)

    @Deprecated("使用 setIndicatorGreen")
    fun setRgGreen(on: Boolean) = setIndicatorGreen(on)

    @Deprecated("使用 setIndicatorRed")
    fun setRgbRed(on: Boolean) = setIndicatorRed(on)

    @Deprecated("使用 setIndicatorGreen")
    fun setRgbGreen(on: Boolean) = setIndicatorGreen(on)

    /** 设备无独立蓝灯节点，调用无效果 */
    @Deprecated("设备无蓝灯节点")
    fun setRgbBlue(on: Boolean) {
        Log.d(TAG, "setRgbBlue($on): device has no blue LED node")
    }

    fun setLaser(on: Boolean) = writeSysfs(Ze69SysfsPaths.LASER, if (on) "1" else "0")

    @Suppress("UNUSED_PARAMETER")
    fun setIrBrightness(level: Int) {
        writeSysfs(Ze69SysfsPaths.IR_LED, "0")
    }

    @Suppress("UNUSED_PARAMETER")
    fun setIrCut(open: Boolean) = writeSysfs(Ze69SysfsPaths.IR_CUT, "0")

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

    /** PTT 短按：白光灯（radium_spotlight） */
    fun setWhiteLight(on: Boolean) {
        if (!ledNodesWritable) return
        setSpotlight(on)
    }

    /** @deprecated 使用 [DeviceStatusIndicator] */
    fun setRecordingIndicator(on: Boolean) {
        DeviceStatusIndicator.setVideoRecording(on)
    }

    /** PTT 长按 / AI 聆听：设备无蓝灯节点，调用无效果 */
    fun setAiListeningIndicator(on: Boolean) {
        if (!ledNodesWritable) return
        // 设备无蓝灯节点，用红灯短暂闪烁代替（可选）
        if (on) {
            setIndicatorRed(true)
        }
    }

    /** @deprecated 使用 [DeviceStatusIndicator] */
    fun setLowBatteryIndicator(on: Boolean) {
        if (!isZe69Platform) return
        if (on) setIndicatorRed(true)
    }

    /** @deprecated 使用 [DeviceStatusIndicator.pulsePhotoCapture] */
    fun pulseCaptureFlash() {
        DeviceStatusIndicator.pulsePhotoCapture()
    }

    fun resetAllIndicators() {
        if (!ledNodesWritable) return
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
