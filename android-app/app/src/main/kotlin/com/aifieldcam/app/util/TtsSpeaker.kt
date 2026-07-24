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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    private val activeUtteranceId = AtomicReference<String?>(null)
    private val utteranceSequence = AtomicLong()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var pendingSpeak: String? = null

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
                ready.set(true)
                Log.i(TAG, "TTS ready")
                val pending = pendingSpeak
                pendingSpeak = null
                if (!pending.isNullOrBlank()) {
                    mainHandler.post { speakNow(pending) }
                }
            } else {
                Log.w(TAG, "TTS init failed: $status")
            }
        }
        tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (activeUtteranceId.get() == utteranceId) setSpeaking(true)
                }

                override fun onDone(utteranceId: String?) {
                    finishSpeech(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    finishSpeech(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    finishSpeech(utteranceId)
                }
            },
        )
    }

    fun speak(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        if (!ready.get() || tts == null) {
            pendingSpeak = line
            Log.i(TAG, "TTS not ready, queue: ${line.take(40)}")
            return
        }
        speakNow(line)
    }

    private fun speakNow(line: String) {
        val utterance = if (line.length > MAX_CHARS) line.take(MAX_CHARS) + "…" else line
        val utteranceId = "aifieldcam_tts_${utteranceSequence.incrementAndGet()}"
        val engine = tts
        if (engine == null) {
            setSpeaking(false)
            return
        }
        activeUtteranceId.set(utteranceId)
        val code = engine.speak(
            utterance,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId,
        )
        if (code == TextToSpeech.SUCCESS && activeUtteranceId.get() == utteranceId) {
            setSpeaking(true)
        } else if (code != TextToSpeech.SUCCESS && activeUtteranceId.compareAndSet(utteranceId, null)) {
            setSpeaking(false)
            Log.w(TAG, "TTS speak failed code=$code text=${utterance.take(40)}")
        }
    }

    fun stop() {
        activeUtteranceId.set(null)
        tts?.stop()
        setSpeaking(false)
    }

    fun shutdown() {
        activeUtteranceId.set(null)
        pendingSpeak = null
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

    fun isSpeaking(): Boolean = speaking.get()

    private fun finishSpeech(utteranceId: String?) {
        if (utteranceId != null && activeUtteranceId.compareAndSet(utteranceId, null)) {
            setSpeaking(false)
        }
    }

    private fun setSpeaking(active: Boolean) {
        if (!speaking.compareAndSet(!active, active)) return
        mainHandler.post {
            listeners.forEach { it.onSpeakingChanged(active) }
        }
    }
}
