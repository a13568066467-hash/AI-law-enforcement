package com.aifieldcam.app.platform

import android.util.Base64
import android.util.Log
import com.aifieldcam.app.data.ApiClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2：将 [MediaEncoderPipeline] 产出的 NAL 单元 POST 到后端（供 Web H264 解码扩展）。
 */
object HttpNalRelay {

    private const val TAG = "HttpNalRelay"
    /** 约 10fps 上传，减轻网络与 CPU */
    private const val MIN_POST_INTERVAL_MS = 100L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HttpNalRelay").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)
    private var callId: String = ""
    private var lastPostMs = 0L
    private var postedCount = 0L

    private val consumer = object : VideoStreamManager.StreamConsumer {
        override val name: String get() = "HttpNalRelay"

        override fun start() {
            Log.i(TAG, "NAL consumer started")
        }

        override fun onNalUnit(nal: ByteArray) {
            if (!running.get() || callId.isBlank() || nal.isEmpty()) return
            val now = System.currentTimeMillis()
            if (now - lastPostMs < MIN_POST_INTERVAL_MS) return
            lastPostMs = now
            val id = callId
            val b64 = Base64.encodeToString(nal, Base64.NO_WRAP)
            executor.execute {
                ApiClient.postWebRtcNal(id, b64) { ok, err ->
                    if (ok) postedCount++ else if (err.isNotBlank()) {
                        Log.w(TAG, "nal post failed: $err")
                    }
                }
            }
        }

        override fun close() {
            Log.i(TAG, "NAL consumer closed, posted=$postedCount")
        }
    }

    fun start(callId: String) {
        stop()
        if (callId.isBlank()) return
        this.callId = callId
        postedCount = 0
        lastPostMs = 0L
        running.set(true)
        VideoStreamManager.startWebRtc(consumer)
        Log.i(TAG, "NAL relay started callId=$callId")
    }

    fun stop() {
        running.set(false)
        VideoStreamManager.stopWebRtc()
        callId = ""
    }

    fun isRunning(): Boolean = running.get()
}
