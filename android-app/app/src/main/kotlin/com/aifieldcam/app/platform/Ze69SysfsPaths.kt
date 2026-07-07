package com.aifieldcam.app.platform

/**
 * ZE69 sysfs 节点（与 docs/hardware/ZE69-驱动控制接口.txt、厂商《适配文档》一致）
 */
object Ze69SysfsPaths {

    const val ALS_BASE = "/sys/devices/platform/odm/odm:camera_als"

    // 设备实际 LED 节点（DSJ-ZECN6A1 真机验证）
    const val INDICATOR_RED = "$ALS_BASE/indicator_red_led"
    const val INDICATOR_GREEN = "$ALS_BASE/indicator_green_led"
    const val RADIUM_SPOTLIGHT = "$ALS_BASE/radium_spotlight"
    const val ALL_POINT = "$ALS_BASE/all_point"

    // 保留旧名称兼容（实际设备上不存在）
    @Deprecated("使用 INDICATOR_RED 替代")
    const val RG_RED = INDICATOR_RED
    @Deprecated("使用 INDICATOR_GREEN 替代")
    const val RG_GREEN = INDICATOR_GREEN
    @Deprecated("设备无独立蓝灯节点")
    const val RGB_BLUE = "$ALS_BASE/rgb_blue_led"

    const val LASER = "$ALS_BASE/leise_led"
    const val IR_LED = "$ALS_BASE/ir_led"
    const val IR_CUT = "$ALS_BASE/ir_door"
    const val ALS_DATA = "$ALS_BASE/als_data"

    // 旧名称节点（可能不存在，保留以备兼容）
    const val RGB_RED = "$ALS_BASE/rgb_red_led"
    const val RGB_GREEN = "$ALS_BASE/rgb_green_led"

    /** 写入节点（0/1 或 0~255） */
    val writeNodes = listOf(
        INDICATOR_RED, INDICATOR_GREEN, RADIUM_SPOTLIGHT,
        LASER, IR_LED, IR_CUT,
    )

    val readNodes = listOf(ALS_DATA)
}
