package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * GB28181 轻量 SIP UA（纯 Kotlin，无需 PJSIP 等 native 库）。
 *
 * 支持：
 *   - REGISTER（MD5 摘要认证）
 *   - INVITE 接受（200 OK + SDP 应答）
 *   - BYE 处理
 *   - 自动重注册（默认 300s）
 *
 * 协议：SIP over UDP，GB/T 28181 国标
 *
 * 用法：
 *   1. configure(serverIp, port, deviceId, password)
 *   2. register()
 *   3. （等待 INVITE 或主动推流）
 *   4. unregister()
 */
object SipUaClient {

    private const val TAG = "SipUa"
    private const val SIP_PORT_DEFAULT = 5060
    private const val RE_REGISTER_INTERVAL_SEC = 300L
    private const val RESPONSE_TIMEOUT_MS = 5_000L

    // ── 配置 ──

    private var serverIp: String = ""
    private var serverPort: Int = SIP_PORT_DEFAULT
    private var deviceId: String = ""
    private var password: String = ""
    private var localVideoPort: Int = 20000
    private var localAudioPort: Int = 20002

    // ── 状态 ──

    private val registered = AtomicBoolean(false)
    private val streaming = AtomicBoolean(false)

    private var udpSocket: DatagramSocket? = null
    private var registerThread: Thread? = null
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var callId: String = ""
    @Volatile
    private var localTag: String = ""
    @Volatile
    private var remoteTag: String = ""
    @Volatile
    private var cSeq = AtomicInteger(1)

    /** 远端 RTP 视频端口（从 INVITE SDP 中解析） */
    @Volatile
    var remoteVideoPort: Int = 0
        private set

    /** 远端 RTP 地址 */
    @Volatile
    var remoteVideoIp: String = ""
        private set

    // ── 回调 ──

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var onRegistered: (() -> Unit)? = null

    @Volatile
    var onUnregistered: (() -> Unit)? = null

    @Volatile
    var onIncomingCall: ((remoteIp: String, remoteVideoPort: Int, remoteAudioPort: Int, sdp: String) -> Unit)? = null

    @Volatile
    var onCallEnded: (() -> Unit)? = null

    @Volatile
    var onError: ((String) -> Unit)? = null

    // ── 公开接口 ──

    fun isRegistered(): Boolean = registered.get()
    fun isStreaming(): Boolean = streaming.get()

    fun configure(
        serverIp: String,
        serverPort: Int = SIP_PORT_DEFAULT,
        deviceId: String,
        password: String,
        localVideoPort: Int = 20000,
        localAudioPort: Int = 20002,
    ) {
        this.serverIp = serverIp
        this.serverPort = serverPort
        this.deviceId = deviceId
        this.password = password
        this.localVideoPort = localVideoPort
        this.localAudioPort = localAudioPort
        Log.i(TAG, "configured: sip:$deviceId@$serverIp:$serverPort")
    }

    fun register() {
        if (registered.get()) return
        if (serverIp.isEmpty()) {
            onError?.invoke("SIP 服务器未配置")
            return
        }
        ensureSocket()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        // 立即注册 + 定期重注册
        doRegister()
        scheduler?.scheduleAtFixedRate(
            { doRegister() },
            RE_REGISTER_INTERVAL_SEC,
            RE_REGISTER_INTERVAL_SEC,
            TimeUnit.SECONDS,
        )
    }

    fun unregister() {
        scheduler?.shutdownNow()
        scheduler = null
        stopStreaming()
        doUnregister()
        closeSocket()
        registered.set(false)
    }

    /** 开始 RTP 推流（VideoStreamManager 调用） */
    fun startStreaming() {
        streaming.set(true)
        Log.i(TAG, "RTP streaming active → video=${serverIp}:$remoteVideoPort")
    }

    fun stopStreaming() {
        if (!streaming.compareAndSet(true, false)) return
        sendBye()
        Log.i(TAG, "RTP streaming stopped")
    }

    // ── SIP 注册 ──

