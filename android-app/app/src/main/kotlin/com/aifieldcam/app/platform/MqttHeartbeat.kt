package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * MQTT 定时心跳：每 60s 上报设备状态快照。
 * 在 [MqttClient] 连接成功后启动，断连时自动停止。
 */
object MqttHeartbeat {

    private const val TAG = "MqttHeartbeat"
    private const val INTERVAL_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private var sessionProvider: (() -> SessionState?)? = null

    data class SessionState(
        val batteryPct: Int,
        val isCharging: Boolean,
        val storageFreeMb: Long,
        val isRecording: Boolean,
        val isAudioRecording: Boolean,
        val gpsLat: Double,
        val gpsLng: Double,
        val signalStrength: Int,
    )

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!running || !MqttClient.isConnected()) return
            val state = sessionProvider?.invoke()
            if (state != null) {
                publish(state)
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    /** 注册状态提供者，启动心跳 */
    fun start(provider: () -> SessionState?) {
        sessionProvider = provider
        if (running) return
        running = true
        handler.post(heartbeatRunnable)
        Log.i(TAG, "heartbeat started, interval=${INTERVAL_MS}ms")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(heartbeatRunnable)
        Log.i(TAG, "heartbeat stopped")
    }

    private fun publish(state: SessionState) {
        val json = JSONObject().apply {
            put("battery_pct", state.batteryPct)
            put("is_charging", state.isCharging)
            put("storage_free_mb", state.storageFreeMb)
            put("is_recording", state.isRecording)
            put("is_audio_recording", state.isAudioRecording)
            put("gps_lat", state.gpsLat)
            put("gps_lng", state.gpsLng)
            put("signal_strength", state.signalStrength)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        val topic = MqttTopicRouter.eventTopic("/thing/event/heartbeat/post")
        MqttClient.publish(topic, json.toString(), qos = 1)
    }
}