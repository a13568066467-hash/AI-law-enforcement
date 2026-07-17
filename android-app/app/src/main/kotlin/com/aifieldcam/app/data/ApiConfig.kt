package com.aifieldcam.app.data

import android.content.Context
import com.aifieldcam.app.BuildConfig

/**
 * 可持久化的后端地址，避免电脑局域网 IP 变化后需改代码重装。
 */
object ApiConfig {

    private const val PREFS_NAME = "api_config"
    private const val KEY_BASE_URL = "base_url"

    /** 首次安装默认值；可被 local.properties 的 backend.host 覆盖 */
    val DEFAULT_BASE_URL: String = run {
        val fromBuild = BuildConfig.BACKEND_HOST.trim()
        if (fromBuild.isNotEmpty()) normalizeUrlStatic(fromBuild) else "http://192.168.1.106:8000"
    }

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getBaseUrl(): String {
        val saved = prefs().getString(KEY_BASE_URL, null)?.trim().orEmpty()
        if (saved.isNotEmpty()) return normalizeUrl(saved)
        val fromBuild = BuildConfig.BACKEND_HOST.trim()
        if (fromBuild.isNotEmpty()) return normalizeUrl(fromBuild)
        return DEFAULT_BASE_URL
    }

    fun setBaseUrl(raw: String): String {
        return setBaseUrlResult(raw).first
    }

    /** @return Pair(规范化后的地址, 是否与上次不同) */
    fun setBaseUrlResult(raw: String): Pair<String, Boolean> {
        val previous = getBaseUrl()
        val normalized = normalizeUrl(raw)
        prefs().edit().putString(KEY_BASE_URL, normalized).apply()
        return normalized to (previous != normalized)
    }

    fun normalizeUrl(raw: String): String = normalizeUrlStatic(raw)

    private fun normalizeUrlStatic(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (url.isEmpty()) return DEFAULT_BASE_URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
