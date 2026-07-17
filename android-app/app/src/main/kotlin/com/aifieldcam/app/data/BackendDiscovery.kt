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
    private const val SCAN_TIMEOUT_SEC = 20L

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 串行调度发现流程，避免与 scanPool 互相阻塞 */
    private val orchestrator = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BackendDiscovery").apply { isDaemon = true }
    }
    private val scanPool = Executors.newFixedThreadPool(32)

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
            val ok = conn.responseCode == 200 && isOurHealthPayload(readText(conn))
            conn.disconnect()
            if (ok) {
                markLastGood(base)
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun readText(conn: HttpURLConnection): String {
        return try {
            val stream = if (conn.responseCode in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream
            }
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /** 识别本仓库 FastAPI /health，避免误命中同端口其他服务 */
    private fun isOurHealthPayload(body: String): Boolean {
        if (body.isBlank()) return false
        return body.contains("\"ok\"") &&
            (body.contains("officer_db") || body.contains("face_engine"))
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
        orchestrator.execute {
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
        orchestrator.execute {
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
            val candidates = mutableListOf<Pair<Int, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("ppp") || name.contains("vpn")) continue
                val priority = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("wifi") -> 1
                    name.startsWith("eth") -> 2
                    else -> 4
                }
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (isPrivateLan(host)) {
                            candidates.add(priority to host)
                        }
                    }
                }
            }
            candidates.minByOrNull { it.first }?.second
        } catch (_: Exception) {
            null
        }
    }

    private fun isPrivateLan(host: String): Boolean {
        if (host.startsWith("192.168.") || host.startsWith("10.")) return true
        if (!host.startsWith("172.")) return false
        val second = host.split(".").getOrNull(1)?.toIntOrNull() ?: return false
        return second in 16..31
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
