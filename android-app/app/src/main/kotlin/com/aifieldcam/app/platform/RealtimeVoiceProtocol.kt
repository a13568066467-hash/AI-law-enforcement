package com.aifieldcam.app.platform

import org.json.JSONObject

internal enum class RealtimeVoicePhase {
    IDLE,
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR,
}

internal class RealtimeToolCallRegistry(
    private val maxEntries: Int = 64,
) {
    enum class Decision { ALLOW, DUPLICATE, DENY }

    private val handled = LinkedHashSet<String>()

    @Synchronized
    fun evaluate(callId: String, name: String): Decision {
        if (name !in ALLOWED_TOOLS) return Decision.DENY
        if (!handled.add(callId)) return Decision.DUPLICATE
        while (handled.size > maxEntries) {
            handled.remove(handled.first())
        }
        return Decision.ALLOW
    }

    companion object {
        private val ALLOWED_TOOLS = setOf(
            "start_recording",
            "stop_recording",
            "capture_and_explain",
        )
    }
}

internal sealed interface RealtimeVoiceEvent {
    data object Ready : RealtimeVoiceEvent
    data object ResponseStarted : RealtimeVoiceEvent
    data object ResponseDone : RealtimeVoiceEvent
    data class UserTranscript(val text: String, val partial: Boolean) : RealtimeVoiceEvent
    data class AssistantTranscript(val text: String, val partial: Boolean) : RealtimeVoiceEvent
    data class ToolCall(
        val callId: String,
        val name: String,
        val arguments: JSONObject,
    ) : RealtimeVoiceEvent
    data class Error(val code: String, val message: String) : RealtimeVoiceEvent
}

internal object RealtimeVoiceProtocol {
    fun sessionStart(sessionId: String, deviceId: String): String =
        JSONObject()
            .put("type", "session.start")
            .put("session_id", sessionId)
            .put("device_id", deviceId)
            .toString()

    fun commit(): String = """{"type":"commit"}"""

    /** 仅提交输入音频以完成转写，不触发助手回复。 */
    fun commitInput(): String = """{"type":"commit_input"}"""

    fun cancel(): String = """{"type":"cancel"}"""

    fun image(jpegBase64: String): String =
        JSONObject()
            .put("type", "image")
            .put("image", jpegBase64)
            .toString()

    fun toolResult(callId: String, output: JSONObject): String =
        JSONObject()
            .put("type", "tool_result")
            .put("call_id", callId)
            .put("output", output)
            .toString()

    fun parseServerText(text: String): RealtimeVoiceEvent {
        val json = JSONObject(text)
        return when (val type = json.optString("type")) {
            "ready" -> RealtimeVoiceEvent.Ready
            "response_started" -> RealtimeVoiceEvent.ResponseStarted
            "response_done" -> RealtimeVoiceEvent.ResponseDone
            "user_transcript_delta" -> RealtimeVoiceEvent.UserTranscript(
                json.optString("text"),
                partial = true,
            )
            "user_transcript" -> RealtimeVoiceEvent.UserTranscript(
                json.optString("text"),
                partial = false,
            )
            "assistant_transcript_delta" -> RealtimeVoiceEvent.AssistantTranscript(
                json.optString("text"),
                partial = true,
            )
            "assistant_transcript" -> RealtimeVoiceEvent.AssistantTranscript(
                json.optString("text"),
                partial = false,
            )
            "tool_call" -> RealtimeVoiceEvent.ToolCall(
                callId = json.getString("call_id"),
                name = json.getString("name"),
                arguments = json.optJSONObject("arguments") ?: JSONObject(),
            )
            "error" -> RealtimeVoiceEvent.Error(
                code = json.optString("code", "realtime_error"),
                message = json.optString("message", "实时语音服务异常"),
            )
            else -> RealtimeVoiceEvent.Error(
                code = "unknown_event",
                message = "未知实时语音事件: $type",
            )
        }
    }
}
