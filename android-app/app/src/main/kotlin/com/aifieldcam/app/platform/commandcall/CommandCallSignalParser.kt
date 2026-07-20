package com.aifieldcam.app.platform.commandcall

import org.json.JSONObject

/**
 * 解析连线信令 JSON（MQTT / HTTP poll）为进房凭证。
 * 支持 snake_case 与 camelCase 字段名。
 */
object CommandCallSignalParser {

    data class StartSignal(
        val callId: String,
        val caller: String,
        val credentials: CommandCallCredentials,
    )

    fun parseStart(json: JSONObject): StartSignal? {
        val callId = firstNonBlank(json, "call_id", "callId") ?: return null
        val roomId = firstNonBlank(json, "room_id", "roomId") ?: return null
        val userId = firstNonBlank(json, "user_id", "userId") ?: return null
        val userSig = firstNonBlank(json, "user_sig", "userSig") ?: return null
        val sdkAppId = when {
            json.has("sdk_app_id") -> json.optInt("sdk_app_id", 0)
            json.has("sdkAppId") -> json.optInt("sdkAppId", 0)
            else -> 0
        }
        if (sdkAppId <= 0) return null
        val caller = firstNonBlank(json, "caller") ?: "指挥中心"
        return StartSignal(
            callId = callId,
            caller = caller,
            credentials = CommandCallCredentials(
                sdkAppId = sdkAppId,
                roomId = roomId,
                userId = userId,
                userSig = userSig,
            ),
        )
    }

    fun parseEndCallId(json: JSONObject): String {
        return firstNonBlank(json, "call_id", "callId") ?: ""
    }

    private fun firstNonBlank(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = json.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }
}
