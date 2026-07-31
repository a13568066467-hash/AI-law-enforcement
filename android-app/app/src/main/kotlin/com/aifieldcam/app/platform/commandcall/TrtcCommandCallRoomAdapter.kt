package com.aifieldcam.app.platform.commandcall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import com.tencent.trtc.TRTCCloud
import com.tencent.trtc.TRTCCloudDef
import com.tencent.trtc.TRTCCloudListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真 TRTC 房间适配器：自定义视频旁路 + 自定义 PCM 上行；禁止 SDK 自采相机。
 * JVM 单测勿实例化本类（依赖 LiteAV native）。
 */
class TrtcCommandCallRoomAdapter(
    context: Context,
    private val joinTimeoutMs: Long = JOIN_TIMEOUT_MS,
) : CommandCallRoomAdapter {

    private val appContext = context.applicationContext

    @Volatile
    override var state: CommandCallRoomState = CommandCallRoomState.IDLE
        private set

    @Volatile
    private var customVideoEnabled: Boolean = false

    @Volatile
    private var localAudioMuted: Boolean = true

    @Volatile
    private var customAudioEnabled: Boolean = false

    private val trtc: TRTCCloud = TRTCCloud.sharedInstance(appContext)

    private val joinResultCode = AtomicInteger(0)

    @Volatile
    private var pendingJoinLatch: CountDownLatch? = null

    private val listener = object : TRTCCloudListener() {
        override fun onEnterRoom(result: Long) {
            joinResultCode.set(result.toInt())
            val latch = pendingJoinLatch
            pendingJoinLatch = null
            latch?.countDown()
        }

        override fun onExitRoom(reason: Int) {
            // leave() 同步置 IDLE
        }

        override fun onError(errCode: Int, errMsg: String?, extraInfo: Bundle?) {
            Log.w(TAG, "TRTC onError code=$errCode msg=$errMsg")
            if (state == CommandCallRoomState.JOINING) {
                joinResultCode.set(if (errCode == 0) -1 else -kotlin.math.abs(errCode))
                val latch = pendingJoinLatch
                pendingJoinLatch = null
                latch?.countDown()
            }
        }
    }

    init {
        trtc.setListener(listener)
    }

    override fun isInRoom(): Boolean = state == CommandCallRoomState.IN_ROOM

    override fun join(credentials: CommandCallCredentials): Boolean {
        if (state == CommandCallRoomState.IN_ROOM || state == CommandCallRoomState.JOINING) {
            return false
        }
        state = CommandCallRoomState.JOINING
        localAudioMuted = true
        joinResultCode.set(0)

        val latch = CountDownLatch(1)
        pendingJoinLatch = latch

        val params = TRTCCloudDef.TRTCParams().apply {
            sdkAppId = credentials.sdkAppId
            userId = credentials.userId
            userSig = credentials.userSig
            strRoomId = credentials.roomId
            role = TRTCCloudDef.TRTCRoleAnchor
        }

        return try {
            applyLowLatencyEncoderParams()
            applyClearNetworkQos()
            // 指挥连线/监看一律自定义视频旁路，进房前打开，避免 SDK 自采相机且保证后续 push 生效
            trtc.enableCustomVideoCapture(TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG, true)
            customVideoEnabled = true
            // VIDEOCALL 场景相对直播更偏实时交互
            trtc.enterRoom(params, TRTCCloudDef.TRTC_APP_SCENE_VIDEOCALL)
            val completed = latch.await(joinTimeoutMs, TimeUnit.MILLISECONDS)
            val code = joinResultCode.get()
            if (!completed || code < 0) {
                Log.w(TAG, "join failed completed=$completed code=$code")
                safeExit()
                state = CommandCallRoomState.FAILED
                false
            } else {
                // 监看默认不开自定义音频：开启却不推 PCM 会让 SDK 为音画同步囤视频（常见 1～3s 延迟）
                try {
                    trtc.stopLocalAudio()
                } catch (_: Throwable) {
                }
                trtc.muteLocalAudio(true)
                enableCustomAudioCaptureInternal(false)
                state = CommandCallRoomState.IN_ROOM
                Log.i(TAG, "join ok room=${credentials.roomId} user=${credentials.userId}")
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "join exception", t)
            pendingJoinLatch = null
            safeExit()
            state = CommandCallRoomState.FAILED
            false
        }
    }

    override fun leave() {
        if (state == CommandCallRoomState.IDLE) return
        customVideoEnabled = false
        localAudioMuted = true
        try {
            trtc.enableCustomVideoCapture(TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG, false)
            enableCustomAudioCaptureInternal(false)
            trtc.muteLocalAudio(true)
            trtc.exitRoom()
        } catch (t: Throwable) {
            Log.w(TAG, "leave error", t)
        }
        pendingJoinLatch = null
        state = CommandCallRoomState.IDLE
    }

    override fun enableCustomVideoSource(enabled: Boolean) {
        customVideoEnabled = enabled
        if (state == CommandCallRoomState.IN_ROOM || state == CommandCallRoomState.JOINING) {
            try {
                trtc.enableCustomVideoCapture(TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG, enabled)
            } catch (t: Throwable) {
                Log.w(TAG, "enableCustomVideoSource", t)
            }
        }
    }

    override fun pushVideoFrame(frame: CommandCallVideoFrame) {
        if (!customVideoEnabled || state != CommandCallRoomState.IN_ROOM) return
        try {
            if (frame.hasI420) {
                val videoFrame = TRTCCloudDef.TRTCVideoFrame().apply {
                    pixelFormat = TRTCCloudDef.TRTC_VIDEO_PIXEL_FORMAT_I420
                    bufferType = TRTCCloudDef.TRTC_VIDEO_BUFFER_TYPE_BYTE_ARRAY
                    data = frame.i420Bytes
                    width = frame.width
                    height = frame.height
                    // 0 = 交由 SDK 打点；墙钟时间戳在自定义音频断续时易拉高接收缓冲
                    timestamp = 0L
                }
                trtc.sendCustomVideoData(TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG, videoFrame)
                return
            }
            if (frame.jpegBytes.isEmpty() || frame.width <= 0 || frame.height <= 0) return
            val bitmap = BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size)
                ?: return
            val i420 = bitmapToI420(bitmap)
            val w = bitmap.width
            val h = bitmap.height
            bitmap.recycle()
            val videoFrame = TRTCCloudDef.TRTCVideoFrame().apply {
                pixelFormat = TRTCCloudDef.TRTC_VIDEO_PIXEL_FORMAT_I420
                bufferType = TRTCCloudDef.TRTC_VIDEO_BUFFER_TYPE_BYTE_ARRAY
                data = i420
                width = w
                height = h
                timestamp = 0L
            }
            trtc.sendCustomVideoData(TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG, videoFrame)
        } catch (t: Throwable) {
            Log.w(TAG, "pushVideoFrame", t)
        }
    }

    override fun setLocalAudioMuted(muted: Boolean) {
        localAudioMuted = muted
        try {
            if (!muted) {
                // 对讲开麦时才开自定义音频，并持续推 PCM
                enableCustomAudioCaptureInternal(true)
            }
            trtc.muteLocalAudio(muted)
            if (muted) {
                enableCustomAudioCaptureInternal(false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "setLocalAudioMuted", t)
        }
    }

    override fun isLocalAudioMuted(): Boolean = localAudioMuted

    override fun pushAudioPcm(pcm: ByteArray) {
        if (localAudioMuted || state != CommandCallRoomState.IN_ROOM) return
        if (pcm.isEmpty() || !customAudioEnabled) return
        try {
            val audioFrame = TRTCCloudDef.TRTCAudioFrame().apply {
                data = pcm
                sampleRate = PCM_SAMPLE_RATE
                channel = 1
                timestamp = 0L
            }
            trtc.sendCustomAudioData(audioFrame)
        } catch (t: Throwable) {
            Log.w(TAG, "pushAudioPcm", t)
        }
    }

    private fun enableCustomAudioCaptureInternal(enabled: Boolean) {
        customAudioEnabled = enabled
        try {
            trtc.enableCustomAudioCapture(enabled)
        } catch (t: Throwable) {
            Log.w(TAG, "enableCustomAudioCapture", t)
            customAudioEnabled = false
        }
    }

    /** 与旁路约 15fps / 1920 长边（1080p）对齐。 */
    private fun applyLowLatencyEncoderParams() {
        try {
            val enc = TRTCCloudDef.TRTCVideoEncParam().apply {
                videoResolution = TRTCCloudDef.TRTC_VIDEO_RESOLUTION_1920_1080
                videoResolutionMode = TRTCCloudDef.TRTC_VIDEO_RESOLUTION_MODE_LANDSCAPE
                videoFps = 15
                videoBitrate = 1800
                minVideoBitrate = 800
                enableAdjustRes = false
            }
            trtc.setVideoEncoderParam(enc)
        } catch (t: Throwable) {
            Log.w(TAG, "setVideoEncoderParam", t)
        }
    }

    /** 清晰/低延迟优先：少攒帧，两端画面更跟手（弱网可能更易卡顿）。 */
    private fun applyClearNetworkQos() {
        try {
            val qos = TRTCCloudDef.TRTCNetworkQosParam().apply {
                preference = TRTCCloudDef.TRTC_VIDEO_QOS_PREFERENCE_CLEAR
                controlMode = TRTCCloudDef.VIDEO_QOS_CONTROL_SERVER
            }
            trtc.setNetworkQosParam(qos)
        } catch (t: Throwable) {
            Log.w(TAG, "setNetworkQosParam", t)
        }
    }

    private fun safeExit() {
        try {
            trtc.enableCustomVideoCapture(TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG, false)
            enableCustomAudioCaptureInternal(false)
            trtc.exitRoom()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "TrtcCommandCallRoom"
        const val JOIN_TIMEOUT_MS: Long = 8_000L
        private const val PCM_SAMPLE_RATE = 16_000

        /** ARGB_8888 Bitmap → I420（YYYY… UUU… VVV…）。 */
        internal fun bitmapToI420(bitmap: Bitmap): ByteArray {
            val width = bitmap.width
            val height = bitmap.height
            val argb = IntArray(width * height)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
            val ySize = width * height
            val uvSize = ySize / 4
            val out = ByteArray(ySize + uvSize * 2)
            var yIndex = 0
            var uIndex = ySize
            var vIndex = ySize + uvSize
            var index = 0
            for (j in 0 until height) {
                for (i in 0 until width) {
                    val c = argb[index++]
                    val r = (c shr 16) and 0xff
                    val g = (c shr 8) and 0xff
                    val b = c and 0xff
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    out[yIndex++] = y.coerceIn(0, 255).toByte()
                    if (j % 2 == 0 && i % 2 == 0) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        out[uIndex++] = u.coerceIn(0, 255).toByte()
                        out[vIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
            return out
        }
    }
}
