package com.aifieldcam.app.platform

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 统一视频推流管理 — 协调 GB28181 和 WebRTC 两个推流通道，
 * 从 [MediaEncoderPipeline] 的 NAL 队列分发给活跃通道。
 *
 * 状态机：
 *   NONE → GB28181 / WEBRTC / BOTH
 *
 * 用法：
 *   1. 录像开始后调用 [startGb28181] / [startWebRtc]
 *   2. 自动从 MediaEncoderPipeline.nalQueue 拉取 NAL 并分发给活跃通道
 *   3. 调用 [stopGb28181] / [stopWebRtc] 停止推流
 */
object VideoStreamManager {

    private const val TAG = "VideoStream"

    /** 推流模式 */
    enum class Mode { NONE, GB28181, WEBRTC, BOTH }

    /** NAL 消费者接口 — GB28181 和 WebRTC 模块各自实现 */
    interface StreamConsumer : Closeable {
        /** 通道名称（用于日志） */
        val name: String

        /** 接收一个 NAL 单元 */
        fun onNalUnit(nal: ByteArray)

        /** 开始推流 */
        fun start()

        /** 停止推流 */
        override fun close()
    }

    @Volatile
    var currentMode: Mode = Mode.NONE
        private set

    @Volatile
    var isGb28181Active: Boolean = false
        private set

    @Volatile
    var isWebRtcActive: Boolean = false
        private set

    private var gb28181Consumer: StreamConsumer? = null
    private var webRtcConsumer: StreamConsumer? = null

    /** NAL 分发线程 */
    private var dispatchThread: Thread? = null
    @Volatile
    private var dispatchRunning = false

    /** 分发到活跃通道的总 NAL 数（诊断用） */
    @Volatile
    var dispatchedNalCount: Long = 0
        private set

    /** 分发失败次数 */
    @Volatile
    var dispatchFailures: Long = 0
        private set

    // ── 回调 ──

    /** 推流状态变化回调（主线程） */
    @Volatile
    var onModeChanged: ((Mode) -> Unit)? = null

    /** 推流错误回调（主线程） */
    @Volatile
    var onError: ((String) -> Unit)? = null

    // ── 公开接口 ──

    /** 预览推流（HTTP JPEG），不改变 MediaRecorder 编码路径 */
    @Volatile
    private var previewActive = false

    fun markPreviewActive(active: Boolean) {
        previewActive = active
        if (!active && currentMode == Mode.NONE) {
            onModeChanged?.invoke(Mode.NONE)
        }
    }

    /**
     * 含 HTTP 预览 JPEG 的广义「在推流」。
     * **勿**用于 F6 PTT 归属：预览/监看 JPEG 不应抢走 AI 全双工。
     */
    fun isStreaming(): Boolean = isEncodedStreaming() || previewActive

    /** 仅 GB28181 / WebRTC 编码推流（不含 HTTP 预览）。用于 PTT 对讲归属。 */
    fun isEncodedStreaming(): Boolean = currentMode != Mode.NONE

    /**
     * 启动 GB28181 推流。
     * @param consumer 推流消费者（在 SIP UA 建立 INVITE 后传入）
     */
    fun startGb28181(consumer: StreamConsumer) {
        if (isGb28181Active) {
            Log.w(TAG, "GB28181 already active")
            return
        }
        gb28181Consumer = consumer
        try {
            consumer.start()
            isGb28181Active = true
            Log.i(TAG, "GB28181 streaming started: ${consumer.name}")
        } catch (e: Exception) {
            Log.e(TAG, "GB28181 start failed: ${e.message}")
            gb28181Consumer = null
            onError?.invoke("GB28181 推流启动失败: ${e.message}")
            return
        }
        updateMode()
        ensureDispatch()
    }

    /**
     * 启动 WebRTC 推流。
     * @param consumer 推流消费者（在 PeerConnection 建立后传入）
     */
    fun startWebRtc(consumer: StreamConsumer) {
        if (isWebRtcActive) {
            Log.w(TAG, "WebRTC already active")
            return
        }
        webRtcConsumer = consumer
        try {
            consumer.start()
            isWebRtcActive = true
            Log.i(TAG, "WebRTC streaming started: ${consumer.name}")
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC start failed: ${e.message}")
            webRtcConsumer = null
            onError?.invoke("WebRTC 推流启动失败: ${e.message}")
            return
        }
        updateMode()
        ensureDispatch()
    }

