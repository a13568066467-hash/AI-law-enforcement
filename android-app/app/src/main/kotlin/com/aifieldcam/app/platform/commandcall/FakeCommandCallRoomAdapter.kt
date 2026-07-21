package com.aifieldcam.app.platform.commandcall

/**
 * 假 TRTC 房间：同步置 [CommandCallRoomState.IN_ROOM]，记录共摄旁路帧，不连接腾讯云。
 */
class FakeCommandCallRoomAdapter : CommandCallRoomAdapter {
    @Volatile
    override var state: CommandCallRoomState = CommandCallRoomState.IDLE
        private set

    @Volatile
    var lastJoinedCredentials: CommandCallCredentials? = null
        private set

    @Volatile
    var joinCount: Int = 0
        private set

    @Volatile
    var leaveCount: Int = 0
        private set

    @Volatile
    var customVideoEnabled: Boolean = false
        private set

    @Volatile
    var failNextJoin: Boolean = false

    @Volatile
    private var localAudioMuted: Boolean = true

    private val _pushedFrames = mutableListOf<CommandCallVideoFrame>()
    private val _pushedPcm = mutableListOf<ByteArray>()

    val pushedFrames: List<CommandCallVideoFrame>
        get() = synchronized(_pushedFrames) { _pushedFrames.toList() }

    val pushedFrameCount: Int
        get() = synchronized(_pushedFrames) { _pushedFrames.size }

    val pushedPcmChunks: List<ByteArray>
        get() = synchronized(_pushedPcm) { _pushedPcm.toList() }

    val pushedPcmCount: Int
        get() = synchronized(_pushedPcm) { _pushedPcm.size }

    override fun isInRoom(): Boolean = state == CommandCallRoomState.IN_ROOM

    override fun join(credentials: CommandCallCredentials): Boolean {
        if (state == CommandCallRoomState.IN_ROOM || state == CommandCallRoomState.JOINING) {
            return false
        }
        state = CommandCallRoomState.JOINING
        lastJoinedCredentials = credentials
        joinCount += 1
        localAudioMuted = true
        if (failNextJoin) {
            failNextJoin = false
            state = CommandCallRoomState.FAILED
            return false
        }
        state = CommandCallRoomState.IN_ROOM
        return true
    }

    override fun leave() {
        if (state == CommandCallRoomState.IDLE) return
        leaveCount += 1
        lastJoinedCredentials = null
        customVideoEnabled = false
        localAudioMuted = true
        synchronized(_pushedFrames) { _pushedFrames.clear() }
        synchronized(_pushedPcm) { _pushedPcm.clear() }
        state = CommandCallRoomState.IDLE
    }

    override fun enableCustomVideoSource(enabled: Boolean) {
        customVideoEnabled = enabled
        if (!enabled) {
            synchronized(_pushedFrames) { _pushedFrames.clear() }
        }
    }

    override fun pushVideoFrame(frame: CommandCallVideoFrame) {
        if (!customVideoEnabled || state != CommandCallRoomState.IN_ROOM) return
        synchronized(_pushedFrames) { _pushedFrames.add(frame) }
    }

    override fun setLocalAudioMuted(muted: Boolean) {
        localAudioMuted = muted
        if (muted) {
            // 半双工：静音后不再累计新上行（已推送的保留供断言）
        }
    }

    override fun isLocalAudioMuted(): Boolean = localAudioMuted

    override fun pushAudioPcm(pcm: ByteArray) {
        if (localAudioMuted || state != CommandCallRoomState.IN_ROOM) return
        if (pcm.isEmpty()) return
        synchronized(_pushedPcm) { _pushedPcm.add(pcm.copyOf()) }
    }
}
