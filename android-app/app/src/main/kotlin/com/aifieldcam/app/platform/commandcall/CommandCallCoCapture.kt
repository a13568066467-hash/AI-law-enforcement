package com.aifieldcam.app.platform.commandcall

/**
 * 连线共摄：把帧源旁路缩放到约 720p 后注入房间适配器。
 * 不持有相机；不二次 openCamera。
 */
object CommandCallCoCapture {

    @Volatile
    private var activeAdapter: CommandCallRoomAdapter? = null

    @Volatile
    private var activeSource: CommandCallFrameSource? = null

    @Volatile
    private var scaler: CommandCallJpegScaler = IdentityCommandCallJpegScaler

    fun isActive(): Boolean = activeAdapter != null

    fun bind(
        adapter: CommandCallRoomAdapter,
        source: CommandCallFrameSource,
        jpegScaler: CommandCallJpegScaler = IdentityCommandCallJpegScaler,
    ) {
        unbind()
        if (!adapter.isInRoom()) return
        scaler = jpegScaler
        activeAdapter = adapter
        activeSource = source
        adapter.enableCustomVideoSource(true)
        source.start { frame -> accept(frame) }
    }

    fun unbind() {
        val source = activeSource
        val adapter = activeAdapter
        activeSource = null
        activeAdapter = null
        source?.stop()
        adapter?.enableCustomVideoSource(false)
        scaler = IdentityCommandCallJpegScaler
    }

    private fun accept(frame: CommandCallVideoFrame) {
        val adapter = activeAdapter ?: return
        if (!adapter.isInRoom()) return
        val (tw, th) = CommandCallVideoScale.targetSize(
            frame.width,
            frame.height,
            COMMAND_CALL_VIDEO_MAX_LONG_SIDE,
        )
        val bytes = if (tw == frame.width && th == frame.height) {
            frame.jpegBytes
        } else {
            scaler.scale(frame.jpegBytes, tw, th)
        }
        adapter.pushVideoFrame(
            CommandCallVideoFrame(
                width = tw,
                height = th,
                jpegBytes = bytes,
                timestampMs = frame.timestampMs,
            ),
        )
    }
}
