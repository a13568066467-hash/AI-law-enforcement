/**
 * 状态机：按键 / 语音 / BLE 命令统一调度
 */
#include "app_fsm.h"
#include "audio_in.h"
#include "audio_prompt.h"
#include "ble_service.h"
#include "camera.h"
#include "power_mgr.h"
#include "standby_mgr.h"

#include "esp_err.h"
#include "esp_log.h"
#include "esp_timer.h"

static const char *TAG = "fsm";

static glass_state_t current_state = GLASS_STATE_IDLE;
static glass_state_t capture_return_state = GLASS_STATE_IDLE;
static bool powered = true;
static int64_t record_start_us = 0;

/** BLE ERROR codes（BLE协议.md 0x87 payload） */
#define BLE_ERR_AI_WHILE_RECORD  0x01
#define BLE_ERR_CAPTURE_FAILED   0x02
#define BLE_ERR_CAPTURE_BLOCKED  0x03
#define BLE_ERR_OTA_UNSUPPORTED  0x04

static void ble_notify_error(uint8_t code)
{
    ble_notify_event(0x87, &code, 1);
}

static fsm_sensor_state_t state_to_sensor(glass_state_t s)
{
    switch (s) {
    case GLASS_STATE_AI_ASSIST:
        return FSM_SENSOR_AI;
    case GLASS_STATE_CAPTURE:
        return FSM_SENSOR_CAPTURE;
    case GLASS_STATE_RECORD:
        return FSM_SENSOR_RECORD;
    default:
        return FSM_SENSOR_IDLE;
    }
}

static void leave_ai_assist(void)
{
    if (current_state != GLASS_STATE_AI_ASSIST) {
        return;
    }
    audio_in_stop_stream();
    /* TODO: ble_notify(0x85 AI_IDLE); */
    ble_notify_event(0x85, NULL, 0);
    ESP_LOGI(TAG, "AI assist stopped");
}

static void leave_record(void)
{
    if (current_state != GLASS_STATE_RECORD) {
        return;
    }

    uint32_t duration_ms = 0;
    if (record_start_us > 0) {
        duration_ms = (uint32_t)((esp_timer_get_time() - record_start_us) / 1000);
    }
    record_start_us = 0;

    (void)camera_stop_record();

    uint8_t *vdata = NULL;
    size_t vlen = 0;
    esp_err_t verr = camera_take_record_file(&vdata, &vlen);
    uint32_t file_size = (verr == ESP_OK && vdata && vlen > 0) ? (uint32_t)vlen : 0;
    uint8_t pl[8] = {
        (uint8_t)((duration_ms >> 24) & 0xFF),
        (uint8_t)((duration_ms >> 16) & 0xFF),
        (uint8_t)((duration_ms >> 8) & 0xFF),
        (uint8_t)(duration_ms & 0xFF),
        (uint8_t)((file_size >> 24) & 0xFF),
        (uint8_t)((file_size >> 16) & 0xFF),
        (uint8_t)((file_size >> 8) & 0xFF),
        (uint8_t)(file_size & 0xFF),
    };
    ble_notify_event(0x82, pl, sizeof(pl));
    ESP_LOGI(TAG, "record stopped duration=%u ms file=%u", (unsigned)duration_ms,
             (unsigned)file_size);

    if (file_size > 0 && vdata && ble_service_is_connected()) {
        uint32_t file_id = (uint32_t)(esp_timer_get_time() / 1000ULL);
        ble_send_video(vdata, vlen, file_id);
    }
    camera_release_record_data(vdata);
}

static void enter_state(glass_state_t s)
{
    current_state = s;
    ESP_LOGI(TAG, "state -> %d (sensor %d)", (int)s, (int)state_to_sensor(s));
    standby_mgr_on_state_changed(s);
    /* TODO: led_update(); */
    ble_service_publish_sensor_state();
}

static bool can_start_capture(void)
{
    return powered && current_state != GLASS_STATE_RECORD && current_state != GLASS_STATE_CAPTURE;
}

