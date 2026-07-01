package com.aifieldcam.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** 本机 TTS 播报（PRD：识图说明与 AI 回复「展示/播报」） */
object TtsSpeaker {

    private const val TAG = "TtsSpeaker"
    private const val MAX_CHARS = 500

    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
                ready.set(true)
                Log.i(TAG, "TTS ready")
            } else {
                Log.w(TAG, "TTS init failed: $status")
            }
        }
    }

    fun speak(text: String) {
        val line = text.trim()
        if (line.isEmpty() || !ready.get()) return
        val utterance = if (line.length > MAX_CHARS) line.take(MAX_CHARS) + "…" else line
        tts?.speak(utterance, TextToSpeech.QUEUE_FLUSH, null, "aifieldcam_tts")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready.set(false)
    }
}
