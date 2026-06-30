package com.aifieldcam.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aifieldcam.app.BuildConfig
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 局域网自动发现后端，避免每次手动填写电脑 IP。
 * 策略：已保存地址 → 上次可用地址 → BuildConfig → 同网段 :8000 扫描
 */
object BackendDiscovery {

    private const val PREFS = "backend_discovery"
    private const val KEY_LAST_OK = "last_ok_url"
    private const val PROBE_TIMEOUT_MS = 1_200
    private const val SCAN_TIMEOUT_SEC = 12L

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanPool = Executors.newFixedThreadPool(24)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun probe(url: String, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean {
        val base = ApiConfig.normalizeUrl(url)
        return try {
            val conn = URL("$base/health").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            val ok = conn.responseCode == 200
            conn.disconnect()
            if (ok) {
                markLastGood(base)
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    fun markLastGood(url: String) {
        prefs().edit().putString(KEY_LAST_OK, ApiConfig.normalizeUrl(url)).apply()
    }

    fun getLastGoodUrl(): String? {
        return prefs().getString(KEY_LAST_OK, null)?.takeIf { it.isNotBlank() }
    }

    fun buildCandidateUrls(): List<String> {
        val seen = linkedSetOf<String>()
        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return
            seen.add(ApiConfig.normalizeUrl(raw))
        }
        add(ApiConfig.getBaseUrl())
        add(getLastGoodUrl())
        add(BuildConfig.BACKEND_HOST.trim().takeIf { it.isNotEmpty() }?.let { ApiConfig.normalizeUrl(it) })
        add("http://10.0.2.2:8000")
        wifiIpv4()?.let { ip ->
            val parts = ip.split(".")
            if (parts.size == 4) {
                val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                add("http://$prefix.1:8000")
                add("http://$ip:8000")
            }
        }
        return seen.toList()
    }

    /** 后台探测；找到则写入 ApiConfig 并回调 */
    fun discoverInBackground(onDone: ((String?) -> Unit)? = null) {
        scanPool.execute {
            val found = discoverBlocking()
            if (found != null) {
                ApiConfig.setBaseUrl(found)
            }
            if (onDone != null) {
                mainHandler.post { onDone(found) }
            }
        }
    }

    /** 若当前地址不可用则自动发现 */
    fun ensureReachable(onDone: ((Boolean, String) -> Unit)? = null) {
        scanPool.execute {
            val current = ApiConfig.getBaseUrl()
            if (probe(current)) {
                mainHandler.post { onDone?.invoke(true, current) }
                return@execute
            }
            val found = discoverBlocking()
            if (found != null) {
                ApiConfig.setBaseUrl(found)
                mainHandler.post { onDone?.invoke(true, found) }
            } else {
                mainHandler.post {
                    onDone?.invoke(false, "未在同 WiFi 发现后端，请确认电脑已启动服务")
                }
            }
        }
    }

    private fun discoverBlocking(): String? {
        for (url in buildCandidateUrls()) {
            if (probe(url)) return url
        }
        val ip = wifiIpv4() ?: return null
        val parts = ip.split(".")
        if (parts.size != 4) return null
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
        val found = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        for (host in 1..254) {
            if (found.get() != null) break
            val targetHost = host
            scanPool.execute {
                if (found.get() != null) return@execute
                val url = "http://$prefix.$targetHost:8000"
                if (probe(url, 600)) {
                    if (found.compareAndSet(null, url)) {
                        latch.countDown()
                    }
                }
            }
        }
        latch.await(SCAN_TIMEOUT_SEC, TimeUnit.SECONDS)
        return found.get()
    }

    private fun wifiIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
