package com.aifieldcam.app.platform.commandcall

/**
 * 指挥连线旁路视频帧。优先 I420（YUV 直喂）；JPEG 仅兼容旧路径 / 测试。
 */
data class CommandCallVideoFrame(
    val width: Int,
    val height: Int,
    val jpegBytes: ByteArray = ByteArray(0),
    val i420Bytes: ByteArray = ByteArray(0),
    val timestampMs: Long = System.currentTimeMillis(),
) {
    val hasI420: Boolean get() = i420Bytes.isNotEmpty() && width > 0 && height > 0
    val hasJpeg: Boolean get() = jpegBytes.isNotEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommandCallVideoFrame) return false
        return width == other.width &&
            height == other.height &&
            timestampMs == other.timestampMs &&
            jpegBytes.contentEquals(other.jpegBytes) &&
            i420Bytes.contentEquals(other.i420Bytes)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + jpegBytes.contentHashCode()
        result = 31 * result + i420Bytes.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}

/** 旁路上行长边上限（540p：960×540）。默认约 30fps；本机录像仍为 1080p。 */
const val COMMAND_CALL_VIDEO_MAX_LONG_SIDE = 960
