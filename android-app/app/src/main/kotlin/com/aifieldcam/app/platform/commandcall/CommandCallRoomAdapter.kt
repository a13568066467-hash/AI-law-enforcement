package com.aifieldcam.app.platform.commandcall

/**
 * 指挥连线进房所需凭证。SecretKey 永不出现在此结构中——仅后端签发 UserSig。
 */
data class CommandCallCredentials(
    val sdkAppId: Int,
    val roomId: String,
    val userId: String,
    val userSig: String,
)

/** 连线房间生命周期（可观察状态，不断言 TRTC SDK 内部）。 */
enum class CommandCallRoomState {
    IDLE,
    JOINING,
    IN_ROOM,
    FAILED,
}

/**
 * 指挥连线房间适配器接缝。真 TRTC 与假实现可互换注入；业务侧只依赖本接口。
 */
interface CommandCallRoomAdapter {
    val state: CommandCallRoomState

    fun isInRoom(): Boolean

    /** @return true 若成功进入房间；已在房内或失败时返回 false。 */
    fun join(credentials: CommandCallCredentials): Boolean

    fun leave()

    /** 启用/关闭自定义视频源（连线共摄旁路）；禁止在实现内二次 openCamera。 */
    fun enableCustomVideoSource(enabled: Boolean)

    /** 推送已缩放的旁路帧（约 720p）。未启用自定义源或未进房时可忽略。 */
    fun pushVideoFrame(frame: CommandCallVideoFrame)

    /**
     * 本地上行麦静音。连线对讲半双工：默认静音，PTT 按住时 false。
     * 平台侧常开麦不由此控制。
     */
    fun setLocalAudioMuted(muted: Boolean)

    fun isLocalAudioMuted(): Boolean

    /** 推送上行 PCM（仅在未静音且已进房时有效）。 */
    fun pushAudioPcm(pcm: ByteArray)
}
