package com.aifieldcam.app.platform.commandcall

import org.json.JSONObject

/**
 * 解析连线/监看/占用持房/任务房信令 JSON（MQTT / HTTP poll）为进房凭证。
 * 支持 snake_case 与 camelCase；action 区分 occupy_room / task_room_join / watch_start 等。
 */
object CommandCallSignalParser {

    enum class StartKind {
        OCCUPY_ROOM,
        TASK_ROOM,
        WATCH,
        CALL,
        UPGRADE,
    }

    data class StartSignal(
        val callId: String,
        val caller: String,
        val credentials: CommandCallCredentials,
        val kind: StartKind = StartKind.CALL,
        val pushVideo: Boolean = true,
    )

    fun parseStart(json: JSONObject): StartSignal? {
        val nested = json.optJSONObject("device")
        val roomId = firstNonBlank(json, "room_id", "roomId")
            ?: nested?.let { firstNonBlank(it, "room_id", "roomId") }
            ?: return null
        val userId = firstNonBlank(json, "user_id", "userId")
            ?: nested?.let { firstNonBlank(it, "user_id", "userId") }
            ?: return null
        val userSig = firstNonBlank(json, "user_sig", "userSig")
            ?: nested?.let { firstNonBlank(it, "user_sig", "userSig") }
            ?: return null
        val sdkAppId = when {
            json.has("sdk_app_id") -> json.optInt("sdk_app_id", 0)
            json.has("sdkAppId") -> json.optInt("sdkAppId", 0)
            nested != null && nested.has("sdk_app_id") -> nested.optInt("sdk_app_id", 0)
            nested != null && nested.has("sdkAppId") -> nested.optInt("sdkAppId", 0)
            else -> 0
        }
        if (sdkAppId <= 0) return null
        val caller = firstNonBlank(json, "caller") ?: "指挥中心"
        val action = json.optString("action", "").trim()
        val kind = when (action) {
            "occupy_room" -> StartKind.OCCUPY_ROOM
            "task_room_join" -> StartKind.TASK_ROOM
            "watch_start" -> StartKind.WATCH
            "call_upgrade" -> StartKind.UPGRADE
            else -> StartKind.CALL
        }
        val callId = firstNonBlank(json, "call_id", "callId", "task_room_id", "taskRoomId")
            ?: if (kind == StartKind.OCCUPY_ROOM || kind == StartKind.TASK_ROOM) roomId else null
            ?: return null
        val pushVideo = when {
            json.has("push_video") -> json.optBoolean("push_video", true)
            json.has("pushVideo") -> json.optBoolean("pushVideo", true)
            else -> true
        }
        return StartSignal(
            callId = callId,
            caller = caller,
            credentials = CommandCallCredentials(
                sdkAppId = sdkAppId,
                roomId = roomId,
                userId = userId,
                userSig = userSig,
            ),
            kind = kind,
            pushVideo = pushVideo,
        )
    }

    fun parseEndCallId(json: JSONObject): String {
        return firstNonBlank(json, "call_id", "callId", "task_room_id", "taskRoomId") ?: ""
    }

    fun isEndAction(action: String): Boolean =
        action == "call_end" || action == "watch_end"

    fun isOccupyRoomEnd(action: String): Boolean =
        action == "occupy_room_end"

    fun isTaskRoomLeave(action: String): Boolean =
        action == "task_room_leave"

    private fun firstNonBlank(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = json.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }
}
