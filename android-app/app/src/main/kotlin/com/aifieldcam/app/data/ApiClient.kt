package com.aifieldcam.app.data

import com.aifieldcam.app.ble.BleConfig
import com.aifieldcam.app.demo.DemoScenarios
import com.aifieldcam.app.data.ApiConfig
import org.json.JSONArray
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
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
        val demo: DemoScenarios.SceneResult? = null,
    )

    data class VisionResponse(
        val explanation: String,
        val lastExplanation: String,
        val model: String,
    )

    data class PatrolAuthResult(
        val token: String,
        val phone: String,
        val name: String,
        val employeeId: String,
        val department: String,
        val deviceId: String,
        val message: String,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mockSessionExplanation: String = ""

    private fun postMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    fun login(
        phone: String,
        password: String,
        onDone: (Boolean, String, String) -> Unit,
    ) {
        executor.execute {
            val (ok, token, err) = performLogin(phone, password)
            postMain { onDone(ok, token, err) }
        }
    }

    fun verifyStep1Profile(
        profile: OfficerProfile,
        onDone: (Boolean, String, String) -> Unit,
    ) {
        executor.execute {
            val result = try {
                val body = JSONObject()
                    .put("name", profile.name)
                    .put("employee_id", profile.employeeId)
                    .put("department", profile.department)
                    .put("device_id", profile.deviceId)
                    .toString()
                val conn = openPost("${ApiConfig.getBaseUrl()}/auth/patrol/step1/profile", body, null)
                if (conn.responseCode == 200) {
                    val json = readJson(conn)
                    Triple(true, json.optString("message", "步骤1通过"), json.optString("session_id", ""))
                } else {
                    Triple(false, readResponseText(conn).take(200), "")
                }
            } catch (e: Exception) {
                localStep1(profile, e)
            }
            postMain { onDone(result.first, result.second, result.third) }
        }
    }

    fun sendSmsCode(
        sessionId: String,
        phone: String,
        onDone: (Boolean, String, String) -> Unit,
    ) {
        executor.execute {
            val result = try {
                val body = JSONObject()
                    .put("session_id", sessionId)
                    .put("phone", phone)
                    .toString()
                val conn = openPost("${ApiConfig.getBaseUrl()}/auth/patrol/step2/sms/send", body, null)
                if (conn.responseCode == 200) {
                    val json = readJson(conn)
                    Triple(
                        true,
                        json.optString("message", "验证码已发送"),
                        json.optString("dev_code", ""),
                    )
                } else {
                    Triple(false, readResponseText(conn).take(200), "")
                }
            } catch (e: Exception) {
                localSendSms(sessionId, phone, e)
            }
            postMain { onDone(result.first, result.second, result.third) }
        }
    }

    fun verifySmsCode(
        sessionId: String,
        phone: String,
        code: String,
        onDone: (Boolean, String, String) -> Unit,
    ) {
        executor.execute {
            val result = try {
                val body = JSONObject()
                    .put("session_id", sessionId)
                    .put("phone", phone)
                    .put("code", code)
                    .toString()
                val conn = openPost("${ApiConfig.getBaseUrl()}/auth/patrol/step2/sms/verify", body, null)
                if (conn.responseCode == 200) {
                    val json = readJson(conn)
                    Triple(
                        true,
                        json.optString("message", "步骤2通过"),
                        json.optString("verify_token", ""),
                    )
                } else {
                    Triple(false, readResponseText(conn).take(200), "")
                }
            } catch (e: Exception) {
                localVerifySms(sessionId, phone, code, e)
            }
            postMain { onDone(result.first, result.second, result.third) }
        }
    }

    fun patrolAuthenticate(
        verifyToken: String,
        deviceId: String,
        profile: OfficerProfile,
        faceJpeg: ByteArray,
        onDone: (Boolean, PatrolAuthResult?, String) -> Unit,
    ) {
        executor.execute {
            val faceBase64 = com.aifieldcam.app.util.FaceFingerprint.jpegToBase64(faceJpeg)
            if (verifyToken.startsWith("offline-")) {
                val (ok, result, err) = tryOfflinePatrol(profile, faceJpeg)
                postMain { onDone(ok, result, err) }
                return@execute
            }
            val registered = try {
                val url = "${ApiConfig.getBaseUrl()}/auth/patrol/status" +
                    "?phone=${java.net.URLEncoder.encode(profile.phone, "UTF-8")}" +
                    "&device_id=${java.net.URLEncoder.encode(deviceId, "UTF-8")}"
                val conn = openGet(url)
                if (conn.responseCode == 200) {
                    readJson(conn).optBoolean("registered", false)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }

            val result = when (registered) {
                true -> performPatrolAuth(verifyToken, deviceId, faceBase64, register = false)
                false -> performPatrolAuth(verifyToken, deviceId, faceBase64, register = true)
                null -> tryOfflinePatrol(profile, faceJpeg)
            }
            postMain { onDone(result.first, result.second, result.third) }
        }
    }

    fun runDemoScenario(
        token: String,
        scenarioId: String,
        deviceId: String,
        onDone: (Boolean, DemoScenarios.SceneResult?, String) -> Unit,
    ) {
        executor.execute {
            val result = try {
                val body = JSONObject()
                    .put("scenario_id", scenarioId)
                    .put("device_id", deviceId)
                    .toString()
                val conn = openPost("${ApiConfig.getBaseUrl()}/v1/demo/scenario", body, token)
                if (conn.responseCode == 200) {
                    val json = readJson(conn)
                    val demo = parseDemoResult(json)
                    if (demo != null) Triple(true, demo, "") else Triple(false, null, "解析失败")
                } else if (conn.responseCode == 401) {
                    Triple(false, null, ERR_AUTH_EXPIRED)
                } else {
                    Triple(false, null, readResponseText(conn).take(120))
                }
            } catch (_: Exception) {
                val local = DemoScenarios.run(scenarioId, deviceId)
                if (local.scenarioId.isNotEmpty()) {
                    Triple(true, local, "")
                } else {
                    Triple(false, null, "演示失败")
                }
            }
            postMain { onDone(result.first, result.second, result.third) }
        }
    }

    fun offboardPatrolOfficer(
        token: String,
        deviceId: String,
        onDone: (Boolean, String) -> Unit,
    ) {
        executor.execute {
            val result = try {
                val body = JSONObject().put("device_id", deviceId).toString()
                val conn = openPost("${ApiConfig.getBaseUrl()}/auth/patrol/offboard", body, token)
                if (conn.responseCode == 200) {
                    val json = readJson(conn)
                    true to json.optString("message", "已注销")
                } else {
                    false to readResponseText(conn).take(200).ifEmpty { "注销失败" }
                }
            } catch (e: Exception) {
                false to networkErrorMessage(e)
            }
            postMain { onDone(result.first, result.second) }
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
            postMain { onDone(result.first, result.second) }
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
                            demo = parseDemo(json.optJSONObject("demo")),
                        ),
                        "",
                    )
                } else {
                    chatFailure(conn, token, text)
                }
            } catch (e: Exception) {
                chatNetworkFailure(token, text, e)
            }
            postMain { onDone(result.first, result.second, result.third) }
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
                } else if (BleConfig.API_AUTO_MOCK && isMockToken(token)) {
                    Triple(true, mockVision(), "")
                } else if (conn.responseCode == 401) {
                    Triple(false, null, ERR_AUTH_EXPIRED)
                } else {
                    Triple(false, null, httpErrorMessage(conn, "识图失败"))
                }
            } catch (e: Exception) {
                if (BleConfig.API_AUTO_MOCK && isMockToken(token)) {
                    Triple(true, mockVision(), "")
                } else {
                    Triple(false, null, networkErrorMessage(e))
                }
            }
            postMain { onDone(result.first, result.second, result.third) }
        }
    }

    private fun performPatrolAuth(
        verifyToken: String,
        deviceId: String,
        faceBase64: String,
        register: Boolean,
    ): Triple<Boolean, PatrolAuthResult?, String> {
        return try {
            val path = if (register) "/auth/patrol/register" else "/auth/patrol/login"
            val body = JSONObject()
                .put("verify_token", verifyToken)
                .put("device_id", deviceId)
                .put("face_image_base64", faceBase64)
                .toString()
            val conn = openPost("${ApiConfig.getBaseUrl()}$path", body, null)
            if (conn.responseCode == 200) {
                val json = readJson(conn)
                Triple(true, parsePatrolResult(json), json.optString("message", "认证成功"))
            } else {
                val detail = readResponseText(conn).take(200)
                Triple(false, null, detail.ifEmpty { "认证失败 HTTP ${conn.responseCode}" })
            }
        } catch (e: Exception) {
            Triple(false, null, networkErrorMessage(e))
        }
    }

    private fun localStep1(
        profile: OfficerProfile,
        e: Exception,
    ): Triple<Boolean, String, String> {
        if (!BleConfig.API_AUTO_MOCK) {
            return Triple(false, networkErrorMessage(e), "")
        }
        if (!profile.isCompleteForRegister() && profile.name.isBlank()) {
            return Triple(false, "请填写完整人员信息", "")
        }
        if (profile.name.length < 2 || profile.employeeId.isBlank() || profile.department.isBlank()) {
            return Triple(false, "请填写姓名、工号、部门", "")
        }
        val roster = mapOf("XC001" to "张三", "XC002" to "李四", "XC003" to "王五")
        val expected = roster[profile.employeeId.uppercase()]
        if (expected != null && expected != profile.name) {
            return Triple(false, "工号与姓名不匹配（应为 $expected）", "")
        }
        val sid = "offline-${System.currentTimeMillis()}"
        return Triple(true, "步骤1通过（离线校验）", sid)
    }

    private fun localSendSms(
        sessionId: String,
        phone: String,
        e: Exception,
    ): Triple<Boolean, String, String> {
        if (!BleConfig.API_AUTO_MOCK || !sessionId.startsWith("offline-")) {
            return Triple(false, networkErrorMessage(e), "")
        }
        val code = (100000 + (Math.random() * 899999)).toInt().toString()
        VerificationStateStore.saveDevCode(code)
        return Triple(true, "验证码已发送（离线演示）", code)
    }

    private fun localVerifySms(
        sessionId: String,
        phone: String,
        code: String,
        e: Exception,
    ): Triple<Boolean, String, String> {
        if (!BleConfig.API_AUTO_MOCK || !sessionId.startsWith("offline-")) {
            return Triple(false, networkErrorMessage(e), "")
        }
        val expected = VerificationStateStore.load().devCode
        if (expected.isEmpty() || code.trim() != expected) {
            return Triple(false, "验证码错误", "")
        }
        val token = "offline-${System.currentTimeMillis()}"
        return Triple(true, "步骤2通过（离线校验）", token)
    }

    private fun parsePatrolResult(json: JSONObject): PatrolAuthResult {
        return PatrolAuthResult(
            token = json.optString("token", ""),
            phone = json.optString("phone", ""),
            name = json.optString("name", ""),
            employeeId = json.optString("employee_id", ""),
            department = json.optString("department", ""),
            deviceId = json.optString("device_id", ""),
            message = json.optString("message", ""),
        )
    }

    private fun tryOfflinePatrol(
        profile: OfficerProfile,
        faceJpeg: ByteArray,
    ): Triple<Boolean, PatrolAuthResult?, String> {
        if (!BleConfig.API_AUTO_MOCK) {
            return Triple(false, null, "后端离线，请检查网络与地址")
        }
        if (!VerificationStateStore.load().step2Ok) {
            return Triple(false, null, "请先完成步骤2电话验证")
        }
        val stored = OfficerProfileStore.load()
        if (stored != null && stored.faceFingerprint.isNotEmpty()) {
            if (stored.phone != profile.phone) {
                return Triple(false, null, "手机号与已绑定巡查员不一致")
            }
            if (stored.deviceId != profile.deviceId) {
                return Triple(false, null, "本机不是您绑定的执法仪")
            }
            if (!com.aifieldcam.app.util.FaceFingerprint.matches(stored.faceFingerprint, faceJpeg)) {
                return Triple(false, null, "人脸验证未通过")
            }
        } else {
            val fp = com.aifieldcam.app.util.FaceFingerprint.fromJpeg(faceJpeg)
            OfficerProfileStore.saveRegistered(profile, fp)
        }
        val token = "mock-patrol-${System.currentTimeMillis()}"
        val result = PatrolAuthResult(
            token = token,
            phone = profile.phone,
            name = profile.name,
            employeeId = profile.employeeId,
            department = profile.department,
            deviceId = profile.deviceId,
            message = "离线三步验证完成",
        )
        return Triple(true, result, result.message)
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
            return if (BleConfig.API_AUTO_MOCK && isMockToken(token)) {
                Triple(true, mockChat(text), "")
            } else {
                Triple(false, null, ERR_AUTH_EXPIRED)
            }
        }
        if (BleConfig.API_AUTO_MOCK && isMockToken(token)) {
            return Triple(true, mockChat(text), "")
        }
        return Triple(false, null, httpErrorMessage(conn, "对话失败"))
    }

    private fun chatNetworkFailure(
        token: String,
        text: String,
        e: Exception,
    ): Triple<Boolean, ChatResponse?, String> {
        if (BleConfig.API_AUTO_MOCK && isMockToken(token)) {
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

    private fun isMockToken(token: String): Boolean = token.startsWith("mock")

    private fun mockLogin(phone: String, password: String): Boolean {
        return phone == BleConfig.DEMO_PHONE && password == BleConfig.DEMO_PASSWORD
    }

    private fun mockChat(text: String, deviceId: String = ""): ChatResponse {
        val sid = DemoScenarios.matchFromText(text)
        if (sid != null) {
            val demo = DemoScenarios.run(sid, deviceId)
            return ChatResponse("A", "demo_scenario", demo.reply, demo.bleCmds, demo)
        }
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

    private fun parseDemo(obj: org.json.JSONObject?): DemoScenarios.SceneResult? {
        if (obj == null || obj.length() == 0) return null
        return parseDemoResult(obj)
    }

    private fun parseDemoResult(json: org.json.JSONObject): DemoScenarios.SceneResult? {
        val sid = json.optString("scenario_id", "")
        if (sid.isEmpty()) return null
        val highlights = mutableListOf<String>()
        json.optJSONArray("highlights")?.let { arr ->
            for (i in 0 until arr.length()) highlights.add(arr.optString(i))
        }
        return DemoScenarios.SceneResult(
            scenarioId = sid,
            title = json.optString("title", ""),
            voiceBroadcast = json.optString("voice_broadcast", ""),
            reply = json.optString("reply", ""),
            document = json.optString("document", ""),
            highlights = highlights,
            platformSync = json.optString("platform_sync", ""),
            bleCmds = parseBleCmds(json.optJSONArray("ble_cmds")),
            alertLevel = json.optString("alert_level", "info"),
        )
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
