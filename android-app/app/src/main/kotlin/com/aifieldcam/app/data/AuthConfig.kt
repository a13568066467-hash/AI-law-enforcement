package com.aifieldcam.app.data

import android.content.Context

/**
 * 持久化登录态，避免每次打开 App 或后端重启后都要手动登录。
 * token 与 base_url 绑定：换地址后自动失效，需重新登录。
 */
object AuthConfig {

    private const val PREFS_NAME = "auth_config"
    private const val KEY_TOKEN = "worker_token"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_BASE_URL = "auth_base_url"
    private const val KEY_OFFICER_NAME = "officer_name"
    private const val KEY_OFFICER_PHONE = "officer_phone"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    data class SavedAuth(
        val token: String,
        val sessionId: String,
        val baseUrl: String,
        val officerName: String = "",
        val officerPhone: String = "",
    )

    fun load(): SavedAuth {
        val p = prefs()
        return SavedAuth(
            token = p.getString(KEY_TOKEN, "").orEmpty(),
            sessionId = p.getString(KEY_SESSION_ID, "").orEmpty(),
            baseUrl = p.getString(KEY_BASE_URL, "").orEmpty(),
            officerName = p.getString(KEY_OFFICER_NAME, "").orEmpty(),
            officerPhone = p.getString(KEY_OFFICER_PHONE, "").orEmpty(),
        )
    }

    fun save(
        token: String,
        sessionId: String,
        baseUrl: String,
        officerName: String = "",
        officerPhone: String = "",
    ) {
        prefs().edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_BASE_URL, ApiConfig.normalizeUrl(baseUrl))
            .putString(KEY_OFFICER_NAME, officerName)
            .putString(KEY_OFFICER_PHONE, officerPhone)
            .apply()
    }

    fun clear() {
        prefs().edit()
            .remove(KEY_TOKEN)
            .remove(KEY_SESSION_ID)
            .remove(KEY_BASE_URL)
            .remove(KEY_OFFICER_NAME)
            .remove(KEY_OFFICER_PHONE)
            .apply()
    }

    fun matchesCurrentBaseUrl(): Boolean {
        val saved = load()
        if (saved.token.isEmpty()) return false
        return saved.baseUrl == ApiConfig.getBaseUrl()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
