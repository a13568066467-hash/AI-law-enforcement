package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * ZE69 / DSJ-ZECN6A1 平台 sysfs（docs/hardware/ZE69-驱动控制接口.txt）
 * 需系统签名或 root 方可写入；普通 App 调用会静默失败并打日志。
 */
object Ze69Hardware {

    private const val TAG = "Ze69Hardware"
    private val mainHandler = Handler(Looper.getMainLooper())

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

    fun setIrBrightness(level: Int) {
        val v = level.coerceIn(0, 255)
        writeSysfs(Ze69SysfsPaths.IR_LED, v.toString())
    }

    fun setIrCut(open: Boolean) = writeSysfs(Ze69SysfsPaths.IR_CUT, if (open) "1" else "0")

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
            File(Ze69SysfsPaths.ALS_DATA).readText().trim().toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** 录像状态：红绿灯绿灯 */
    fun setRecordingIndicator(on: Boolean) {
        if (!isZe69Platform) return
        setRgGreen(on)
        if (on) setRgRed(false)
    }

    /** AI 聆听：蓝灯指示 */
    fun setAiListeningIndicator(on: Boolean) {
        if (!isZe69Platform) return
        setRgbBlue(on)
    }

    /** 低电量：三色红灯警示 */
    fun setLowBatteryIndicator(on: Boolean) {
        if (!isZe69Platform) return
        setRgbRed(on)
    }

    /** 拍照短闪（交互设计：快门反馈） */
    fun pulseCaptureFlash() {
        if (!isZe69Platform) return
        setRgbGreen(true)
        mainHandler.postDelayed({ setRgbGreen(false) }, 120L)
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
            FileOutputStream(path).use { it.write(value.toByteArray()) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "write $path=$value failed: ${e.message}")
            false
        }
    }
}