    /** 停止 GB28181 推流 */
    fun stopGb28181() {
        if (!isGb28181Active) return
        gb28181Consumer?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "GB28181 close error: ${e.message}") }
        }
        gb28181Consumer = null
        isGb28181Active = false
        Log.i(TAG, "GB28181 streaming stopped")
        updateMode()
    }

    /** 停止 WebRTC 推流 */
    fun stopWebRtc() {
        if (!isWebRtcActive) return
        webRtcConsumer?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "WebRTC close error: ${e.message}") }
        }
        webRtcConsumer = null
        isWebRtcActive = false
        Log.i(TAG, "WebRTC streaming stopped")
        updateMode()
    }

    /** 停止所有推流 */
    fun stopAll() {
        stopGb28181()
        stopWebRtc()
        stopDispatch()
    }

    // ── 内部实现 ──

    private fun updateMode() {
        val newMode = when {
            isGb28181Active && isWebRtcActive -> Mode.BOTH
            isGb28181Active -> Mode.GB28181
            isWebRtcActive -> Mode.WEBRTC
            else -> Mode.NONE
        }
        if (newMode != currentMode) {
            currentMode = newMode
            Log.i(TAG, "mode changed → $newMode")
            onModeChanged?.invoke(newMode)
        }
        // 无活跃通道时停止分发线程
        if (newMode == Mode.NONE) {
            stopDispatch()
        }
    }

    /** 确保 NAL 分发线程在运行 */
    private fun ensureDispatch() {
        if (dispatchThread?.isAlive == true && dispatchRunning) return
        stopDispatch()
        MediaEncoderPipeline.setNalRelayEnabled(true)
        dispatchRunning = true
        dispatchedNalCount = 0
        dispatchFailures = 0
        dispatchThread = Thread({
            Log.d(TAG, "NAL dispatch thread started")
            val queue = MediaEncoderPipeline.nalQueue
            // 缓冲区：收集多个 NAL 后批量分发给消费者
            val batch = mutableListOf<ByteArray>()
            while (dispatchRunning) {
                try {
                    // 批量取 NAL
                    batch.clear()
                    var nal: ByteArray? = queue.poll()
                    while (nal != null && batch.size < 30) { // 最多 30 帧/次（~1s @30fps）
                        batch.add(nal)
                        nal = queue.poll()
                    }

                    if (batch.isEmpty()) {
                        // 队列空，短暂等待
                        Thread.sleep(10)
                        continue
                    }

                    // 分发
                    val gb = gb28181Consumer.takeIf { isGb28181Active }
                    val wr = webRtcConsumer.takeIf { isWebRtcActive }
                    for (data in batch) {
                        try {
                            gb?.onNalUnit(data)
                            wr?.onNalUnit(data)
                            dispatchedNalCount++
                        } catch (e: Exception) {
                            dispatchFailures++
                            if (dispatchFailures % 50 == 1L) {
                                Log.w(TAG, "dispatch failure #$dispatchFailures: ${e.message}")
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    Log.d(TAG, "dispatch thread interrupted")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "dispatch error: ${e.message}")
                    dispatchFailures++
                }
            }
            Log.i(TAG, "NAL dispatch thread stopped (total=$dispatchedNalCount, failures=$dispatchFailures)")
        }, "VideoStream-Dispatch").apply { isDaemon = true; start() }
    }

    private fun stopDispatch() {
        dispatchRunning = false
        MediaEncoderPipeline.setNalRelayEnabled(false)
        dispatchThread?.interrupt()
        dispatchThread = null
    }

    // ── 便捷方法：创建 GB28181 NAL 消费者 ──

    /**
     * 创建一个将 NAL 单元封装为 RTP 包并通过 UDP 发送的消费者。
     * Phase 3 中由 SipUaClient 使用此方法创建。
     */
    fun createGb28181Consumer(
        remoteIp: String,
        remoteVideoPort: Int,
    ): StreamConsumer = object : StreamConsumer {
        override val name: String get() = "GB28181-RTP:$remoteIp:$remoteVideoPort"

        private var udpSocket: java.net.DatagramSocket? = null
        private var sequence = (Math.random() * 0xFFFF).toInt() and 0xFFFF
        private var timestamp = 0
        private val ssrc = (Math.random() * Int.MAX_VALUE).toInt()
        private var remoteAddr: java.net.InetAddress? = null

        override fun start() {
            udpSocket = java.net.DatagramSocket()
            remoteAddr = java.net.InetAddress.getByName(remoteIp)
            Log.i(TAG, "GB28181 RTP → $remoteIp:$remoteVideoPort")
        }

        override fun onNalUnit(nal: ByteArray) {
            val socket = udpSocket ?: return
            val addr = remoteAddr ?: return
            // 90kHz 时钟：约 30fps → 每帧 +3000
            timestamp = (timestamp + 3_000) and Int.MAX_VALUE
            val packets = RtpPacketizer.packetize(
                annexBNal = nal,
                sequence = sequence,
                timestamp = timestamp,
                ssrc = ssrc,
            )
            for (packet in packets) {
                try {
                    socket.send(
                        java.net.DatagramPacket(packet, packet.size, addr, remoteVideoPort),
                    )
                    sequence = (sequence + 1) and 0xFFFF
                } catch (_: Exception) {
                    // 外层统计 dispatchFailures
                }
            }
        }

        override fun close() {
            try {
                udpSocket?.close()
            } catch (_: Exception) {
            }
            udpSocket = null
        }
    }
}