    private fun doRegister() {
        try {
            val seq = cSeq.incrementAndGet()
            val callId = generateCallId()
            this.callId = callId
            this.localTag = generateTag()

            val request = buildRegisterRequest(seq, callId, localTag!!)
            sendSipMessage(request)

            // 等待 401 或 200
            val response = receiveSipResponse()

            if (response != null && response.contains("401")) {
                // 摘要认证
                val realm = extractHeader(response, "realm=", "\"")
                val nonce = extractHeader(response, "nonce=\"", "\"")
                if (realm.isNotEmpty() && nonce.isNotEmpty()) {
                    val authSeq = cSeq.incrementAndGet()
                    val authRequest = buildRegisterWithAuth(authSeq, callId, localTag!!, realm, nonce)
                    sendSipMessage(authRequest)

                    val authResponse = receiveSipResponse()
                    if (authResponse != null && authResponse.contains("200")) {
                        registered.set(true)
                        Log.i(TAG, "REGISTER success → $deviceId")
                        mainHandler.post { onRegistered?.invoke() }
                    } else {
                        Log.w(TAG, "REGISTER auth failed: ${authResponse?.take(200)}")
                        mainHandler.post { onError?.invoke("SIP 注册认证失败") }
                    }
                }
            } else if (response != null && response.contains("200")) {
                // 无认证直接成功
                registered.set(true)
                Log.i(TAG, "REGISTER success (no auth) → $deviceId")
                mainHandler.post { onRegistered?.invoke() }
            } else {
                Log.w(TAG, "REGISTER failed: ${response?.take(200)}")
                mainHandler.post { onError?.invoke("SIP 注册失败") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "REGISTER error: ${e.message}")
            mainHandler.post { onError?.invoke("SIP 注册异常: ${e.message}") }
        }
    }

    private fun doUnregister() {
        try {
            if (serverIp.isEmpty() || callId.isEmpty()) return
            val seq = cSeq.incrementAndGet()
            val request = buildUnregisterRequest(seq, callId, localTag)
            sendSipMessage(request)
            Log.i(TAG, "UNREGISTER sent")
        } catch (e: Exception) {
            Log.w(TAG, "UNREGISTER error: ${e.message}")
        }
    }

    // ── SIP 请求构建 ──

    private fun buildRegisterRequest(seq: Int, cId: String, tag: String): String {
        val contact = "sip:${deviceId}@${getLocalIp()}:$serverPort"
        return buildString {
            appendLine("REGISTER sip:${deviceId}@${serverIp}:${serverPort ?: SIP_PORT_DEFAULT} SIP/2.0")
            appendLine("Via: SIP/2.0/UDP ${getLocalIp()}:${localPort()};rport;branch=z9hG4bK${generateBranch()}")
            appendLine("From: <sip:${deviceId}@${serverIp}>;tag=$tag")
            appendLine("To: <sip:${deviceId}@${serverIp}>")
            appendLine("Call-ID: $cId")
            appendLine("CSeq: $seq REGISTER")
            appendLine("Contact: <$contact>;expires=3600")
            appendLine("Max-Forwards: 70")
            appendLine("User-Agent: 赢筑AI-DSJ-ZECN6A1/1.0")
            appendLine("Content-Length: 0")
            appendLine()
        }
    }

    private fun buildRegisterWithAuth(
        seq: Int,
        cId: String,
        tag: String,
        realm: String,
        nonce: String,
    ): String {
        val contact = "sip:${deviceId}@${getLocalIp()}:$serverPort"
        val uri = "sip:${deviceId}@${serverIp}"
        val ha1 = md5("${deviceId}:$realm:$password")
        val ha2 = md5("REGISTER:$uri")
        val response = md5("$ha1:$nonce:$ha2")

        return buildString {
            appendLine("REGISTER $uri SIP/2.0")
            appendLine("Via: SIP/2.0/UDP ${getLocalIp()}:${localPort()};rport;branch=z9hG4bK${generateBranch()}")
            appendLine("From: <sip:${deviceId}@${serverIp}>;tag=$tag")
            appendLine("To: <sip:${deviceId}@${serverIp}>")
            appendLine("Call-ID: $cId")
            appendLine("CSeq: $seq REGISTER")
            appendLine("Contact: <$contact>;expires=3600")
            appendLine("Authorization: Digest username=\"${deviceId}\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\", algorithm=MD5")
            appendLine("Max-Forwards: 70")
            appendLine("User-Agent: 赢筑AI-DSJ-ZECN6A1/1.0")
            appendLine("Content-Length: 0")
            appendLine()
        }
    }

    private fun buildUnregisterRequest(seq: Int, cId: String, tag: String): String {
        val contact = "sip:${deviceId}@${getLocalIp()}:$serverPort"
        return buildString {
            appendLine("REGISTER sip:${deviceId}@${serverIp} SIP/2.0")
            appendLine("Via: SIP/2.0/UDP ${getLocalIp()}:${localPort()};rport;branch=z9hG4bK${generateBranch()}")
            appendLine("From: <sip:${deviceId}@${serverIp}>;tag=$tag")
            appendLine("To: <sip:${deviceId}@${serverIp}>")
            appendLine("Call-ID: $cId")
            appendLine("CSeq: $seq REGISTER")
            appendLine("Contact: <$contact>;expires=0")
            appendLine("Max-Forwards: 70")
            appendLine("Content-Length: 0")
            appendLine()
        }
    }

    private fun buildInviteResponse(cId: String, remoteTagStr: String, localTagStr: String, seq: Int): String {
        val sdp = buildSdp(localVideoPort, localAudioPort)
        return buildString {
            appendLine("SIP/2.0 200 OK")
            appendLine("Via: SIP/2.0/UDP ${getLocalIp()}:${localPort()};rport;branch=z9hG4bK${generateBranch()}")
            appendLine("From: <sip:${deviceId}@${serverIp}>;tag=$remoteTagStr")
            appendLine("To: <sip:${deviceId}@${serverIp}>;tag=$localTagStr")
            appendLine("Call-ID: $cId")
            appendLine("CSeq: $seq INVITE")
            appendLine("Contact: <sip:${deviceId}@${getLocalIp()}:${serverPort}>")
            appendLine("User-Agent: 赢筑AI-DSJ-ZECN6A1/1.0")
            appendLine("Content-Type: application/sdp")
            appendLine("Content-Length: ${sdp.length}")
            appendLine()
            append(sdp)
        }
    }

    private fun sendBye() {
        if (callId.isEmpty()) return
        try {
            val seq = cSeq.incrementAndGet()
            val request = buildString {
                appendLine("BYE sip:${deviceId}@${serverIp} SIP/2.0")
                appendLine("Via: SIP/2.0/UDP ${getLocalIp()}:${localPort()};rport;branch=z9hG4bK${generateBranch()}")
                appendLine("From: <sip:${deviceId}@${serverIp}>;tag=$localTag")
                appendLine("To: <sip:${deviceId}@${serverIp}>;tag=$remoteTag")
                appendLine("Call-ID: $callId")
                appendLine("CSeq: $seq BYE")
                appendLine("Max-Forwards: 70")
                appendLine("Content-Length: 0")
                appendLine()
            }
            sendSipMessage(request)
        } catch (e: Exception) {
            Log.w(TAG, "BYE error: ${e.message}")
        }
    }

    // ── SDP 构建（GB28181 标准） ──

    private fun buildSdp(videoPort: Int, audioPort: Int): String {
        return buildString {
            appendLine("v=0")
            appendLine("o=${deviceId} 0 0 IN IP4 ${getLocalIp()}")
            appendLine("s=Play")
            appendLine("c=IN IP4 ${getLocalIp()}")
            appendLine("t=0 0")
            // 视频
            appendLine("m=video $videoPort RTP/AVP 96")
            appendLine("a=rtpmap:96 H264/90000")
            appendLine("a=fmtp:96 profile-level-id=42001f;packetization-mode=1")
            appendLine("a=recvonly")
            // 音频
            appendLine("m=audio $audioPort RTP/AVP 8")
            appendLine("a=rtpmap:8 PCMA/8000")
            appendLine("a=recvonly")
        }
    }

    // ── SDP 解析 ──

    private fun parseSdpVideoPort(sdp: String): Int {
        for (line in sdp.lines()) {
            if (line.startsWith("m=video")) {
                val parts = line.split(" ")
                if (parts.size >= 2) return parts[1].toIntOrNull() ?: 0
            }
        }
        return 0
    }

    private fun parseSdpConnection(sdp: String): String {
        for (line in sdp.lines()) {
            if (line.startsWith("c=")) {
                val parts = line.removePrefix("c=").trim().split(" ")
                if (parts.size >= 2) return parts[1]
            }
        }
        return serverIp
    }

    // ── SIP 消息接收与分发 ──

    /**
     * 启动 SIP 消息监听线程（在 register 后调用）。
     * 持续监听 UDP 端口，解析 INVITE/BYE 等请求。
     */
    fun startListening() {
        if (registerThread?.isAlive == true) return
        registerThread = Thread({
            val buffer = ByteArray(4096)
            while (registered.get() || isStreaming()) {
                try {
                    val socket = udpSocket ?: break
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.soTimeout = 2000 // 2s 超时，可中断
                    try {
                        socket.receive(packet)
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    }
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)

                    if (message.startsWith("INVITE")) {
                        handleIncomingInvite(message, packet.address.hostAddress ?: serverIp)
                    } else if (message.startsWith("BYE")) {
                        handleIncomingBye(message)
                    } else if (message.startsWith("SIP/2.0 200")) {
                        // ACK response — handled by receive flow
                    }
                } catch (e: IOException) {
                    if (!registered.get()) break
                    Log.w(TAG, "SIP listen error: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "SIP listen fatal: ${e.message}")
                    break
                }
            }
            Log.d(TAG, "SIP listener stopped")
        }, "SipUa-Listen").apply { isDaemon = true; start() }
        Log.i(TAG, "SIP listener started")
    }

    private fun handleIncomingInvite(message: String, remoteIp: String) {
        val cId = extractSipHeader(message, "Call-ID:")
        val rTag = extractSipHeader(message, "From:").let { extractHeader(it, "tag=", null) }
        remoteTag = rTag
        callId = cId
        this.remoteVideoIp = remoteIp

        // 解析 SDP（在消息体中）
        val sdpStart = message.indexOf("v=0")
        val sdp = if (sdpStart >= 0) message.substring(sdpStart) else ""
        val videoPort = parseSdpVideoPort(sdp)
        if (videoPort > 0) {
            remoteVideoPort = videoPort
        }

        val cSeqStr = extractSipHeader(message, "CSeq:")
        val seq = cSeqStr.split(" ").firstOrNull()?.toIntOrNull() ?: 1

        Log.i(TAG, "INVITE received from $remoteIp, video=$videoPort, call-id=$cId")

        // 应答 200 OK
        val localTagStr = generateTag()
        this.localTag = localTagStr
        val response = buildInviteResponse(cId, rTag, localTagStr, seq)
        sendSipMessage(response)

        mainHandler.post {
            onIncomingCall?.invoke(remoteIp, videoPort, 0, sdp)
        }
    }

    private fun handleIncomingBye(message: String) {
        Log.i(TAG, "BYE received")
        streaming.set(false)
        mainHandler.post { onCallEnded?.invoke() }
    }

    // ── UDP 通信 ──

    private fun sendSipMessage(message: String) {
        try {
            val data = message.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                data, data.size,
                InetAddress.getByName(serverIp),
                serverPort,
            )
            udpSocket?.send(packet)
        } catch (e: Exception) {
            Log.w(TAG, "SIP send failed: ${e.message}")
        }
    }