static void capture_begin(void)
{
    if (!can_start_capture()) {
        ESP_LOGW(TAG, "capture blocked (power=%d state=%d)", powered, (int)current_state);
        if (current_state == GLASS_STATE_RECORD) {
            ble_notify_error(BLE_ERR_CAPTURE_BLOCKED);
        } else if (!powered) {
            ble_notify_error(BLE_ERR_CAPTURE_FAILED);
        }
        return;
    }
    capture_return_state =
        (current_state == GLASS_STATE_AI_ASSIST) ? GLASS_STATE_AI_ASSIST : GLASS_STATE_IDLE;
    enter_state(GLASS_STATE_CAPTURE);
    ESP_LOGI(TAG, "capture started (return to %d)", (int)capture_return_state);

#if CONFIG_CAMERA_ENABLE
    if (camera_is_ready()) {
        uint8_t *jpeg = NULL;
        size_t len = 0;
        esp_err_t err = camera_capture_jpeg(&jpeg, &len);
        if (err == ESP_OK && jpeg && len > 0) {
            ESP_LOGI(TAG, "captured JPEG %u bytes", (unsigned)len);
            ble_send_jpeg(jpeg, len);
            camera_release_jpeg(jpeg);
            app_fsm_capture_done();
            return;
        }
        ESP_LOGW(TAG, "camera capture failed %s — abort", esp_err_to_name(err));
        ble_notify_error(BLE_ERR_CAPTURE_FAILED);
        app_fsm_capture_abort();
        return;
    }
#endif
    /* 无相机：通知 App，避免一直等待 IMAGE_TX */
    ble_notify_error(BLE_ERR_CAPTURE_FAILED);
    app_fsm_capture_abort();
}

static void fsm_force_idle(void)
{
    capture_return_state = GLASS_STATE_IDLE;
    if (current_state == GLASS_STATE_CAPTURE) {
        app_fsm_capture_done();
    }
    if (current_state == GLASS_STATE_RECORD) {
        leave_record();
    }
    if (current_state == GLASS_STATE_AI_ASSIST) {
        leave_ai_assist();
    }
    enter_state(GLASS_STATE_IDLE);
}

static bool start_record(void)
{
    if (!powered) {
        return false;
    }
    if (!power_mgr_can_record()) {
        ESP_LOGW(TAG, "battery too low — record blocked");
        audio_prompt_low_battery(power_mgr_get_percent());
        return false;
    }
    if (current_state == GLASS_STATE_RECORD) {
        return true;
    }
    if (current_state == GLASS_STATE_CAPTURE) {
        ESP_LOGW(TAG, "capture in progress — record blocked");
        return false;
    }
    if (current_state == GLASS_STATE_AI_ASSIST) {
        leave_ai_assist();
    }
    enter_state(GLASS_STATE_RECORD);
    record_start_us = esp_timer_get_time();
    (void)camera_start_record();
    ble_notify_event(0x81, NULL, 0);
    ESP_LOGI(TAG, "record started");
    return true;
}

static bool stop_record(void)
{
    if (current_state != GLASS_STATE_RECORD) {
        return false;
    }
    leave_record();
    enter_state(GLASS_STATE_IDLE);
    return true;
}

static bool start_ai_assist(void)
{
    if (!powered) {
        return false;
    }
    if (!power_mgr_can_use_ai()) {
        ESP_LOGW(TAG, "battery too low — AI blocked");
        audio_prompt_low_battery(power_mgr_get_percent());
        return false;
    }
    if (current_state == GLASS_STATE_RECORD) {
        ESP_LOGW(TAG, "recording — AI blocked");
        ble_notify_error(BLE_ERR_AI_WHILE_RECORD);
        return false;
    }
    if (current_state == GLASS_STATE_CAPTURE) {
        ESP_LOGW(TAG, "capture in progress — AI blocked");
        return false;
    }
    if (current_state == GLASS_STATE_AI_ASSIST) {
        return true;
    }
    enter_state(GLASS_STATE_AI_ASSIST);
    audio_in_start_stream();
    ble_notify_event(0x84, NULL, 0);
    /* TODO: Opus encode from audio_in_read_denoised_mono → AUDIO_TX */
    ESP_LOGI(TAG, "AI assist listening");
    return true;
}

static bool stop_ai_assist(void)
{
    if (current_state != GLASS_STATE_AI_ASSIST) {
        return false;
    }
    leave_ai_assist();
    enter_state(GLASS_STATE_IDLE);
    return true;
}

glass_state_t app_fsm_get_state(void)
{
    return current_state;
}

fsm_sensor_state_t app_fsm_get_sensor_state(void)
{
    return state_to_sensor(current_state);
}

bool app_fsm_is_powered(void)
{
    return powered;
}

void app_fsm_init(void)
{
    current_state = GLASS_STATE_IDLE;
    capture_return_state = GLASS_STATE_IDLE;
    powered = true;
    record_start_us = 0;
    ESP_LOGI(TAG, "FSM ready");
}

void app_fsm_capture_done(void)
{
    if (current_state != GLASS_STATE_CAPTURE) {
        ESP_LOGW(TAG, "capture_done ignored (state=%d)", (int)current_state);
        return;
    }
    enter_state(capture_return_state);
    ESP_LOGI(TAG, "capture done");
}

void app_fsm_capture_abort(void)
{
    if (current_state != GLASS_STATE_CAPTURE) {
        return;
    }
    ESP_LOGI(TAG, "capture aborted -> state %d", (int)capture_return_state);
    app_fsm_capture_done();
}

