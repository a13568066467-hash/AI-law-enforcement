package com.aifieldcam.app.platform

import android.os.Build

/**
 * DSJ-ZECN6A1 执法记录仪硬件参数（规格书）
 * 参见 docs/hardware/DSJ-ZECN6A1硬件参数.txt、ZE69-驱动控制接口.txt
 */
object DeviceProfile {

    const val MODEL_NAME = "DSJ-ZECN6A1"
    const val MANUFACTURER = "ZECN致业"

    // 结构与性能
    const val SIZE_MM = "85×59×29"
    const val WEIGHT_GRAM = 168
    const val WEIGHT_MAX_GRAM = 170
    const val DISPLAY_INCH = 2.8f
    const val CPU_DESC = "8核 2.0GHz 低功耗"
    const val RAM_ROM = "3GB+32GB"

    // 摄录
    const val FOV_DEGREE = 120
    const val DISTORTION_MAX_PERCENT = 15
    const val PHOTO_MAX_MP = 48
    const val VIDEO_WIDTH = 1920
    const val VIDEO_HEIGHT = 1080
    const val VIDEO_FPS = 30
    const val VIDEO_CODEC = "H.264"
    const val VIDEO_CONTAINER = "MP4"
    const val RECORD_HOURS_SINGLE_BATTERY = 18

    // 夜视 / 光感（规格：光传感器控制红外与补光；夜视有效距离 ≥5m）
    const val NIGHT_VISION_METERS = 5
    const val ALS_NIGHT_THRESHOLD = 80
    const val IR_BRIGHTNESS_NIGHT = 220

    // 环境
    const val IP_RATING = "IP67"
    const val TEMP_MIN_C = -30
    const val TEMP_MAX_C = 55
    const val BATTERY_MAH = 3200

    // 连接
    const val NETWORK = "4G全网通"
    const val CHARGE = "5V2A Type-C"
    const val POSITIONING = "GPS/北斗/GLONASS/Galileo"

    val isDsjZecn6a1: Boolean
        get() {
            val model = Build.MODEL.uppercase()
            val device = Build.DEVICE.uppercase()
            val product = Build.PRODUCT.uppercase()
            return model.contains("ZECN6A1") ||
                model.contains("DSJ-ZE") ||
                device.contains("ZECN6A1") ||
                product.contains("ZECN6A1") ||
                Ze69Hardware.isZe69Platform
        }

    fun summaryLine(): String = buildString {
        append(MODEL_NAME)
        append(" · ")
        append("${DISPLAY_INCH}寸 · ${VIDEO_WIDTH}p · ${BATTERY_MAH}mAh · $IP_RATING")
    }

    fun settingsDetail(): String = buildString {
        appendLine("型号：$MODEL_NAME（$MANUFACTURER）")
        appendLine("尺寸/重量：${SIZE_MM}mm · ${WEIGHT_GRAM}g")
        appendLine("屏幕：${DISPLAY_INCH}寸触摸 · 视场角 ${FOV_DEGREE}°")
        appendLine("存储：$RAM_ROM · 编码 $VIDEO_CODEC/$VIDEO_CONTAINER")
        appendLine("录像：${VIDEO_WIDTH}×${VIDEO_HEIGHT}@${VIDEO_FPS}fps · 单电约${RECORD_HOURS_SINGLE_BATTERY}h")
        appendLine("夜视：红外补光已禁用")
        appendLine("防护：$IP_RATING · ${TEMP_MIN_C}~${TEMP_MAX_C}℃")
        appendLine("网络：$NETWORK · $POSITIONING · $CHARGE")
        if (Ze69Hardware.isZe69Platform) {
            val probe = Ze69Hardware.probeNodes()
            append("sysfs：节点 ${probe.writableCount}/${probe.writeNodes.size}")
            probe.alsSample?.let { append(" · 光感=$it") }
        } else if (isDsjZecn6a1) {
            append("硬件识别：型号匹配（sysfs 需系统签名写入）")
        } else {
            append("执法记录仪模式")
        }
    }
}
