/**
 * 板级自检：GPIO 冲突矩阵 + 各模块探测
 */
#include "board_selftest.h"

#include "audio_in.h"
#include "audio_out.h"
#include "board_config.h"
#include "camera.h"
#include "camera_pins.h"
#include "power_mgr.h"

#include "driver/gpio.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_log.h"
#include <stdio.h>

static const char *TAG = "selftest";

typedef struct {
    gpio_num_t gpio;
    const char *owner;
} pin_owner_t;

typedef struct {
    gpio_num_t a;
    const char *owner_a;
    gpio_num_t b;
    const char *owner_b;
} pin_clash_t;

static const pin_owner_t s_owners[] = {
    { PIN_BTN_POWER, "BTN_PWR" },
    { PIN_BTN_SHUTTER, "BTN_SHUT" },
    { PIN_BTN_AI, "BTN_AI" },
    { GPIO_NUM_4, "BAT_ADC" },
    { PIN_CHARGE_DETECT, "CHRG" },
    { PIN_I2S_BCK, "I2S_BCK" },
    { PIN_I2S_WS, "I2S_WS" },
    { PIN_I2S_MIC_DIN, "MIC_STEREO" },
    { PIN_I2S_AMP_DIN, "AMP_DIN" },
    { PIN_I2C_SDA, "IMU_SDA" },
    { PIN_I2C_SCL, "IMU_SCL" },
    { CAM_XCLK_GPIO_NUM, "CAM_XCLK" },
    { CAM_SIOD_GPIO_NUM, "CAM_SDA" },
    { CAM_SIOC_GPIO_NUM, "CAM_SCL" },
    { CAM_Y9_GPIO_NUM, "CAM_Y9" },
    { CAM_Y8_GPIO_NUM, "CAM_Y8" },
    { CAM_Y7_GPIO_NUM, "CAM_Y7" },
    { CAM_Y6_GPIO_NUM, "CAM_Y6" },
    { CAM_Y5_GPIO_NUM, "CAM_Y5" },
    { CAM_Y4_GPIO_NUM, "CAM_Y4" },
    { CAM_Y3_GPIO_NUM, "CAM_Y3" },
    { CAM_Y2_GPIO_NUM, "CAM_Y2" },
    { CAM_VSYNC_GPIO_NUM, "CAM_VSYNC" },
    { CAM_HREF_GPIO_NUM, "CAM_HREF" },
    { CAM_PCLK_GPIO_NUM, "CAM_PCLK" },
};

static const pin_clash_t s_known_risks[] = {
    { PIN_I2S_AMP_DIN, "AMP_DIN", SD_PIN_DATA0, "SD_DATA(勿插卡)" },
    { PIN_I2S_MIC_SD2_WARN, "MIC2误接?", SD_PIN_CLK, "SD_CLK(勿插卡)" },
    { PIN_I2C_SCL, "IMU_SCL", SD_PIN_CMD, "SD_CMD(勿插卡)" },
};

static int test_pin_conflicts(void)
{
    int fail = 0;
    ESP_LOGI(TAG, "--- pin conflict matrix ---");

    for (size_t i = 0; i < sizeof(s_owners) / sizeof(s_owners[0]); i++) {
        for (size_t j = i + 1; j < sizeof(s_owners) / sizeof(s_owners[0]); j++) {
            if (s_owners[i].gpio == s_owners[j].gpio) {
                ESP_LOGE(TAG, "[FAIL] GPIO%d shared: %s vs %s",
                         s_owners[i].gpio, s_owners[i].owner, s_owners[j].owner);
                fail++;
            }
        }
    }

    for (size_t k = 0; k < sizeof(s_known_risks) / sizeof(s_known_risks[0]); k++) {
        const pin_clash_t *c = &s_known_risks[k];
        if (c->a == c->b) {
            ESP_LOGE(TAG, "[FAIL] risk GPIO%d: %s / %s", c->a, c->owner_a, c->owner_b);
            fail++;
        } else {
            ESP_LOGW(TAG, "[WARN] GPIO%d %s overlaps SD/legacy %s — 勿插 MicroSD",
                     c->a, c->owner_a, c->owner_b);
        }
    }

    if (fail == 0) {
        ESP_LOGI(TAG, "[PASS] no duplicate GPIO assignment");
    }
    return fail;
}

static int test_buttons_idle(void)
{
    int pressed = 0;
    if (gpio_get_level(PIN_BTN_POWER) == 0) {
        pressed |= 1;
    }
    if (gpio_get_level(PIN_BTN_SHUTTER) == 0) {
        pressed |= 2;
    }
    if (gpio_get_level(PIN_BTN_AI) == 0) {
        pressed |= 4;
    }

    if (pressed != 0) {
        ESP_LOGW(TAG, "[WARN] button held at test (mask=0x%x) — release before use", pressed);
        return 0;
    }
    ESP_LOGI(TAG, "[PASS] buttons idle high on GPIO%d/%d/%d",
             PIN_BTN_POWER, PIN_BTN_SHUTTER, PIN_BTN_AI);
    return 0;
}

