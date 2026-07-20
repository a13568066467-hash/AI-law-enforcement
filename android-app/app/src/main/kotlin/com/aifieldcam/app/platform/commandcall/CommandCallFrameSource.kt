package com.aifieldcam.app.platform.commandcall

/**
 * 连线共摄帧源。生产实现从本机录像 ImageReader 旁路取 JPEG；测试注入 Fake。
 * 实现不得调用二次 openCamera（如 [com.aifieldcam.app.platform.NativeRecorder.grabSingleFrame]）。
 */
interface CommandCallFrameSource {
    fun start(onFrame: (CommandCallVideoFrame) -> Unit)

    fun stop()

    /** 是否走过会二次打开相机的路径；共摄合规实现必须恒为 false。 */
    fun openedSecondCamera(): Boolean = false
}

/** 测试用：手动推帧，永不 openCamera。 */
class FakeCommandCallFrameSource : CommandCallFrameSource {
    @Volatile
    private var sink: ((CommandCallVideoFrame) -> Unit)? = null

    @Volatile
    private var started = false

    override fun start(onFrame: (CommandCallVideoFrame) -> Unit) {
        sink = onFrame
        started = true
    }

    override fun stop() {
        started = false
        sink = null
    }

    fun emit(frame: CommandCallVideoFrame) {
        sink?.invoke(frame)
    }

    fun isStarted(): Boolean = started

    override fun openedSecondCamera(): Boolean = false
}

fun interface CommandCallJpegScaler {
    fun scale(jpeg: ByteArray, targetWidth: Int, targetHeight: Int): ByteArray
}

/** 单测：不改字节，只依赖 CoCapture 改写宽高元数据。 */
val IdentityCommandCallJpegScaler = CommandCallJpegScaler { jpeg, _, _ -> jpeg }
