package com.aifieldcam.mobile.data

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object ApiClient {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000

    data class AuthResult(
        val ok: Boolean,
        val token: String = "",
        val phone: String = "",
        val name: String = "",
        val employeeId: String = "",
        val department: String = "",
        val message: String = "",
    )

    data class BindResult(
        val ok: Boolean,
        val message: String = "",
        val deviceId: String = "",
        val officerName: String = "",
    )

    private val executor = Executors.newSingleThreadExecutor()

    fun mobileLogin(
        phone: String,
        faceImageBase64: String,
        onDone: (AuthResult) -> Unit,
    ) {
        executor.execute {
            val result = try {
                val body = JSONObject()
                    .put("phone", phone)
                    .put("face_image_base64", faceImageBase64)
                    .toString()
                val conn = openPost("${ApiConfig.getBaseUrl()}/auth/mobile/login", body, null)
                if (conn.responseCode == 200) {
                    val json = readJson(conn)
                    AuthResult(
                        ok = true,
                        token = json.optString("token"),
                        phone = json.optString("phone"),
                        name = json.optString("name"),
                        employeeId = json.optString("employee_id"),
                        department = json.optString("department"),
                        message = json.optString("message", "登录成功"),
                    )
                } else {
                    AuthResult(ok = false, message = parseError(conn))
                }
            } catch (e: Exception) {
                AuthResult(ok = false, message = networkError(e))
            }
            onDone(result)
        }
    }

    fun mobileRegister(
        phone: String,
        name: String,
        employeeId: String,
        department: String,
        company: String,
        position: String,
        idCard: String,
        faceImageBase64: String,
        onDone: (AuthResult) -> Unit,
    ) {
        executor.execute {
            val result = try {
                val body = JSONObject()
                    .put("phone", phone)
                    .put("name", name)
                    .put("employee_id", employeeId)
                    .put("department", department)
                    .put("company", company)
                    .put("position", position)
                    .put("id_card", idCard)
                    .put("face_image_base64", faceImageBase64)
                    .toString()
                val conn = openPost("${ApiConfig.getBaseUrl()}/auth/mobile/register", body, null)
                if (conn.responseCode == 200) {
                    val json = readJson(conn)
                    AuthResult(
                        ok = true,
                        token = json.optString("token"),
                        phone = json.optString("phone"),
                        name = json.optString("name"),
                        employeeId = json.optString("employee_id"),
                        department = json.optString("department"),
                        message = json.optString("message", "注册成功"),
                    )
                } else {
                    AuthResult(ok = false, message = parseError(conn))
                }
            } catch (e: Exception) {
                AuthResult(ok = false, message = networkError(e))
            }
            onDone(result)
        }
    }

    fun confirmBind(
        deviceId: String,
        token: String,
        mobileToken: String,
        onDone: (BindResult) -> Unit,
    ) {
        executor.execute {
            val result = try {
                val body = JSONObject()
                    .put("device_id", deviceId)
                    .put("token", token)
                    .toString()
                val conn = openPost(
                    "${ApiConfig.getBaseUrl()}/auth/device/bind/confirm",
                    body,
                    "Bearer $mobileToken",
                )
                if (conn.responseCode == 200) {
                    val json = readJson(conn)
                    val officer = json.optJSONObject("officer")
                    BindResult(
                        ok = json.optBoolean("ok", true),
                        message = json.optString("message", "绑定成功"),
                        deviceId = json.optString("device_id", deviceId),
                        officerName = officer?.optString("name").orEmpty(),
                    )
                } else {
                    BindResult(ok = false, message = parseError(conn))
                }
            } catch (e: Exception) {
                BindResult(ok = false, message = networkError(e))
            }
            onDone(result)
        }
    }

    private fun openPost(url: String, body: String, authorization: String?): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (!authorization.isNullOrBlank()) {
                setRequestProperty("Authorization", authorization)
            }
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val text = readResponseText(conn)
        return JSONObject(text)
    }

    private fun readResponseText(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return BufferedReader(InputStreamReader(stream ?: conn.inputStream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun parseError(conn: HttpURLConnection): String {
        return try {
            val json = readJson(conn)
            json.optString("detail", json.optString("message", "请求失败"))
        } catch (_: Exception) {
            "请求失败 (${conn.responseCode})"
        }
    }

    private fun networkError(e: Exception): String {
        val msg = e.message.orEmpty()
        return if (msg.contains("Failed to connect", ignoreCase = true)) {
            "无法连接后端，请检查网络与 API 地址"
        } else {
            msg.ifEmpty { "网络错误" }
        }
    }
}