static int test_battery_adc(void)
{
#if CONFIG_BATTERY_MONITOR_ENABLE && !CONFIG_BATTERY_USE_MOCK
    adc_oneshot_unit_handle_t adc;
    adc_oneshot_unit_init_cfg_t init = { .unit_id = BATTERY_ADC_UNIT };
    if (adc_oneshot_new_unit(&init, &adc) != ESP_OK) {
        ESP_LOGE(TAG, "[FAIL] ADC unit init");
        return 1;
    }
    adc_oneshot_chan_cfg_t cfg = {
        .bitwidth = ADC_BITWIDTH_DEFAULT,
        .atten = BATTERY_ADC_ATTEN,
    };
    if (adc_oneshot_config_channel(adc, BATTERY_ADC_UNIT_CH, &cfg) != ESP_OK) {
        ESP_LOGE(TAG, "[FAIL] ADC ch config GPIO%d", BATTERY_ADC_GPIO);
        adc_oneshot_del_unit(adc);
        return 1;
    }
    int raw = 0;
    if (adc_oneshot_read(adc, BATTERY_ADC_UNIT_CH, &raw) != ESP_OK) {
        ESP_LOGE(TAG, "[FAIL] ADC read GPIO%d", BATTERY_ADC_GPIO);
        adc_oneshot_del_unit(adc);
        return 1;
    }
    int mv = (raw * 3300) / 4095;
    int bat = (mv * (BATTERY_DIVIDER_R1_KOHM + BATTERY_DIVIDER_R2_KOHM)) / BATTERY_DIVIDER_R2_KOHM;
    ESP_LOGI(TAG, "[PASS] battery ADC raw=%d ~%dmV pack~%dmV (%u%%)",
             raw, mv, bat, (unsigned)power_mgr_get_percent());
    adc_oneshot_del_unit(adc);
    return 0;
#else
    ESP_LOGI(TAG, "[SKIP] battery ADC (mock or disabled)");
    return 0;
#endif
}

static int test_charge_gpio(void)
{
#if CONFIG_CHARGE_DETECT_ENABLE && !CONFIG_CHARGE_USE_MOCK
    int lvl = gpio_get_level(PIN_CHARGE_DETECT);
    bool charging = power_mgr_is_charging();
    ESP_LOGI(TAG, "[PASS] CHRG GPIO%d level=%d charging=%d", PIN_CHARGE_DETECT, lvl, charging);
    return 0;
#else
    ESP_LOGI(TAG, "[SKIP] charge GPIO (mock or disabled)");
    return 0;
#endif
}

static int test_audio_out(void)
{
#if CONFIG_AUDIO_OUT_ENABLE
    ESP_LOGI(TAG, "audio out beep test...");
    audio_out_play_startup_beep();
    ESP_LOGI(TAG, "[PASS] audio out beep sent");
    return 0;
#else
    ESP_LOGI(TAG, "[SKIP] audio out disabled");
    return 0;
#endif
}

static int test_dual_mic(void)
{
#if CONFIG_AUDIO_IN_ENABLE
    audio_in_levels_t lv = { 0 };
    if (!audio_in_is_ready()) {
        ESP_LOGE(TAG, "[FAIL] dual mic not ready");
        return 1;
    }
    if (!audio_in_self_test_sample(&lv)) {
        ESP_LOGE(TAG, "[FAIL] dual mic read timeout — check SD→GPIO44 stereo wiring");
        return 1;
    }
    ESP_LOGI(TAG, "dual mic RMS L=%ld R=%ld mono=%ld (speak to verify)",
             (long)lv.rms_left, (long)lv.rms_right, (long)lv.rms_mono);
    if (!lv.left_active && !lv.right_active) {
        ESP_LOGW(TAG, "[WARN] both channels quiet — OK in silent room; tap mics to verify");
    } else if (!lv.left_active || !lv.right_active) {
        ESP_LOGW(TAG, "[WARN] one channel weak — check L/R strap (GND vs 3.3V) and SD on GPIO44");
    } else {
        ESP_LOGI(TAG, "[PASS] dual mic stereo active");
    }
    return 0;
#else
    ESP_LOGI(TAG, "[SKIP] audio in disabled");
    return 0;
#endif
}

static int test_camera(void)
{
#if CONFIG_CAMERA_ENABLE
    if (!camera_is_ready()) {
        ESP_LOGE(TAG, "[FAIL] camera not initialized");
        return 1;
    }
    if (!camera_self_test_capture()) {
        ESP_LOGE(TAG, "[FAIL] camera JPEG capture");
        return 1;
    }
    ESP_LOGI(TAG, "[PASS] camera OV5640 JPEG");
    return 0;
#else
    ESP_LOGI(TAG, "[SKIP] camera disabled");
    return 0;
#endif
}

void board_selftest_run(void)
{
#if !CONFIG_BOARD_SELFTEST_ENABLE
    ESP_LOGI(TAG, "board self-test disabled");
    return;
#endif

    int fail = 0;
    ESP_LOGI(TAG, "======== board self-test (XIAO + OV5640) ========");

    fail += test_pin_conflicts();
    fail += test_buttons_idle();
    fail += test_battery_adc();
    fail += test_charge_gpio();
    fail += test_audio_out();
    fail += test_dual_mic();
    fail += test_camera();

    if (fail == 0) {
        ESP_LOGI(TAG, "======== board self-test PASS ========");
    } else {
        ESP_LOGE(TAG, "======== board self-test %d FAIL(s) ========", fail);
    }
}
