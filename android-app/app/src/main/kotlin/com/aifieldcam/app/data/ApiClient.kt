package com.aifieldcam.app.data

import com.aifieldcam.app.ble.BleConfig
import com.aifieldcam.app.data.ApiConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object ApiClient {

    const val ERR_AUTH_EXPIRED = "AUTH_EXPIRED"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val DEFAULT_READ_TIMEOUT_MS = 15_000
    private const val LLM_READ_TIMEOUT_MS = 90_000

    data class ChatResponse(
        val agent: String,
        val intent: String,
        val reply: String,
        val bleCmds: List<Int>,
    )

    data class VisionResponse(
        val explanation: String,
        val lastExplanation: String,
        val model: String,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private var mockSessionExplanation: String = ""

    fun login(
        phone: String,
        password: String,
        onDone: (Boolean, String, String) -> Unit,
    ) {
        executor.execute {
            val (ok, token, err) = performLogin(phone, password)
            onDone(ok, token, err)
        }
    }

    fun pingHealth(onDone: (Boolean, String) -> Unit) {
        executor.execute {
            val result = try {
                val conn = openGet("${ApiConfig.getBaseUrl()}/health")
                if (conn.responseCode == 200) {
                    true to "后端在线"
                } else {
                    false to "HTTP ${conn.responseCode}"
                }
            } catch (_: Exception) {
                false to if (BleConfig.API_AUTO_MOCK) "离线（将用 mock）" else "network error"
            }
            onDone(result.first, result.second)
        }
    }

    fun postChat(
        token: String,
        sessionId: String,
        deviceId: String,
        text: String,
        bleState: Int,
        onDone: (Boolean, ChatResponse?, String) -> Unit,
    ) {
        executor.execute {
            val result = try {
                val state = JSONObject().put("ble_state", bleState)
                val body = JSONObject()
                    .put("session_id", sessionId)
                    .put("device_id", deviceId)
                    .put("text", text)
                    .put("state", state)
                    .toString()
                val conn = openPost(
                    "${ApiConfig.getBaseUrl()}/v1/chat",
                    body,
                    token,
                    LLM_READ_TIMEOUT_MS,
                )
                if (conn.responseCode == 200) {
                    val json = readJson(conn)
                    Triple(
                        true,
                        ChatResponse(
                            agent = json.optString("agent", "A"),
                            intent = json.optString("intent", "chat"),
                            reply = json.optString("reply", ""),
                            bleCmds = parseBleCmds(json.optJSONArray("ble_cmds")),
                        ),
                        "",
                    )
                } else {
                    chatFailure(conn, token, text)
                }
            } catch (e: Exception) {
                chatNetworkFailure(token, text, e)
            }
            onDone(result.first, result.second, result.third)
        }
    }

    fun postVision(
        token: String,
        sessionId: String,
        imageBase64: String,
        onDone: (Boolean, VisionResponse?, String) -> Unit,
    ) {
        executor.execute {
            val result = try {
                val body = JSONObject()
                    .put("session_id", sessionId)
                    .put("image_base64", imageBase64)
                    .toString()
                val conn = openPost(
                    "${ApiConfig.getBaseUrl()}/v1/vision",
                    body,
                    token,
                    LLM_READ_TIMEOUT_MS,
                )
                if (conn.responseCode == 200) {
                    val json = readJson(conn)
                    val resp = VisionResponse(
                        explanation = json.optString("explanation", ""),
                        lastExplanation = json.optString("last_explanation", ""),
                        model = json.optString("model", "qwen3-vl-8b-instruct"),
                    )
                    mockSessionExplanation = resp.lastExplanation
                    Triple(true, resp, "")
                } else if (BleConfig.API_AUTO_MOCK && token.startsWith("mock-token")) {
                    Triple(true, mockVision(), "")
                } else if (conn.responseCode == 401) {
                    Triple(false, null, ERR_AUTH_EXPIRED)
                } else {
                    Triple(false, null, httpErrorMessage(conn, "识图失败"))
                }
            } catch (e: Exception) {
                if (BleConfig.API_AUTO_MOCK && token.startsWith("mock-token")) {
                    Triple(true, mockVision(), "")
                } else {
                    Triple(false, null, networkErrorMessage(e))
                }
            }
            onDone(result.first, result.second, result.third)
        }
    }

    private fun performLogin(phone: String, password: String): Triple<Boolean, String, String> {
        return try {
            val body = JSONObject()
                .put("phone", phone)
                .put("password", password)
                .toString()
            val conn = openPost("${ApiConfig.getBaseUrl()}/auth/login", body, null)
            val code = conn.responseCode
            if (code == 200) {
                val token = readJson(conn).optString("token", "")
                if (token.isNotEmpty()) {
                    Triple(true, token, "")
                } else {
                    mockLoginFallback(phone, password, "login failed $code")
                }
            } else {
                mockLoginFallback(phone, password, "login failed $code")
            }
        } catch (_: Exception) {
            mockLoginFallback(phone, password, "network error")
        }
    }

    private fun mockLoginFallback(
        phone: String,
        password: String,
        err: String,
    ): Triple<Boolean, String, String> {
        return if (BleConfig.API_AUTO_MOCK && mockLogin(phone, password)) {
            Triple(true, "mock-token-${System.currentTimeMillis()}", "offline-mock")
        } else {
            Triple(false, "", err)
        }
    }

    private fun chatFailure(
        conn: HttpURLConnection,
        token: String,
        text: String,
    ): Triple<Boolean, ChatResponse?, String> {
        if (conn.responseCode == 401) {
            return if (BleConfig.API_AUTO_MOCK && token.startsWith("mock-token")) {
                Triple(true, mockChat(text), "")
            } else {
                Triple(false, null, ERR_AUTH_EXPIRED)
            }
        }
        if (BleConfig.API_AUTO_MOCK && token.startsWith("mock-token")) {
            return Triple(true, mockChat(text), "")
        }
        return Triple(false, null, httpErrorMessage(conn, "对话失败"))
    }

    private fun chatNetworkFailure(
        token: String,
        text: String,
        e: Exception,
    ): Triple<Boolean, ChatResponse?, String> {
        if (BleConfig.API_AUTO_MOCK && token.startsWith("mock-token")) {
            return Triple(true, mockChat(text), "")
        }
        return Triple(false, null, networkErrorMessage(e))
    }

    private fun httpErrorMessage(conn: HttpURLConnection, prefix: String): String {
        val detail = readResponseText(conn).take(120)
        return if (detail.isNotEmpty()) {
            "$prefix (HTTP ${conn.responseCode}): $detail"
        } else {
            "$prefix (HTTP ${conn.responseCode})"
        }
    }

    private fun networkErrorMessage(e: Exception): String {
        val hint = "请确认手机与电脑同一 WiFi，且后端已启动：${ApiConfig.getBaseUrl()}"
        val msg = e.message?.take(80).orEmpty()
        return if (msg.isNotEmpty()) "网络错误: $msg。$hint" else "网络错误。$hint"
    }

    private fun mockLogin(phone: String, password: String): Boolean {
        return phone == BleConfig.DEMO_PHONE && password == BleConfig.DEMO_PASSWORD
    }

    private fun mockChat(text: String): ChatResponse {
        return when {
            text.contains("开始录像") || text.contains("开录") ->
                ChatResponse("A", "start_recording", "好的，开始录像。", listOf(0x01))
            text.contains("停止录像") || text.contains("停录") ->
                ChatResponse("A", "stop_recording", "录像已停止。", listOf(0x02))
            isMockCaptureCommand(text) ->
                ChatResponse("A", "capture_and_explain", "好的，我来拍一张看看。", listOf(0x03))
            mockSessionExplanation.isNotEmpty() && !isMockCaptureCommand(text) -> {
                val memory = mockSessionExplanation.take(500)
                ChatResponse(
                    "B",
                    "chat",
                    "结合上次识图记忆：\n$memory\n\n（针对「$text」请对照上述内容查看。上传新照片前我会一直记得这张图。）",
                    emptyList(),
                )
            }
            else ->
                ChatResponse("A", "chat", "我在。可以说「开始录像」「停止录像」或上传照片识别。", emptyList())
        }
    }

    private fun isMockCaptureCommand(text: String): Boolean {
        if (text.contains("再拍") || text.contains("重新拍") || text.contains("重新识别") || text.contains("换一张")) {
            return true
        }
        return listOf("识别一下", "拍一张", "拍照", "识图", "拍摄一张").any { text.contains(it) }
    }

    private fun mockVision(): VisionResponse {
        val text = "【识别内容】可见施工区域、机械设备与作业面。\n" +
            "【安全隐患】模拟分析：临边护栏不明显，部分人员安全帽佩戴情况不清晰；" +
            "建议现场复核。"
        mockSessionExplanation = text
        return VisionResponse(text, text, "mock")
    }

    private fun parseBleCmds(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        val cmds = mutableListOf<Int>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            cmds.add(obj.optInt("cmd"))
        }
        return cmds
    }

    private fun openGet(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = DEFAULT_READ_TIMEOUT_MS
        return conn
    }

    private fun openPost(
        url: String,
        body: String,
        token: String?,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = readTimeoutMs
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (!token.isNullOrEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        return JSONObject(readResponseText(conn))
    }

    private fun readResponseText(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) {
            conn.inputStream
        } else {
            conn.errorStream ?: conn.inputStream
        }
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }
}
