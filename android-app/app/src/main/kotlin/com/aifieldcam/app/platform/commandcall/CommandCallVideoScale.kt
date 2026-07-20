package com.aifieldcam.app.platform.commandcall

/**
 * 连线共摄尺寸：把源分辨率缩到长边 ≤ [maxLongSide]，保持宽高比。
 * 纯函数，便于单测；不触及相机。
 */
object CommandCallVideoScale {

    fun targetSize(
        srcWidth: Int,
        srcHeight: Int,
        maxLongSide: Int = COMMAND_CALL_VIDEO_MAX_LONG_SIDE,
    ): Pair<Int, Int> {
        require(srcWidth > 0 && srcHeight > 0) { "invalid size" }
        require(maxLongSide > 0) { "invalid maxLongSide" }
        val longSide = maxOf(srcWidth, srcHeight)
        if (longSide <= maxLongSide) return srcWidth to srcHeight
        val ratio = maxLongSide.toFloat() / longSide.toFloat()
        val w = (srcWidth * ratio).toInt().coerceAtLeast(1)
        val h = (srcHeight * ratio).toInt().coerceAtLeast(1)
        return w to h
    }
}