    private fun receiveSipResponse(): String? {
        val buffer = ByteArray(4096)
        val socket = udpSocket ?: return null
        val deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS
        socket.soTimeout = 500
        while (System.currentTimeMillis() < deadline) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                return String(packet.data, 0, packet.length, Charsets.UTF_8)
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    private fun ensureSocket() {
        if (udpSocket?.isClosed != false) {
            udpSocket = DatagramSocket()
            udpSocket?.soTimeout = 2000
            Log.i(TAG, "SIP UDP socket bound to ${localPort()}")
        }
    }

    private fun closeSocket() {
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
    }

    private fun localPort(): Int = udpSocket?.localPort ?: 0

    // ── 辅助方法 ──

    private fun generateCallId(): String = "${System.currentTimeMillis()}@${getLocalIp()}"
    private fun generateTag(): String = Integer.toHexString((Math.random() * Int.MAX_VALUE).toInt())
    private fun generateBranch(): String = "z9hG4bK-${System.nanoTime()}"

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

    private fun extractHeader(message: String, startDelim: String, endDelim: String?): String {
        val start = message.indexOf(startDelim)
        if (start < 0) return ""
        val from = start + startDelim.length
        val end = if (endDelim != null) {
            val e = message.indexOf(endDelim, from)
            if (e < 0) message.length else e
        } else {
            // 到行尾或 ; 或 >
            val e1 = message.indexOf("\r", from)
            val e2 = message.indexOf("\n", from)
            val e3 = message.indexOf(";", from)
            val e4 = message.indexOf(">", from)
            val candidates = listOfNotNull(
                if (e1 >= 0) e1 else null,
                if (e2 >= 0) e2 else null,
                if (e3 >= 0) e3 else null,
                if (e4 >= 0) e4 else null,
            )
            if (candidates.isNotEmpty()) candidates.min() else message.length
        }
        return message.substring(from, end.coerceAtMost(message.length)).trim()
    }

    private fun extractSipHeader(message: String, header: String): String {
        val start = message.indexOf(header)
        if (start < 0) return ""
        val from = start + header.length
        val end1 = message.indexOf("\r\n", from)
        val end2 = message.indexOf('\n', from)
        val end = when {
            end1 >= 0 && end2 >= 0 -> minOf(end1, end2)
            end1 >= 0 -> end1
            end2 >= 0 -> end2
            else -> message.length
        }
        return message.substring(from, end).trim()
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
