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
}
