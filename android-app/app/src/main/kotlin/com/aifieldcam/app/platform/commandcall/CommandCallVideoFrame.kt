package com.aifieldcam.app.platform.commandcall

/**
 * 指挥连线旁路视频帧（约 720p JPEG）。SecretKey / 相机句柄不出现在此结构中。
 */
data class CommandCallVideoFrame(
    val width: Int,
    val height: Int,
    val jpegBytes: ByteArray,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommandCallVideoFrame) return false
        return width == other.width &&
            height == other.height &&
            timestampMs == other.timestampMs &&
            jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + jpegBytes.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}

/** 旁路上行长边上限（约 720p：1280×720）。本机录像仍为 1080p。 */
const val COMMAND_CALL_VIDEO_MAX_LONG_SIDE = 1280
