package com.aifieldcam.app.data

import android.content.Context
import android.os.SystemClock

/** 检测整机重启并在启动时结束扫码占用。 */
object BindBootMarker {

    private const val PREFS = "device_bind_boot"
    private const val KEY_WAS_BOUND = "was_bound"
    private const val KEY_LAST_UPTIME = "last_uptime_ms"
    private const val KEY_SHUTDOWN_PENDING = "shutdown_pending"

    fun markBound(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_WAS_BOUND, true)
            .putLong(KEY_LAST_UPTIME, SystemClock.elapsedRealtime())
            .apply()
    }

    fun wasBound(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAS_BOUND, false)

    fun clear(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_WAS_BOUND, false)
            .putLong(KEY_LAST_UPTIME, SystemClock.elapsedRealtime())
            .apply()
    }

    /**
     * 在 [SessionManager] 恢复磁盘会话前调用。
     * 若检测到整机重启且曾绑定，立即清本机会话并标记待同步云端 shutdown。
     */
    fun prepareBoot(context: Context): Boolean {
        val p = prefs(context)
        val last = p.getLong(KEY_LAST_UPTIME, -1L)
        val nowUptime = SystemClock.elapsedRealtime()
        val rebooted = last > 0L && nowUptime + 5_000L < last
        val wasBound = p.getBoolean(KEY_WAS_BOUND, false)
        p.edit().putLong(KEY_LAST_UPTIME, nowUptime).apply()
        if (rebooted && wasBound) {
            AuthConfig.clear()
            OfficerProfileStore.clear()
            p.edit()
                .putBoolean(KEY_WAS_BOUND, false)
                .putBoolean(KEY_SHUTDOWN_PENDING, true)
                .apply()
            return true
        }
        return false
    }

    /** 重启后异步通知云端结束占用（本机已在 [prepareBoot] 清过）。 */
    fun notifyCloudAfterReboot(context: Context) {
        if (!consumeShutdownPending(context)) return
        SessionManager.getInstance(context).notifyCloudShutdown()
    }

    private fun consumeShutdownPending(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_SHUTDOWN_PENDING, false)) return false
        p.edit().remove(KEY_SHUTDOWN_PENDING).apply()
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
