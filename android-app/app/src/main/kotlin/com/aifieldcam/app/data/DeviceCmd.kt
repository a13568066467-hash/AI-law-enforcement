package com.aifieldcam.app.data

/** 云端 AI 返回的设备控制指令（映射本机录像/拍照） */
object DeviceCmd {
    const val CMD_START_RECORD = 0x01
    const val CMD_STOP_RECORD = 0x02
    const val CMD_CAPTURE = 0x03
    const val CMD_START_AI_LISTEN = 0x04
    const val CMD_STOP_AI_LISTEN = 0x05

    const val FSM_IDLE = 0
    const val FSM_AI = 1
    const val FSM_CAPTURE = 2
    const val FSM_RECORD = 3

    fun fsmStateLabel(state: Int): String = when (state) {
        FSM_AI -> "AI 聆听"
        FSM_CAPTURE -> "拍照中"
        FSM_RECORD -> "录像中"
        else -> "空闲"
    }

    fun currentFsmState(isRecording: Boolean): Int =
        if (isRecording) FSM_RECORD else FSM_IDLE
}
