package com.aifieldcam.app.ble

/**
 * 与 docs/BLE协议.md、apptext/common/config.uts 对齐
 */
object BleConfig {
    const val API_BASE_URL = "http://192.168.1.106:8000"
    const val API_AUTO_MOCK = true

    const val DEMO_PHONE = "13800000000"
    const val DEMO_PASSWORD = "demo"

    const val BLE_DEVICE_NAME_PREFIX = "AI-FieldCam"
    const val BLE_SERVICE_UUID = "0000A001-0000-1000-8000-00805F9B34FB"

    const val CHR_CMD_WRITE = "0000A002-0000-1000-8000-00805F9B34FB"
    const val CHR_CMD_NOTIFY = "0000A003-0000-1000-8000-00805F9B34FB"
    const val CHR_IMAGE_TX = "0000A006-0000-1000-8000-00805F9B34FB"
    const val CHR_SENSOR_NOTIFY = "0000A008-0000-1000-8000-00805F9B34FB"

    const val CMD_START_RECORD = 0x01
    const val CMD_STOP_RECORD = 0x02
    const val CMD_CAPTURE = 0x03
    const val CMD_START_AI_LISTEN = 0x04
    const val CMD_STOP_AI_LISTEN = 0x05

    const val EVT_RECORD_STARTED = 0x81
    const val EVT_RECORD_STOPPED = 0x82
    const val EVT_CAPTURE_DONE = 0x83
    const val EVT_LOW_BATTERY = 0x86

    const val FSM_IDLE = 0
    const val FSM_AI = 1
    const val FSM_CAPTURE = 2
    const val FSM_RECORD = 3

    const val SENSOR_FLAG_CHARGING = 0x01

    fun uuidSuffix(uuid: String): String {
        val normalized = uuid.uppercase().replace("-", "")
        return if (normalized.length >= 8) normalized.substring(4, 8) else normalized
    }

    fun charIdMatches(candidateId: String, suffix: String): Boolean {
        return uuidSuffix(candidateId) == suffix.uppercase()
    }

    fun fsmStateLabel(state: Int): String = when (state) {
        FSM_AI -> "AI 聆听"
        FSM_CAPTURE -> "拍照中"
        FSM_RECORD -> "录像中"
        else -> "空闲"
    }
}
