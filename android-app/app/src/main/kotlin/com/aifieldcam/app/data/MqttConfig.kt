package com.aifieldcam.app.data

import android.content.Context
import android.util.Base64
import android.util.Log
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MQTT 连接配置：支持 EMQX（用户名密码）与阿里云 IoT（三元组签名）。
 */
object MqttConfig {

    private const val TAG = "MqttConfig"
    private const val PREFS_NAME = "mqtt_config"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROVIDER = "provider" // emqx | aliyun_iot
    private const val KEY_PRODUCT_KEY = "product_key"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_DEVICE_SECRET = "device_secret"
    private const val KEY_BROKER_OVERRIDE = "broker_override"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_TOPIC_PREFIX = "topic_prefix"

    private const val BROKER_SUFFIX = ".iot-as-mqtt.cn-shanghai.aliyuncs.com"
    private const val BROKER_PORT = 1883

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isEnabled(): Boolean = prefs().getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply()
        Log.i(TAG, "mqtt enabled=$enabled")
    }

    fun provider(): String = prefs().getString(KEY_PROVIDER, "aliyun_iot") ?: "aliyun_iot"

    fun setProvider(provider: String) {
        prefs().edit().putString(KEY_PROVIDER, provider.trim()).apply()
    }

    fun isEmqx(): Boolean = provider() == "emqx"

    fun productKey(): String = prefs().getString(KEY_PRODUCT_KEY, "") ?: ""

    fun setProductKey(key: String) {
        prefs().edit().putString(KEY_PRODUCT_KEY, key.trim()).apply()
    }

    fun deviceName(): String = prefs().getString(KEY_DEVICE_NAME, "") ?: ""

    fun setDeviceName(name: String) {
        prefs().edit().putString(KEY_DEVICE_NAME, name.trim()).apply()
    }

    fun deviceSecret(): String = prefs().getString(KEY_DEVICE_SECRET, "") ?: ""

    fun setDeviceSecret(secret: String) {
        prefs().edit().putString(KEY_DEVICE_SECRET, secret.trim()).apply()
    }

    fun username(): String = prefs().getString(KEY_USERNAME, "") ?: ""

    fun setUsername(value: String) {
        prefs().edit().putString(KEY_USERNAME, value.trim()).apply()
    }

    fun password(): String = prefs().getString(KEY_PASSWORD, "") ?: ""

    fun setPassword(value: String) {
        prefs().edit().putString(KEY_PASSWORD, value).apply()
    }

    fun clientIdOverride(): String = prefs().getString(KEY_CLIENT_ID, "") ?: ""

    fun setClientIdOverride(value: String) {
        prefs().edit().putString(KEY_CLIENT_ID, value.trim()).apply()
    }

    fun topicPrefix(): String =
        prefs().getString(KEY_TOPIC_PREFIX, "aifieldcam/command_call")
            ?: "aifieldcam/command_call"

    fun setTopicPrefix(value: String) {
        prefs().edit().putString(KEY_TOPIC_PREFIX, value.trim().trim('/')).apply()
    }

    fun isConfigured(): Boolean =
        if (isEmqx()) {
            brokerUri().isNotBlank() && username().isNotBlank() && password().isNotBlank()
        } else {
            productKey().isNotBlank() && deviceName().isNotBlank() && deviceSecret().isNotBlank()
        }

    fun brokerUri(): String {
        val override = prefs().getString(KEY_BROKER_OVERRIDE, null)?.trim().orEmpty()
        if (override.isNotBlank()) return override
        if (isEmqx()) return ""
        val pk = productKey().ifEmpty { return "" }
        return "tcp://$pk$BROKER_SUFFIX:$BROKER_PORT"
    }

    fun setBrokerUri(uri: String) {
        prefs().edit().putString(KEY_BROKER_OVERRIDE, uri.trim()).apply()
    }

    fun generateClientId(): String {
        if (isEmqx()) {
            val override = clientIdOverride()
            if (override.isNotBlank()) return override
            val dn = deviceName()
            if (dn.isNotBlank()) return dn
            return "device-${UUID.randomUUID()}"
        }
        val dn = deviceName()
        val ts = System.currentTimeMillis()
        return "$dn|securemode=2,signmethod=hmacsha256,timestamp=$ts|"
    }

    fun mqttUsername(): String =
        if (isEmqx()) username() else "${deviceName()}&${productKey()}"

    fun generateMqttPassword(clientId: String): String {
        if (isEmqx()) return password()
        val secret = deviceSecret().ifEmpty { return "" }
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val hash = mac.doFinal(clientId.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(hash, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "generateMqttPassword failed", e)
            ""
        }
    }

    fun generatePahoClientId(): String =
        if (isEmqx()) generateClientId() else "GID_DSJ@@@${UUID.randomUUID()}"

    /** 后端 /v1/mqtt/client-config 批量写入 */
    fun applyFromJson(json: org.json.JSONObject) {
        val provider = json.optString("provider", "").trim()
        if (provider.isNotBlank()) setProvider(provider)
        json.optString("broker_uri", "").takeIf { it.isNotBlank() }?.let { setBrokerUri(it) }
        json.optString("username", "").takeIf { it.isNotBlank() }?.let { setUsername(it) }
        json.optString("password", "").takeIf { it.isNotBlank() }?.let { setPassword(it) }
        json.optString("client_id", "").takeIf { it.isNotBlank() }?.let { setClientIdOverride(it) }
        json.optString("topic_prefix", "").takeIf { it.isNotBlank() }?.let { setTopicPrefix(it) }
        json.optString("product_key", "").takeIf { it.isNotBlank() }?.let { setProductKey(it) }
        json.optString("device_name", "").takeIf { it.isNotBlank() }?.let { setDeviceName(it) }
        json.optString("device_secret", "").takeIf { it.isNotBlank() }?.let { setDeviceSecret(it) }
        if (json.has("enabled")) {
            setEnabled(json.optBoolean("enabled", false))
        } else if (isConfigured()) {
            setEnabled(true)
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
