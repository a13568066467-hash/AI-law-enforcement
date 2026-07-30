package com.aifieldcam.app.platform

import android.util.Log
import com.aifieldcam.app.data.MqttConfig
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.platform.commandcall.CommandCallSignalParser
import org.json.JSONObject

/**
 * MQTT Topic 路由。
 * - EMQX：`{prefix}/{deviceId}/start|end`
 * - 阿里云 IoT：`/sys/{pk}/{dn}/thing/service/command_call/start|end`
 */
object MqttTopicRouter {

    private const val TAG = "MqttRouter"

    private val legacyServiceTopics = listOf(
        "/thing/service/record",
        "/thing/service/scene",
        "/thing/service/broadcast",
        "/thing/service/webrtc/sdp/answer",
        "/thing/service/webrtc/ice/add",
        "/thing/service/webrtc/call/start",
        "/thing/service/webrtc/call/end",
        "/thing/service/command_call/start",
        "/thing/service/command_call/end",
    )

    fun onConnected(sessionManager: SessionManager) {
        val topics = subscribeTopics(sessionManager)
        for (topic in topics) {
            MqttClient.subscribe(topic, qos = 1)
        }
        MqttClient.addOnMessage { topic, payload ->
            dispatch(sessionManager, topic, payload)
        }
        Log.i(TAG, "router initialized, ${topics.size} topics subscribed")
    }

    fun onDisconnected() {
    }

    private fun subscribeTopics(session: SessionManager): List<String> {
        if (MqttConfig.isEmqx()) {
            val prefix = MqttConfig.topicPrefix().trim('/')
            val did = session.officerDeviceIdForMqtt().ifBlank {
                MqttConfig.clientIdOverride().ifBlank { MqttConfig.deviceName() }
            }
            if (did.isBlank()) {
                Log.w(TAG, "emqx subscribe skipped: empty device id")
                return emptyList()
            }
            return listOf("$prefix/$did/start", "$prefix/$did/end")
        }
        return legacyServiceTopics.map { buildAliyunTopic(it) }
    }

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
            topic.endsWith("/service/webrtc/sdp/answer") -> dispatchWebRtcSdpAnswer(session, json)
            topic.endsWith("/service/webrtc/ice/add") -> dispatchWebRtcIce(session, json)
            topic.endsWith("/service/webrtc/call/start") -> dispatchWebRtcCallStart(session, json)
            topic.endsWith("/service/webrtc/call/end") -> dispatchWebRtcCallEnd(session, json)
            topic.endsWith("/command_call/start") ||
                topic.endsWith("/service/command_call/start") ||
                topic.endsWith("/start") && topic.contains("command_call") ->
                dispatchCommandCallStart(session, json)
            topic.endsWith("/command_call/end") ||
                topic.endsWith("/service/command_call/end") ||
                topic.endsWith("/end") && topic.contains("command_call") ->
                dispatchCommandCallEnd(session, json)
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
        val level = json.optString("level", "")
        Log.i(TAG, "broadcast: $title")
        session.handleBroadcast(title, body, level)
    }

    private fun dispatchWebRtcSdpAnswer(session: SessionManager, json: JSONObject) {
        val sdp = json.optString("sdp", "")
        if (sdp.isNotBlank()) session.onWebRtcSdpAnswer(sdp)
    }

    private fun dispatchWebRtcIce(session: SessionManager, json: JSONObject) {
        val candidate = json.optString("candidate", "")
        val sdpMid = json.optString("sdpMid", "")
        val sdpMLineIndex = json.optInt("sdpMLineIndex", 0)
        if (candidate.isNotBlank()) session.onWebRtcIceCandidate(candidate, sdpMid, sdpMLineIndex)
    }

    private fun dispatchWebRtcCallStart(session: SessionManager, json: JSONObject) {
        val caller = json.optString("caller", "web-console")
        val callId = json.optString("callId", System.currentTimeMillis().toString())
        session.onWebRtcCallStart(caller, callId)
    }

    private fun dispatchWebRtcCallEnd(session: SessionManager, json: JSONObject) {
        session.onWebRtcCallEnd()
    }

    private fun dispatchCommandCallStart(session: SessionManager, json: JSONObject) {
        session.handleCommandCallStartPayload(json)
    }

    private fun dispatchCommandCallEnd(session: SessionManager, json: JSONObject) {
        val action = json.optString("action", "")
        if (CommandCallSignalParser.isOccupyRoomEnd(action)) {
            Log.i(TAG, "occupy_room_end")
            session.onOccupyRoomEnd()
            return
        }
        val callId = CommandCallSignalParser.parseEndCallId(json)
        Log.i(TAG, "command_call end action=$action callId=$callId")
        session.onCommandCallEnd(callId)
    }

    private fun buildAliyunTopic(suffix: String): String {
        val dn = MqttConfig.deviceName()
        val pk = MqttConfig.productKey()
        return "/sys/$pk/$dn$suffix"
    }

    fun eventTopic(suffix: String): String = buildAliyunTopic(suffix)
}
