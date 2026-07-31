package com.aifieldcam.app.platform.commandcall

/**
 * 连线共摄：把帧源旁路缩放到约 1920 长边（1080p）后注入房间适配器。
 * 推送跟不上时只保留最新帧。
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
        com.aifieldcam.app.platform.NativeRecorder.setCommandCallFramePump(true)
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
        com.aifieldcam.app.platform.NativeRecorder.setCommandCallFramePump(false)
    }

    private fun pushScaled(frame: CommandCallVideoFrame) {
        val adapter = activeAdapter ?: return
        if (!adapter.isInRoom()) return
        val (tw, th) = CommandCallVideoScale.targetSize(
            frame.width,
            frame.height,
            COMMAND_CALL_VIDEO_MAX_LONG_SIDE,
        )
        if (frame.hasI420) {
            val scaled =
                if (tw == frame.width && th == frame.height) {
                    frame
                } else {
                    val s = YuvFrameUtil.scaleI420(
                        YuvFrameUtil.I420Frame(frame.width, frame.height, frame.i420Bytes),
                        tw,
                        th,
                    )
                    CommandCallVideoFrame(
                        width = s.width,
                        height = s.height,
                        i420Bytes = s.i420,
                        timestampMs = frame.timestampMs,
                    )
                }
            adapter.pushVideoFrame(scaled)
            return
        }
        if (!frame.hasJpeg) return
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
