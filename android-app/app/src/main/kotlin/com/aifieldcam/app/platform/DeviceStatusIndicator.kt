package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 状态指示灯（厂商说明书 §三）：
 * - 待机：绿灯常亮
 * - 录音：黄灯闪烁
 * - 录像：红灯闪烁
 * - 拍照：红灯闪一次
 * - 充电：红灯常亮
 * - 充满：绿灯常亮
 */
object DeviceStatusIndicator {

    private const val TAG = "StatusLed"
    private const val BLINK_MS = 500L

    private val handler = Handler(Looper.getMainLooper())

    private var charging = false
    private var fullCharge = false
    private var videoRecording = false
    private var audioRecording = false
    private var blinkOn = false
    private var blinkScheduled = false

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (!Ze69Hardware.isZe69Platform) return
            blinkOn = !blinkOn
            applyBlinkFrame()
            handler.postDelayed(this, BLINK_MS)
        }
    }

    fun onBatteryChanged(isCharging: Boolean, isFull: Boolean) {
        charging = isCharging
        fullCharge = isFull
        refresh()
    }

    fun setVideoRecording(active: Boolean) {
        videoRecording = active
        refresh()
    }

    fun setAudioRecording(active: Boolean) {
        audioRecording = active
        refresh()
    }

    /** 说明书：拍照时红灯闪一次 */
    fun pulsePhotoCapture() {
        if (!Ze69Hardware.isZe69Platform) return
        stopBlink()
        Ze69Hardware.setRgRed(true)
        Ze69Hardware.setRgGreen(false)
        handler.postDelayed({
            refresh()
        }, 200L)
    }

    fun refresh() {
        if (!Ze69Hardware.isZe69Platform) return
        when {
            videoRecording -> showVideoBlink()
            audioRecording -> showAudioBlink()
            charging && fullCharge -> showFullCharge()
            charging -> showCharging()
            else -> showStandby()
        }
    }

    fun resetAll() {
        charging = false
        fullCharge = false
        videoRecording = false
        audioRecording = false
        stopBlink()
        if (Ze69Hardware.isZe69Platform) {
            Ze69Hardware.setRgbRed(false)
            Ze69Hardware.setRgbGreen(false)
            Ze69Hardware.setRgbBlue(false)
            Ze69Hardware.setRgRed(false)
            Ze69Hardware.setRgGreen(false)
        }
        refresh()
    }

    private fun showStandby() {
        stopBlink()
        Ze69Hardware.setRgRed(false)
        Ze69Hardware.setRgGreen(true)
        Log.d(TAG, "standby: green steady")
    }

    private fun showCharging() {
        stopBlink()
        Ze69Hardware.setRgRed(true)
        Ze69Hardware.setRgGreen(false)
        Log.d(TAG, "charging: red steady")
    }

    private fun showFullCharge() {
        stopBlink()
        Ze69Hardware.setRgRed(false)
        Ze69Hardware.setRgGreen(true)
        Log.d(TAG, "full charge: green steady")
    }

    private fun showVideoBlink() {
        startBlink()
        Log.d(TAG, "video: red blink")
    }

    private fun showAudioBlink() {
        startBlink()
        Log.d(TAG, "audio: yellow blink")
    }

    private fun startBlink() {
        if (!blinkScheduled) {
            blinkScheduled = true
            blinkOn = true
            applyBlinkFrame()
            handler.postDelayed(blinkRunnable, BLINK_MS)
        }
    }

    private fun stopBlink() {
        blinkScheduled = false
        handler.removeCallbacks(blinkRunnable)
    }

    private fun applyBlinkFrame() {
        when {
            videoRecording -> {
                Ze69Hardware.setRgRed(blinkOn)
                Ze69Hardware.setRgGreen(false)
            }
            audioRecording -> {
                // 黄灯 ≈ 红 + 绿 同闪
                Ze69Hardware.setRgRed(blinkOn)
                Ze69Hardware.setRgGreen(blinkOn)
            }
        }
    }
}
