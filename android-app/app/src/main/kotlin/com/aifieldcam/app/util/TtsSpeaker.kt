package com.aifieldcam.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** 本机 TTS 播报（PRD：识图说明与 AI 回复「展示/播报」） */
object TtsSpeaker {

    fun interface Listener {
        fun onSpeakingChanged(speaking: Boolean)
    }

    private const val TAG = "TtsSpeaker"
    private const val MAX_CHARS = 500

    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

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
        tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    setSpeaking(true)
                }

                override fun onDone(utteranceId: String?) {
                    setSpeaking(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    setSpeaking(false)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    setSpeaking(false)
                }
            },
        )
    }

    fun speak(text: String) {
        val line = text.trim()
        if (line.isEmpty() || !ready.get()) return
        val utterance = if (line.length > MAX_CHARS) line.take(MAX_CHARS) + "…" else line
        val code = tts?.speak(
            utterance,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "aifieldcam_tts_${System.currentTimeMillis()}",
        )
        if (code == TextToSpeech.SUCCESS) {
            setSpeaking(true)
        }
    }

    fun stop() {
        tts?.stop()
        setSpeaking(false)
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready.set(false)
        setSpeaking(false)
        listeners.clear()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onSpeakingChanged(speaking.get())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun setSpeaking(active: Boolean) {
        if (!speaking.compareAndSet(!active, active)) return
        mainHandler.post {
            listeners.forEach { it.onSpeakingChanged(active) }
        }
    }
}
