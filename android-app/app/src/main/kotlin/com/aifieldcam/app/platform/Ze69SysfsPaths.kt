package com.aifieldcam.app.platform

/**
 * ZE69 sysfs 节点（与 docs/hardware/ZE69-驱动控制接口.txt、厂商《适配文档》一致）
 */
object Ze69SysfsPaths {

    const val ALS_BASE = "/sys/devices/platform/odm/odm:camera_als"

    const val RGB_RED = "$ALS_BASE/rgb_red_led"
    const val RGB_GREEN = "$ALS_BASE/rgb_green_led"
    const val RGB_BLUE = "$ALS_BASE/rgb_blue_led"

    const val RG_RED = "$ALS_BASE/rg_red_led"
    const val RG_GREEN = "$ALS_BASE/rg_green_led"

    const val LASER = "$ALS_BASE/leise_led"
    const val IR_LED = "$ALS_BASE/ir_led"
    const val IR_CUT = "$ALS_BASE/ir_door"
    const val ALS_DATA = "$ALS_BASE/als_data"

    /** 写入节点（0/1 或 0~255） */
    val writeNodes = listOf(
        RGB_RED, RGB_GREEN, RGB_BLUE,
        RG_RED, RG_GREEN,
        LASER, IR_LED, IR_CUT,
    )

    val readNodes = listOf(ALS_DATA)
}
