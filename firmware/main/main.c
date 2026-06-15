/**
 * AI Field Cam — XIAO ESP32S3 + OV5640 固件入口
 *
 * 编译（ESP-IDF 终端）:
 *   cd firmware
 *   idf.py set-target esp32s3
 *   idf.py build flash monitor
 */
#include "app_fsm.h"
#include "audio_in.h"
#include "audio_out.h"
#include "board_config.h"
#include "board_selftest.h"
#include "ble_service.h"
#include "button.h"
#include "camera.h"
#include "power_mgr.h"
#include "standby_mgr.h"

#include "esp_log.h"
#include "nvs_flash.h"

static const char *TAG = "main";

void app_main(void)
{
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    ESP_LOGI(TAG, "AI Field Cam boot (XIAO + OV5640 + dual mic)");

    app_fsm_init();

#if CONFIG_FSM_SELF_TEST
    app_fsm_run_self_test();
    app_fsm_init();
#endif

    audio_out_init();
    audio_in_init();

#if CONFIG_CAMERA_ENABLE
    esp_err_t cam_err = camera_init();
    if (cam_err != ESP_OK) {
        ESP_LOGW(TAG, "camera_init failed — check expansion board seating");
    }
#endif

    ble_service_init();
    button_init();
    power_mgr_init();
    standby_mgr_init();

    board_selftest_run();

    ESP_LOGI(TAG, "Ready — PWR GPIO%d long 3s; SHUTTER %d click; AI %d dbl-release",
             PIN_BTN_POWER, PIN_BTN_SHUTTER, PIN_BTN_AI);
}
