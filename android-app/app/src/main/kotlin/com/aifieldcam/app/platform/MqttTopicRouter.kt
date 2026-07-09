package com.aifieldcam.app.platform

import android.util.Log
import com.aifieldcam.app.data.SessionManager
import org.json.JSONObject

/**
 * 阿里云 IoT 自定义 Topic 路由。
 *
 * 订阅以下 topic（启动时自动注册）：
 * - /sys/{pk}/{dn}/thing/service/record    远程录/停/拍
 * - /sys/{pk}/{dn}/thing/service/scene     远程场景切换
 * - /sys/{pk}/{dn}/thing/service/broadcast 群播通知
 */
object MqttTopicRouter {

    private const val TAG = "MqttRouter"

    private val serviceTopics = listOf(
        "/thing/service/record",
        "/thing/service/scene",
        "/thing/service/broadcast",
        // V2 WebRTC 信令（下行）
        "/thing/service/webrtc/sdp/answer",
        "/thing/service/webrtc/ice/add",
        "/thing/service/webrtc/call/start",
        "/thing/service/webrtc/call/end",
    )

    /** 启动订阅：连接成功后调用一次 */
    fun onConnected(sessionManager: SessionManager) {
        for (suffix in serviceTopics) {
            val topic = buildTopic(suffix)
            MqttClient.subscribe(topic, qos = 1)
        }
        MqttClient.addOnMessage { topic, payload ->
            dispatch(sessionManager, topic, payload)
        }
        Log.i(TAG, "router initialized, ${serviceTopics.size} topics subscribed")
    }

    /** 断开连接时取消回调 */
    fun onDisconnected() {
        // 回调在 MqttClient.disconnect() 时自然失效（Paho client 关闭后不再回调）
    }

    // ── 消息分发 ──

    private fun dispatch(session: SessionManager, topic: String, payload: String) {
        val json = try {
            JSONObject(payload)
        } catch (e: Exception) {
            Log.w(TAG, "invalid payload for $topic: ${e.message}")
            return
        }
        when {
            topic.endsWith("/service/record") -> dispatchRecord(session, json)
            topic.endsWith("/service/scene") -> dispatchScene(session, json)
            topic.endsWith("/service/broadcast") -> dispatchBroadcast(session, json)
            // V2 WebRTC 信令
            topic.endsWith("/service/webrtc/sdp/answer") -> dispatchWebRtcSdpAnswer(session, json)
            topic.endsWith("/service/webrtc/ice/add") -> dispatchWebRtcIce(session, json)
            topic.endsWith("/service/webrtc/call/start") -> dispatchWebRtcCallStart(session, json)
            topic.endsWith("/service/webrtc/call/end") -> dispatchWebRtcCallEnd(session, json)
            else -> Log.w(TAG, "unhandled topic: $topic")
        }
    }

    private fun dispatchRecord(session: SessionManager, json: JSONObject) {
        val action = json.optString("action", "")
        Log.i(TAG, "remote record cmd: $action")
        when (action) {
            "start" -> session.startRecord()
            "stop" -> session.stopRecord()
            "capture" -> session.triggerCapture()
            else -> Log.w(TAG, "unknown record action: $action")
        }
    }

    private fun dispatchScene(session: SessionManager, json: JSONObject) {
        val sceneId = json.optString("scene_id", "")
        if (sceneId.isBlank()) {
            Log.w(TAG, "empty scene_id")
            return
        }
        Log.i(TAG, "remote scene: $sceneId")
        session.runDemoScenario(sceneId) { _, err ->
            if (err.isNotBlank()) Log.w(TAG, "remote scene failed: $err")
        }
    }

    private fun dispatchBroadcast(session: SessionManager, json: JSONObject) {
        val title = json.optString("title", "")
        val body = json.optString("body", "")
        val level = json.optString("level", "info")
        Log.i(TAG, "broadcast level=$level: $title")
        session.handleBroadcast(title, body, level)
    }

    // ── V2 WebRTC 信令分发 ──

    private fun dispatchWebRtcSdpAnswer(session: SessionManager, json: JSONObject) {
        val sdp = json.optString("sdp", "")
        val type = json.optString("type", "answer")
        if (sdp.isBlank()) {
            Log.w(TAG, "empty WebRTC SDP answer")
            return
        }
        Log.i(TAG, "WebRTC SDP answer (type=$type)")
        session.onWebRtcSdpAnswer(sdp)
    }

    private fun dispatchWebRtcIce(session: SessionManager, json: JSONObject) {
        val candidate = json.optString("candidate", "")
        val sdpMid = json.optString("sdpMid", "")
        val sdpMLineIndex = json.optInt("sdpMLineIndex", 0)
        if (candidate.isBlank()) {
            Log.w(TAG, "empty WebRTC ICE candidate")
            return
        }
        Log.d(TAG, "WebRTC ICE: mid=$sdpMid idx=$sdpMLineIndex")
        session.onWebRtcIceCandidate(candidate, sdpMid, sdpMLineIndex)
    }

    private fun dispatchWebRtcCallStart(session: SessionManager, json: JSONObject) {
        val caller = json.optString("caller", "web-console")
        val callId = json.optString("callId", System.currentTimeMillis().toString())
        Log.i(TAG, "WebRTC call start from $caller, callId=$callId")
        session.onWebRtcCallStart(caller, callId)
    }

    private fun dispatchWebRtcCallEnd(session: SessionManager, json: JSONObject) {
        Log.i(TAG, "WebRTC call end")
        session.onWebRtcCallEnd()
    }

    // ── Topic 构建 ──

    private fun buildTopic(suffix: String): String {
        val dn = com.aifieldcam.app.data.MqttConfig.deviceName()
        val pk = com.aifieldcam.app.data.MqttConfig.productKey()
        return "/sys/$pk/$dn$suffix"
    }

    /** Publish topic 构建（设备→云端） */
    fun eventTopic(suffix: String): String = buildTopic(suffix)
}