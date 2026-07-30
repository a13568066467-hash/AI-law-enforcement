package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aifieldcam.app.data.MqttConfig
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 阿里云 IoT MQTT 连接生命周期管理。
 *
 * 特性：
 * - 阿里云 IoT 签名认证（HMAC-SHA256）
 * - 自动重连（指数退避 5s → 15s → 45s → cap 60s）
 * - keepalive 60s
 * - 纯 Paho MqttClient（不依赖 Android Service）
 */
object MqttClient {

    private const val TAG = "MqttClient"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var scheduler = Executors.newSingleThreadScheduledExecutor()

    private var client: org.eclipse.paho.client.mqttv3.MqttClient? = null
    private var connectOptions: MqttConnectOptions? = null
    private val connected = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)
    private var reconnectFuture: ScheduledFuture<*>? = null

    private val connectCallbacks = CopyOnWriteArrayList<() -> Unit>()
    private val disconnectCallbacks = CopyOnWriteArrayList<(Throwable?) -> Unit>()
    private val messageCallbacks = CopyOnWriteArrayList<(String, String) -> Unit>()

    // ── 公开接口 ──

    fun isConnected(): Boolean = connected.get()

    fun addOnConnected(cb: () -> Unit) { connectCallbacks.add(cb) }
    fun removeOnConnected(cb: () -> Unit) { connectCallbacks.remove(cb) }

    fun addOnDisconnected(cb: (Throwable?) -> Unit) { disconnectCallbacks.add(cb) }
    fun removeOnDisconnected(cb: (Throwable?) -> Unit) { disconnectCallbacks.remove(cb) }

    fun addOnMessage(cb: (topic: String, payload: String) -> Unit) { messageCallbacks.add(cb) }
    fun removeOnMessage(cb: (topic: String, payload: String) -> Unit) { messageCallbacks.remove(cb) }

    /** 建立 MQTT 连接 */
    fun connect() {
        if (!MqttConfig.isEnabled()) {
            Log.d(TAG, "mqtt disabled, skip connect")
            return
        }
        if (!MqttConfig.isConfigured()) {
            Log.w(TAG, "mqtt not configured, skip connect")
            return
        }
        if (connected.get() || connecting.get()) {
            Log.d(TAG, "already connected or connecting")
            return
        }
        executor.execute { doConnect() }
    }

    /** 断开 MQTT 连接 */
    fun disconnect() {
        cancelReconnect()
        executor.execute {
            val c = client
            if (c != null && c.isConnected) {
                try {
                    c.disconnect(3_000)
                } catch (_: Exception) {}
            }
            try { c?.close(true) } catch (_: Exception) {}
            client = null
            connected.set(false)
            connecting.set(false)
            Log.i(TAG, "disconnected")
        }
    }

    /** 发布消息（QoS 1 默认） */
    fun publish(topic: String, payload: String, qos: Int = 1) {
        if (!connected.get()) {
            Log.w(TAG, "not connected, skip publish to $topic")
            return
        }
        executor.execute {
            try {
                val msg = MqttMessage(payload.toByteArray(Charsets.UTF_8))
                msg.qos = qos
                client?.publish(topic, msg)
                Log.d(TAG, "published to $topic")
            } catch (e: MqttException) {
                Log.w(TAG, "publish failed to $topic: ${e.message}")
            }
        }
    }

    /** 订阅主题 */
    fun subscribe(topic: String, qos: Int = 1) {
        if (!connected.get()) {
            Log.w(TAG, "not connected, skip subscribe $topic")
            return
        }
        executor.execute {
            try {
                client?.subscribe(topic, qos)
                Log.i(TAG, "subscribed: $topic")
            } catch (e: MqttException) {
                Log.w(TAG, "subscribe failed $topic: ${e.message}")
            }
        }
    }

    // ── 内部实现 ──

    private fun doConnect() {
        if (connecting.get() || connected.get()) return
        connecting.set(true)
        val serverUri = MqttConfig.brokerUri()
        if (serverUri.isBlank()) {
            connecting.set(false)
            Log.w(TAG, "empty broker uri")
            return
        }
        try {
            val pahoId = MqttConfig.generatePahoClientId()
            if (client == null || !client!!.isConnected) {
                try { client?.close(true) } catch (_: Exception) {}
                client = org.eclipse.paho.client.mqttv3.MqttClient(serverUri, pahoId)
            }
            val c = client ?: run { connecting.set(false); return }

            val opts = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 60
                if (MqttConfig.isEmqx()) {
                    userName = MqttConfig.mqttUsername()
                    password = MqttConfig.password().toCharArray()
                } else {
                    val signedId = MqttConfig.generateClientId()
                    userName = MqttConfig.mqttUsername()
                    password = MqttConfig.generateMqttPassword(signedId).toCharArray()
                }
            }
            connectOptions = opts

            c.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "connection lost: ${cause?.message}")
                    connected.set(false)
                    mainHandler.post { disconnectCallbacks.forEach { it(cause) } }
                    scheduleReconnect()
                }

                override fun messageArrived(topic: String?, msg: MqttMessage?) {
                    if (topic == null || msg == null) return
                    val payload = String(msg.payload, Charsets.UTF_8)
                    Log.d(TAG, "message arrived: $topic")
                    mainHandler.post { messageCallbacks.forEach { it(topic, payload) } }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            c.connect(opts)
            connected.set(true)
            connecting.set(false)
            reconnectAttempt.set(0)
            cancelReconnect()
            Log.i(TAG, "connected to $serverUri (attempt ${reconnectAttempt.get()})")
            mainHandler.post { connectCallbacks.forEach { it() } }
        } catch (e: MqttException) {
            connected.set(false)
            connecting.set(false)
            Log.w(TAG, "connect failed: ${e.message} (attempt ${reconnectAttempt.get()})")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!MqttConfig.isEnabled()) return
        val attempt = reconnectAttempt.incrementAndGet()
        // 指数退避: 5s, 15s, 45s, cap at 60s
        val delaySec = minOf(5L * pow3(attempt - 1), 60L)
        Log.i(TAG, "schedule reconnect in ${delaySec}s (attempt $attempt)")
        cancelReconnect()
        reconnectFuture = scheduler.schedule({
            executor.execute { doConnect() }
        }, delaySec, TimeUnit.SECONDS)
    }

    private fun cancelReconnect() {
        reconnectFuture?.cancel(false)
        reconnectFuture = null
    }

    private fun pow3(n: Int): Long = if (n <= 0) 1 else 3L * pow3(n - 1)

    fun shutdown() {
        disconnect()
        try { scheduler.shutdownNow() } catch (_: Exception) {}
        scheduler = Executors.newSingleThreadScheduledExecutor()
    }
}