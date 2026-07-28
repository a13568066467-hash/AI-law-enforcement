package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class RealtimeVoiceClient(
    private val listener: Listener,
) {
    data class Config(
        val baseUrl: String,
        val token: String,
        val sessionId: String,
        val deviceId: String,
    )

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onEvent(event: RealtimeVoiceEvent)
        fun onAudio(pcm24k: ByteArray)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null
    private var config: Config? = null
    private var requested = false
    private var keepAlive = false
    private var reconnects = 0
    private var generation = 0L
    private var pendingReconnect: Runnable? = null

    fun connect(config: Config, keepAlive: Boolean = false) {
        disconnect()
        this.config = config
        this.keepAlive = keepAlive
        requested = true
        reconnects = 0
        generation += 1
        open(generation)
    }

    fun isConnected(): Boolean = socket != null

    fun isKeepAlive(): Boolean = keepAlive && requested

    fun sendAudio(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size): Boolean {
        if (length <= 0 || offset < 0 || offset + length > pcm.size) return false
        return socket?.send(pcm.toByteString(offset, length)) == true
    }

    fun sendImage(jpeg: ByteArray): Boolean {
        if (jpeg.isEmpty()) return false
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        return socket?.send(RealtimeVoiceProtocol.image(b64)) == true
    }

    fun commit(): Boolean = socket?.send(RealtimeVoiceProtocol.commit()) == true

    fun cancelResponse(): Boolean = socket?.send(RealtimeVoiceProtocol.cancel()) == true

    fun sendToolResult(callId: String, output: JSONObject): Boolean =
        socket?.send(RealtimeVoiceProtocol.toolResult(callId, output)) == true

    fun disconnect() {
        requested = false
        keepAlive = false
        generation += 1
        cancelPendingReconnect()
        val current = socket
        socket = null
        current?.close(1000, "client disconnect")
        notifyState(ConnectionState.DISCONNECTED)
    }

    private fun cancelPendingReconnect() {
        pendingReconnect?.let { mainHandler.removeCallbacks(it) }
        pendingReconnect = null
    }

    private fun open(expectedGeneration: Long) {
        val currentConfig = config ?: return
        if (!requested || expectedGeneration != generation) return
        notifyState(ConnectionState.CONNECTING)
        val wsBase = currentConfig.baseUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val request = Request.Builder()
            .url("$wsBase/v1/realtime/voice")
            .header("Authorization", "Bearer ${currentConfig.token}")
            .build()
        http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!requested || expectedGeneration != generation) {
                        webSocket.close(1000, "stale connection")
                        return
                    }
                    socket = webSocket
                    reconnects = 0
                    webSocket.send(
                        RealtimeVoiceProtocol.sessionStart(
                            currentConfig.sessionId,
                            currentConfig.deviceId,
                        ),
                    )
                    notifyState(ConnectionState.CONNECTED)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = try {
                        RealtimeVoiceProtocol.parseServerText(text)
                    } catch (e: Exception) {
                        RealtimeVoiceEvent.Error(
                            "invalid_server_event",
                            e.message ?: "实时语音消息格式错误",
                        )
                    }
                    mainHandler.post { listener.onEvent(event) }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val audio = bytes.toByteArray()
                    mainHandler.post { listener.onAudio(audio) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket === webSocket) socket = null
                    notifyState(ConnectionState.DISCONNECTED)
                    reconnectOrReport(expectedGeneration)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (socket === webSocket) socket = null
                    Log.w(TAG, "realtime socket failed: ${t.message}")
                    notifyState(ConnectionState.DISCONNECTED)
                    reconnectOrReport(expectedGeneration)
                }
            },
        )
    }

    private fun reconnectOrReport(expectedGeneration: Long) {
        if (!requested || expectedGeneration != generation) return
        reconnects += 1
        if (RealtimeReconnectPolicy.shouldReportFailure(keepAlive, reconnects)) {
            mainHandler.post {
                listener.onEvent(
                    RealtimeVoiceEvent.Error(
                        "connection_failed",
                        "实时语音连接失败，请检查网络后重试",
                    ),
                )
            }
            return
        }
        val delay = RealtimeReconnectPolicy.delayMs(reconnects - 1)
        Log.i(TAG, "realtime reconnect in ${delay}ms (attempt=$reconnects keepAlive=$keepAlive)")
        cancelPendingReconnect()
        val expected = expectedGeneration
        val task = Runnable { open(expected) }
        pendingReconnect = task
        mainHandler.postDelayed(task, delay)
    }

    private fun notifyState(state: ConnectionState) {
        mainHandler.post { listener.onConnectionStateChanged(state) }
    }

    companion object {
        private const val TAG = "RealtimeVoiceClient"
    }
}
