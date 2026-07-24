package com.aifieldcam.app.platform.commandcall

/**
 * 连线共摄：把帧源旁路缩放到约 960 长边后注入房间适配器。
 * 不持有相机；不二次 openCamera。
 * 推送跟不上时只保留最新帧，避免旁路队列堆高延迟（目标端到端 <200ms）。
 */
object CommandCallCoCapture {

    @Volatile
    private var activeAdapter: CommandCallRoomAdapter? = null

    @Volatile
    private var activeSource: CommandCallFrameSource? = null

    @Volatile
    private var scaler: CommandCallJpegScaler = IdentityCommandCallJpegScaler

    private var pump: LatestFramePump<CommandCallVideoFrame>? = null

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
        pump = LatestFramePump { frame -> pushScaled(frame) }
        adapter.enableCustomVideoSource(true)
        source.start { frame -> pump?.offer(frame) }
    }

    fun unbind() {
        val source = activeSource
        val adapter = activeAdapter
        activeSource = null
        activeAdapter = null
        pump?.clear()
        pump = null
        source?.stop()
        adapter?.enableCustomVideoSource(false)
        scaler = IdentityCommandCallJpegScaler
    }

    private fun pushScaled(frame: CommandCallVideoFrame) {
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
