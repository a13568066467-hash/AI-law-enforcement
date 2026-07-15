package com.aifieldcam.mobile.data

import android.content.Context
import com.aifieldcam.mobile.BuildConfig

object ApiConfig {

    private const val PREFS_NAME = "api_config"
    private const val KEY_BASE_URL = "base_url"

    val DEFAULT_BASE_URL: String = run {
        val fromBuild = BuildConfig.BACKEND_HOST.trim()
        if (fromBuild.isNotEmpty()) normalizeUrlStatic(fromBuild) else "http://192.168.1.106:8000"
    }

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getBaseUrl(): String {
        val fromBuild = BuildConfig.BACKEND_HOST.trim()
        if (fromBuild.isNotEmpty()) return normalizeUrl(fromBuild)
        val saved = prefs().getString(KEY_BASE_URL, null)?.trim().orEmpty()
        return if (saved.isNotEmpty()) normalizeUrl(saved) else DEFAULT_BASE_URL
    }

    fun setBaseUrl(raw: String): String {
        val normalized = normalizeUrl(raw)
        prefs().edit().putString(KEY_BASE_URL, normalized).apply()
        return normalized
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
