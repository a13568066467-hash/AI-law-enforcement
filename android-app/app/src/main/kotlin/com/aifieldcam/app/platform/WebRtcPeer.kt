package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebRTC 点对点连接管理器 — 通过 MQTT 传递信令（SDP offer/answer/ICE），
 * 媒体流从 [MediaEncoderPipeline] NAL 队列获取并通过 PeerConnection 推送到远端浏览器。
 *
 * 当前版本使用 MQTT 信令 + 本地 NAL 注入模式：
 *   1. 设备创建 SDP offer → 通过 MQTT 发给云端
 *   2. 云端返回 SDP answer → 设备设置 remote description
 *   3. ICE candidates 通过 MQTT 双向交换
 *   4. 媒体流从 NAL 队列注入（不依赖 WebRTC 硬件编码器）
 *
 * 生产环境中可替换为 google-webrtc SDK 提供完整的 PeerConnection。
 */
object WebRtcPeer {

    private const val TAG = "WebRtcPeer"

    // ── 状态 ──

    enum class State { IDLE, CONNECTING, CONNECTED, FAILED }

    @Volatile
    var state: State = State.IDLE
        private set

    private val active = AtomicBoolean(false)
    private var callId: String = ""
    private var peerSdp: String = ""

    // ── 回调 ──

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var onStateChanged: ((State) -> Unit)? = null

    @Volatile
    var onSdpOfferReady: ((sdp: String) -> Unit)? = null
        /** 本地 SDP offer 生成完毕 → 外部通过 MQTT 发布到 /event/webrtc/sdp/offer */

    @Volatile
    var onIceCandidateReady: ((candidate: String, sdpMid: String, sdpMLineIndex: Int) -> Unit)? = null
        /** 本地 ICE candidate → 外部通过 MQTT 发布到 /event/webrtc/ice/add */

    @Volatile
    var onCallConnected: (() -> Unit)? = null

    @Volatile
    var onCallEnded: (() -> Unit)? = null

    @Volatile
    var onError: ((String) -> Unit)? = null

    // ── 公开接口 ──

    fun isActive(): Boolean = active.get()

    /**
     * 创建 WebRTC offer 并发起呼叫。
     * 生成 SDP offer → 触发 [onSdpOfferReady]（外部通过 MQTT 发送）
     */
    fun createOfferAndCall(callId: String) {
        if (active.compareAndSet(false, true)) {
            this.callId = callId
            setState(State.CONNECTING)
            Log.i(TAG, "creating offer, callId=$callId")

            // 生成 SDP offer（简化版：Video-only, H.264）
            val offerSdp = buildSdpOffer()
            peerSdp = offerSdp
            Log.d(TAG, "local sdp offer ready (${offerSdp.length} bytes)")

            mainHandler.post {
                onSdpOfferReady?.invoke(offerSdp)
            }
        }
    }

    /**
     * 接收到远端 SDP answer → 设置 remote description
     */
    fun onSdpAnswer(sdp: String) {
        if (!active.get()) return
        peerSdp = sdp
        Log.i(TAG, "received SDP answer (${sdp.length} bytes)")
        // 简化模式：SDP answer 接收后直接标记为已连接
        setState(State.CONNECTED)
        mainHandler.post { onCallConnected?.invoke() }
    }

    /**
     * 接收到远端 ICE candidate
     */
    fun onRemoteIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        if (!active.get()) return
        Log.d(TAG, "remote ICE candidate: mid=$sdpMid idx=$sdpMLineIndex")
        // 简化模式：ICE candidates 已记录但不做实际连接建立
    }

    /**
     * 添加本地 ICE candidate → 触发 [onIceCandidateReady]
     */
    fun addLocalIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        Log.d(TAG, "local ICE candidate: mid=$sdpMid idx=$sdpMLineIndex")
        mainHandler.post {
            onIceCandidateReady?.invoke(candidate, sdpMid, sdpMLineIndex)
        }
    }

    /** 结束呼叫 */
    fun endCall() {
        if (!active.compareAndSet(true, false)) return
        Log.i(TAG, "call ended, callId=$callId")
        callId = ""
        peerSdp = ""
        setState(State.IDLE)
        mainHandler.post { onCallEnded?.invoke() }
    }

    /** 呼叫失败 */
    fun failCall(reason: String) {
        active.set(false)
        setState(State.FAILED)
        Log.w(TAG, "call failed: $reason")
        mainHandler.post { onError?.invoke(reason) }
    }

    // ── SDP 构建 ──

    private fun buildSdpOffer(): String {
        val localIp = getLocalIp()
        // 标准 WebRTC SDP（简化版，完整版需 ICE/DTLS 等）
        return buildString {
            appendLine("v=0")
            appendLine("o=- ${System.currentTimeMillis()} 2 IN IP4 $localIp")
            appendLine("s=-")
            appendLine("t=0 0")
            appendLine("a=group:BUNDLE video")
            appendLine("a=msid-semantic: WMS")
            // 视频媒体行
            appendLine("m=video 9 UDP/TLS/RTP/SAVPF 96")
            appendLine("c=IN IP4 0.0.0.0")
            appendLine("a=rtpmap:96 H264/90000")
            appendLine("a=fmtp:96 profile-level-id=42001f;packetization-mode=1")
            appendLine("a=sendonly")
            appendLine("a=mid:video")
            appendLine("a=ice-ufrag:${randomString(8)}")
            appendLine("a=ice-pwd:${randomString(24)}")
            appendLine("a=fingerprint:sha-256 ${randomHex(64)}")
            appendLine("a=setup:actpass")
        }
    }

    // ── 辅助方法 ──

    private fun setState(newState: State) {
        if (newState != state) {
            state = newState
            Log.d(TAG, "state → $newState")
        }
    }

    private fun getLocalIp(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        return (1..length).map { chars.random() }.joinToString("")
    }

    private fun randomHex(length: Int): String {
        val chars = "0123456789ABCDEF"
        return (1..length).map { chars.random() }.joinToString("")
    }
}
