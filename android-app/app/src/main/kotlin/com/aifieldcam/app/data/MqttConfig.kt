package com.aifieldcam.app.data

import android.content.Context
import android.util.Base64
import android.util.Log
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云 IoT 平台 MQTT 连接配置。
 *
 * 凭据来源：阿里云控制台 → 设备管理 → 设备详情 → DeviceSecret。
 * [enabled] 为 false 时 MQTT 模块完全不初始化，不影响现有功能。
 */
object MqttConfig {

    private const val TAG = "MqttConfig"
    private const val PREFS_NAME = "mqtt_config"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRODUCT_KEY = "product_key"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_DEVICE_SECRET = "device_secret"
    private const val KEY_BROKER_OVERRIDE = "broker_override"

    /** 默认 broker 格式（阿里云 IoT 华东2-上海节点） */
    private const val BROKER_SUFFIX = ".iot-as-mqtt.cn-shanghai.aliyuncs.com"
    private const val BROKER_PORT = 1883

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ── 读写开关 ──

    /** 是否启用 MQTT 信令通道。默认 false，完全不影响现有功能。 */
    fun isEnabled(): Boolean = prefs().getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply()
        Log.i(TAG, "mqtt enabled=$enabled")
    }

    // ── 阿里云 IoT 三要素 ──

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

    fun isConfigured(): Boolean =
        productKey().isNotBlank() && deviceName().isNotBlank() && deviceSecret().isNotBlank()

    // ── Broker URI ──

    /**
     * 拼接完整 broker URI。
     * - 若 [setBrokerUri] 显式覆盖则使用覆盖值
     * - 否则按 `${productKey}${BROKER_SUFFIX}:${BROKER_PORT}` 拼装
     */
    fun brokerUri(): String {
        val override = prefs().getString(KEY_BROKER_OVERRIDE, null)?.trim().orEmpty()
        if (override.isNotBlank()) return override
        val pk = productKey().ifEmpty { return "" }
        return "tcp://$pk$BROKER_SUFFIX:$BROKER_PORT"
    }

    fun setBrokerUri(uri: String) {
        prefs().edit().putString(KEY_BROKER_OVERRIDE, uri.trim()).apply()
    }

    // ── 阿里云 IoT MQTT 认证参数 ──

    /**
     * 阿里云 IoT MQTT clientId 格式：
     * `${deviceName}|securemode=2,signmethod=hmacsha256,timestamp=${timestamp}|`
     */
    fun generateClientId(): String {
        val dn = deviceName()
        val ts = System.currentTimeMillis()
        return "$dn|securemode=2,signmethod=hmacsha256,timestamp=$ts|"
    }

    /** MQTT username = `${deviceName}&${productKey}` */
    fun mqttUsername(): String = "${deviceName()}&${productKey()}"

    /**
     * MQTT password = HMAC-SHA256(clientId, deviceSecret)，再 Base64 编码。
     * 参考：https://help.aliyun.com/zh/iot/developer-reference/use-mqtt-to-connect-devices
     */
    fun generateMqttPassword(clientId: String): String {
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

    /** 生成随机 MQTT client instance ID */
    fun generatePahoClientId(): String = "GID_DSJ@@@${UUID.randomUUID()}"

    // ── 批量配置（从后端 API 拉取） ──

    /** 从 JSON 批量设置凭据 */
    fun applyFromJson(json: org.json.JSONObject) {
        json.optString("product_key", "").takeIf { it.isNotBlank() }?.let { setProductKey(it) }
        json.optString("device_name", "").takeIf { it.isNotBlank() }?.let { setDeviceName(it) }
        json.optString("device_secret", "").takeIf { it.isNotBlank() }?.let { setDeviceSecret(it) }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}