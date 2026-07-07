package com.aifieldcam.app.platform

/**
 * 交互设计 §5：录像 / 拍照 / 录音 / AI 全局互斥（亮屏、息屏、无障碍侧键共用）。
 */
object MediaInteractionPolicy {

    data class State(
        val videoRecording: Boolean = false,
        val videoPreparing: Boolean = false,
        val stillCapturing: Boolean = false,
        val audioRecording: Boolean = false,
        val aiBusy: Boolean = false,
        val lowBatteryBlock: Boolean = false,
        val hasCameraPermission: Boolean = true,
        val hasRecordPermissions: Boolean = true,
        val isDsjDevice: Boolean = true,
    ) {
        val videoBusy: Boolean get() = videoRecording || videoPreparing
        val cameraBusy: Boolean get() = videoBusy || stillCapturing
    }

    sealed class Block(val message: String) {
        data object NotDsjDevice : Block("请在执法仪本机使用")
        data object LowBattery : Block("电量过低，已禁止新录像与 AI")
        data object AiBusy : Block("AI 对话中，请先结束")
        data object AudioWhileVideo : Block("录像中请先停止录像")
        data object VideoWhileAudio : Block("录音中请先停止录音")
        data object CaptureWhileVideo : Block("录像中无法拍照")
        data object CaptureWhilePreparing : Block("正在启动录像，请稍候")
        data object CaptureWhileCapturing : Block("正在拍照，请稍候")
        data object CaptureWhileAudio : Block("录音中请先停止录音")
        data object VideoWhileCapturing : Block("正在拍照，请稍候")
        data object VideoPreparing : Block("正在启动录像，请稍候")
        data object AlreadyRecording : Block("已在录像中")
        data object NotRecording : Block("当前未在录像")
        data object NotAudioRecording : Block("当前未在录音")
        data object AlreadyAudio : Block("已在录音中")
        data object MissingCamera : Block("需要相机权限")
        data object MissingRecordPerms : Block("需要相机与麦克风权限")
    }

    fun canStartVideo(state: State): Block? = when {
        !state.isDsjDevice -> Block.NotDsjDevice
        state.lowBatteryBlock -> Block.LowBattery
        state.aiBusy -> Block.AiBusy
        state.audioRecording -> Block.VideoWhileAudio
        state.stillCapturing -> Block.VideoWhileCapturing
        state.videoPreparing -> Block.VideoPreparing
        state.videoRecording -> Block.AlreadyRecording
        !state.hasRecordPermissions -> Block.MissingRecordPerms
        else -> null
    }

    fun canStopVideo(state: State): Block? = when {
        !state.isDsjDevice -> Block.NotDsjDevice
        !state.videoBusy -> Block.NotRecording
        else -> null
    }

    fun canCapture(state: State): Block? = when {
        !state.isDsjDevice -> Block.NotDsjDevice
        state.lowBatteryBlock -> Block.LowBattery
        state.videoRecording -> Block.CaptureWhileVideo
        state.videoPreparing -> Block.CaptureWhilePreparing
        state.stillCapturing -> Block.CaptureWhileCapturing
        state.audioRecording -> Block.CaptureWhileAudio
        !state.hasCameraPermission -> Block.MissingCamera
        else -> null
    }

    fun canStartAudio(state: State): Block? = when {
        !state.isDsjDevice -> Block.NotDsjDevice
        state.videoBusy -> Block.AudioWhileVideo
        state.audioRecording -> Block.AlreadyAudio
        !state.hasRecordPermissions -> Block.MissingRecordPerms
        else -> null
    }

    fun canStopAudio(state: State): Block? = when {
        !state.isDsjDevice -> Block.NotDsjDevice
        !state.audioRecording -> Block.NotAudioRecording
        else -> null
    }
}