void app_fsm_on_ble_cmd(uint8_t cmd_id)
{
    standby_mgr_notify_activity();
    switch (cmd_id) {
    case BLE_CMD_START_RECORD:
        (void)start_record();
        break;
    case BLE_CMD_STOP_RECORD:
        (void)stop_record();
        break;
    case BLE_CMD_CAPTURE:
        capture_begin();
        break;
    case BLE_CMD_START_AI_LISTEN:
        (void)start_ai_assist();
        break;
    case BLE_CMD_STOP_AI_LISTEN:
        (void)stop_ai_assist();
        break;
    case BLE_CMD_SET_VOLUME:
        ESP_LOGI(TAG, "SET_VOLUME via BLE (stub)");
        break;
    case BLE_CMD_POWER_OFF:
        if (powered) {
            powered = false;
            fsm_force_idle();
            ESP_LOGI(TAG, "power OFF via BLE");
        }
        break;
    case BLE_CMD_START_OTA:
        ble_notify_error(BLE_ERR_OTA_UNSUPPORTED);
        break;
    default:
        ESP_LOGW(TAG, "unknown BLE cmd 0x%02x", cmd_id);
        break;
    }
}

void app_fsm_handle_event(glass_event_t evt)
{
    switch (evt) {
    case EVT_POWER_LONG_PRESS:
        powered = !powered;
        ESP_LOGI(TAG, "power %s", powered ? "ON" : "OFF");
        if (!powered) {
            fsm_force_idle();
        }
        break;

    case EVT_POWER_SINGLE_CLICK:
        break;

    case EVT_TOUCH_DOUBLE_TAP:
        if (!powered) {
            ESP_LOGW(TAG, "AI key ignored — power off");
            break;
        }
        /* 拍照传图中：双击视为取消，回 IDLE（避免无法退出） */
        if (current_state == GLASS_STATE_CAPTURE) {
            app_fsm_capture_abort();
            break;
        }
        if (current_state == GLASS_STATE_RECORD) {
            ESP_LOGW(TAG, "AI key ignored — recording (stop record first)");
            break;
        }
        if (current_state == GLASS_STATE_AI_ASSIST) {
            (void)stop_ai_assist();
        } else {
            (void)start_ai_assist();
        }
        break;

    case EVT_SHUTTER_LONG_PRESS:
        if (!powered) {
            break;
        }
        if (current_state == GLASS_STATE_RECORD) {
            (void)stop_record();
            ESP_LOGI(TAG, "shutter long: record stopped");
        } else {
            ESP_LOGD(TAG, "shutter long: no action in state %d", (int)current_state);
        }
        break;

    case EVT_VOICE_START_RECORD:
        (void)start_record();
        break;

    case EVT_VOICE_STOP_RECORD:
        (void)stop_record();
        break;

    case EVT_VOICE_CAPTURE:
        capture_begin();
        break;

    case EVT_CAPTURE_DONE:
        app_fsm_capture_done();
        break;

    case EVT_GLASSES_REMOVED:
        fsm_force_idle();
        ESP_LOGI(TAG, "glasses removed / idle sleep");
        break;

    case EVT_LOW_BATTERY:
        fsm_force_idle();
        ESP_LOGW(TAG, "low battery — forced idle");
        break;

    case EVT_AI_LISTEN_IDLE_TIMEOUT:
        if (current_state == GLASS_STATE_AI_ASSIST) {
            (void)stop_ai_assist();
            ESP_LOGI(TAG, "AI listen idle timeout — back to idle (standby)");
        }
        break;

    default:
        ESP_LOGW(TAG, "unhandled event %d", (int)evt);
        break;
    }
}

void app_fsm_on_shutter_click(void)
{
    if (!powered) {
        ESP_LOGW(TAG, "shutter ignored — power off");
        return;
    }
    if (current_state == GLASS_STATE_RECORD) {
        ESP_LOGW(TAG, "shutter ignored — recording (long press to stop)");
        return;
    }
    capture_begin();
}

void app_fsm_on_shutter_long_press(void)
{
    app_fsm_handle_event(EVT_SHUTTER_LONG_PRESS);
}

#if CONFIG_FSM_SELF_TEST

static void test_enter_capture(glass_state_t return_to)
{
    capture_return_state = return_to;
    enter_state(GLASS_STATE_CAPTURE);
}

typedef struct {
    const char *name;
    glass_state_t expect_state;
    bool expect_powered;
} fsm_test_step_t;

