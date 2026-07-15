package com.aifieldcam.mobile.util

import android.net.Uri

data class BindQrPayload(
    val deviceId: String,
    val token: String,
)

object BindQrParser {

    fun parse(content: String): BindQrPayload? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
        if (uri != null) {
            val device = uri.getQueryParameter("device")?.trim().orEmpty()
            val token = uri.getQueryParameter("token")?.trim().orEmpty()
            if (device.isNotEmpty() && token.isNotEmpty()) {
                return BindQrPayload(device, token)
            }
        }

        val query = when {
            trimmed.contains('?') -> trimmed.substringAfter('?')
            else -> trimmed
        }
        val params = query.split('&')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                part.substring(0, idx) to part.substring(idx + 1)
            }
            .toMap()
        val device = params["device"]?.trim().orEmpty()
        val token = params["token"]?.trim().orEmpty()
        if (device.isNotEmpty() && token.isNotEmpty()) {
            return BindQrPayload(device, token)
        }
        return null
    }
}
