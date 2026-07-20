package com.aifieldcam.app.platform

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PTT 长按短语音采集（16kHz PCM）。
 * 循环录像中优先走 [PcmTeeBridge] 旁路伴随音，避免第二路 AudioRecord 抢麦导致录像断声。
 */
object VoiceCaptureHelper {

    private const val TAG = "VoiceCapture"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val MAX_DURATION_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val capturing = AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? = null
    private var teeSubscription: PcmTeeBridge.Subscription? = null

    fun isCapturing(): Boolean = capturing.get()

    /** 兼容需要整段 PCM 的旧调用；实时语音应直接使用 [startStreaming]。 */
    fun start(onStarted: () -> Unit, onError: (String) -> Unit) {
        val collected = ByteArrayOutputStream(SAMPLE_RATE * 2)
        legacyCollector = collected
        startStreaming(
            onPcm = { pcm ->
                synchronized(collected) {
                    collected.write(pcm)
                }
            },
            onStarted = onStarted,
            onError = onError,
        )
    }

    @Volatile
    private var legacyCollector: ByteArrayOutputStream? = null

    fun startStreaming(
        onPcm: (ByteArray) -> Unit,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!capturing.compareAndSet(false, true)) {
            onError("已在采集语音")
            return
        }
        executor.execute {
            try {
                if (MediaEncoderPipeline.canProvidePcmTee()) {
                    val subscription = PcmTeeBridge.subscribe { pcm ->
                        if (capturing.get()) onPcm(pcm)
                    }
                    if (subscription != null) {
                        teeSubscription = subscription
                        Log.i(TAG, "voice capture via recording PCM tee (共麦)")
                        mainHandler.post { onStarted() }
                        return@execute
                    }
                }
                startDedicatedMic(onStarted, onError)
                captureDedicatedLoop(onPcm, onError)
            } catch (e: Exception) {
                Log.e(TAG, "start voice capture failed", e)
                cleanupCapture()
                mainHandler.post { onError(e.message ?: "麦克风异常") }
            }
        }
    }

    private fun startDedicatedMic(onStarted: () -> Unit, onError: (String) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            capturing.set(false)
            mainHandler.post { onError("音频设备不可用") }
            return
        }
        val readBytes = READ_FRAMES * 2
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            maxOf(minBuf, readBytes * 4),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            capturing.set(false)
            mainHandler.post { onError("无法初始化麦克风") }
            return
        }
        audioRecord = recorder
        recorder.startRecording()
        Log.i(TAG, "voice capture started (dedicated mic)")
        mainHandler.post { onStarted() }
    }

    private fun captureDedicatedLoop(
        onPcm: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        val recorder = audioRecord ?: return
        val buffer = ByteArray(READ_FRAMES * 2)
        try {
            while (capturing.get()) {
                val read = recorder.read(buffer, 0, buffer.size)
                when {
                    read > 0 -> onPcm(buffer.copyOf(read))
                    read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        throw IllegalStateException("麦克风读取失败: $read")
                    }
                }
            }
        } catch (e: Exception) {
            if (capturing.getAndSet(false)) {
                mainHandler.post { onError(e.message ?: "麦克风读取失败") }
            }
        } finally {
            cleanupCapture()
        }
    }

    fun stop(onResult: (ByteArray?) -> Unit) {
        val collector = legacyCollector
        legacyCollector = null
        stopStreaming {
            val bytes = collector?.let {
                synchronized(it) { it.toByteArray() }
            }
            onResult(bytes?.takeIf { it.isNotEmpty() })
        }
    }

    fun stopStreaming(onStopped: () -> Unit = {}) {
        if (!capturing.getAndSet(false)) {
            mainHandler.post(onStopped)
            return
        }
        teeSubscription?.let(PcmTeeBridge::unsubscribe)
        teeSubscription = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        executor.execute {
            cleanupCapture()
            mainHandler.post(onStopped)
        }
    }

    private fun cleanupCapture() {
        teeSubscription?.let(PcmTeeBridge::unsubscribe)
        teeSubscription = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        capturing.set(false)
    }

    fun maxDurationMs(): Long = MAX_DURATION_MS

    internal fun sampleRateForTest(): Int = SAMPLE_RATE

    private const val READ_FRAMES = 320
}