static int run_step(const char *tag, const fsm_test_step_t *exp)
{
    int ok = 1;
    if (app_fsm_get_state() != exp->expect_state) {
        ESP_LOGE(TAG, "[FAIL] %s: state %d expect %d", tag, (int)app_fsm_get_state(),
                 (int)exp->expect_state);
        ok = 0;
    }
    if (app_fsm_is_powered() != exp->expect_powered) {
        ESP_LOGE(TAG, "[FAIL] %s: powered %d expect %d", tag, app_fsm_is_powered(),
                 exp->expect_powered);
        ok = 0;
    }
    if (ok) {
        ESP_LOGI(TAG, "[PASS] %s", tag);
    }
    return ok;
}

void app_fsm_run_self_test(void)
{
    int pass = 0;
    int total = 0;
    const fsm_test_step_t *exp;
    fsm_test_step_t step;

    ESP_LOGI(TAG, "======== FSM self-test start ========");

    app_fsm_init();
    step = (fsm_test_step_t){ "init", GLASS_STATE_IDLE, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    app_fsm_on_ble_cmd(BLE_CMD_START_RECORD);
    step = (fsm_test_step_t){ "ble start record", GLASS_STATE_RECORD, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    /* 录像中禁止 AI */
    app_fsm_handle_event(EVT_TOUCH_DOUBLE_TAP);
    step = (fsm_test_step_t){ "AI blocked while record", GLASS_STATE_RECORD, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    app_fsm_on_ble_cmd(BLE_CMD_STOP_RECORD);
    step = (fsm_test_step_t){ "ble stop record", GLASS_STATE_IDLE, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    app_fsm_handle_event(EVT_TOUCH_DOUBLE_TAP);
    step = (fsm_test_step_t){ "double-tap AI on", GLASS_STATE_AI_ASSIST, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    app_fsm_handle_event(EVT_TOUCH_DOUBLE_TAP);
    step = (fsm_test_step_t){ "double-tap AI off", GLASS_STATE_IDLE, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    /* 拍照后回到 IDLE */
    app_fsm_on_shutter_click();
    step = (fsm_test_step_t){ "shutter capture done", GLASS_STATE_IDLE, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    /* AI 中拍照应回到 AI */
    app_fsm_handle_event(EVT_TOUCH_DOUBLE_TAP);
    app_fsm_on_shutter_click();
    step = (fsm_test_step_t){ "capture during AI returns AI", GLASS_STATE_AI_ASSIST, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }
    app_fsm_handle_event(EVT_TOUCH_DOUBLE_TAP);

    /* 关机清理 */
    app_fsm_handle_event(EVT_POWER_LONG_PRESS);
    step = (fsm_test_step_t){ "power off", GLASS_STATE_IDLE, false };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    app_fsm_handle_event(EVT_POWER_LONG_PRESS);
    step = (fsm_test_step_t){ "power on", GLASS_STATE_IDLE, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    app_fsm_on_ble_cmd(BLE_CMD_START_RECORD);
    app_fsm_handle_event(EVT_SHUTTER_LONG_PRESS);
    step = (fsm_test_step_t){ "shutter long stops record", GLASS_STATE_IDLE, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    app_fsm_handle_event(EVT_TOUCH_DOUBLE_TAP);
    test_enter_capture(GLASS_STATE_AI_ASSIST);
    app_fsm_handle_event(EVT_TOUCH_DOUBLE_TAP);
    step = (fsm_test_step_t){ "AI dbl-tap aborts capture to AI", GLASS_STATE_AI_ASSIST, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }
    app_fsm_handle_event(EVT_TOUCH_DOUBLE_TAP);

    app_fsm_init();
    app_fsm_on_ble_cmd(BLE_CMD_START_RECORD);
    app_fsm_handle_event(EVT_LOW_BATTERY);
    step = (fsm_test_step_t){ "low battery stops record", GLASS_STATE_IDLE, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    app_fsm_handle_event(EVT_TOUCH_DOUBLE_TAP);
    app_fsm_handle_event(EVT_GLASSES_REMOVED);
    step = (fsm_test_step_t){ "glasses removed stops AI", GLASS_STATE_IDLE, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    app_fsm_init();
    app_fsm_on_ble_cmd(BLE_CMD_START_RECORD);
    app_fsm_on_shutter_click();
    step = (fsm_test_step_t){ "shutter blocked while record", GLASS_STATE_RECORD, true };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    app_fsm_init();
    app_fsm_handle_event(EVT_POWER_LONG_PRESS);
    app_fsm_on_ble_cmd(BLE_CMD_CAPTURE);
    step = (fsm_test_step_t){ "capture blocked when powered off", GLASS_STATE_IDLE, false };
    total++;
    if (run_step(step.name, &step)) {
        pass++;
    }

    ESP_LOGI(TAG, "======== FSM self-test %d/%d passed ========", pass, total);
    (void)exp;
}

#endif /* CONFIG_FSM_SELF_TEST */
