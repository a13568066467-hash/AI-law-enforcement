/**
 * AI Field Cam 固件状态机
 * 三大功能：录像 / 拍照识图 / AI 助手
 */
#ifndef APP_FSM_H
#define APP_FSM_H

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    GLASS_STATE_IDLE = 0,
    GLASS_STATE_AI_ASSIST,
    GLASS_STATE_CAPTURE,
    GLASS_STATE_RECORD,
} glass_state_t;

/** 与 BLE协议.md SENSOR_NOTIFY STATE 一致 */
typedef enum {
    FSM_SENSOR_IDLE = 0,
    FSM_SENSOR_AI = 1,
    FSM_SENSOR_CAPTURE = 2,
    FSM_SENSOR_RECORD = 3,
} fsm_sensor_state_t;

typedef enum {
    EVT_POWER_LONG_PRESS = 0,
    EVT_POWER_SINGLE_CLICK,
    EVT_TOUCH_DOUBLE_TAP, /* AI 键双击：开/关 AI */
    EVT_VOICE_START_RECORD,
    EVT_VOICE_STOP_RECORD,
    EVT_VOICE_CAPTURE,
    EVT_GLASSES_REMOVED,
    EVT_LOW_BATTERY,
    EVT_CAPTURE_DONE,
    EVT_SHUTTER_LONG_PRESS, /* 快门长按：录像中停止录像 */
    EVT_AI_LISTEN_IDLE_TIMEOUT, /* AI 聆听无活动超时（待机续航） */
} glass_event_t;

/** BLE CMD_WRITE CMD_ID（BLE协议.md） */
typedef enum {
    BLE_CMD_START_RECORD = 0x01,
    BLE_CMD_STOP_RECORD = 0x02,
    BLE_CMD_CAPTURE = 0x03,
    BLE_CMD_START_AI_LISTEN = 0x04,
    BLE_CMD_STOP_AI_LISTEN = 0x05,
    BLE_CMD_SET_VOLUME = 0x06,
    BLE_CMD_POWER_OFF = 0x07,
    BLE_CMD_START_OTA = 0x08,
} ble_cmd_id_t;

void app_fsm_init(void);
void app_fsm_handle_event(glass_event_t evt);
void app_fsm_on_ble_cmd(uint8_t cmd_id);
void app_fsm_on_shutter_click(void);
void app_fsm_on_shutter_long_press(void);
void app_fsm_capture_done(void);
void app_fsm_capture_abort(void);

glass_state_t app_fsm_get_state(void);
fsm_sensor_state_t app_fsm_get_sensor_state(void);
bool app_fsm_is_powered(void);

#if CONFIG_FSM_SELF_TEST
void app_fsm_run_self_test(void);
#endif

#endif /* APP_FSM_H */
