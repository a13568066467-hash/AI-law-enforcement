/**
 * 电量监测：ADC 分压 + 充电检测（TP4056 CHRG）
 * 充电中：允许边充边录/AI，跳过低电强制停录，不进入轻睡眠
 */
#include "power_mgr.h"

#include "app_fsm.h"
#include "audio_prompt.h"
#include "ble_service.h"
#include "board_config.h"

#include "driver/gpio.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "power";

#if CONFIG_BATTERY_MONITOR_ENABLE
#if CONFIG_BATTERY_LOW_ALERT_PERCENT <= CONFIG_BATTERY_RECORD_MIN_PERCENT
#error "BATTERY_LOW_ALERT_PERCENT must be greater than BATTERY_RECORD_MIN_PERCENT"
#endif
#endif

static uint8_t s_percent = 100;
static bool s_charging = false;
static bool s_was_above_alert = true;
static bool s_was_above_protect = true;
static int64_t s_last_alert_us;

#if CONFIG_BATTERY_MONITOR_ENABLE && !CONFIG_BATTERY_USE_MOCK
static adc_oneshot_unit_handle_t s_adc;
#endif

static uint8_t clamp_percent(int pct)
{
    if (pct < 0) {
        return 0;
    }
    if (pct > 100) {
        return 100;
    }
    return (uint8_t)pct;
}

#if CONFIG_CHARGE_DETECT_ENABLE && !CONFIG_CHARGE_USE_MOCK
static void charge_gpio_init(void)
{
    gpio_config_t cfg = {
        .pin_bit_mask = 1ULL << PIN_CHARGE_DETECT,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = CHARGE_DETECT_PULLUP ? GPIO_PULLUP_ENABLE : GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&cfg);
}

static bool read_charging_gpio(void)
{
    int level = gpio_get_level(PIN_CHARGE_DETECT);
    return CHARGE_DETECT_ACTIVE_LOW ? (level == 0) : (level != 0);
}
#endif

static bool sample_charging(void)
{
#if !CONFIG_CHARGE_DETECT_ENABLE
    return false;
#elif CONFIG_CHARGE_USE_MOCK
    return CONFIG_CHARGE_MOCK_PLUGGED != 0;
#else
    return read_charging_gpio();
#endif
}

#if CONFIG_BATTERY_MONITOR_ENABLE && !CONFIG_BATTERY_USE_MOCK
static int read_battery_mv(void)
{
    int raw = 0;
    if (adc_oneshot_read(s_adc, BATTERY_ADC_UNIT_CH, &raw) != ESP_OK) {
        return -1;
    }
    int adc_mv = (raw * 3300) / 4095;
    return (adc_mv * (BATTERY_DIVIDER_R1_KOHM + BATTERY_DIVIDER_R2_KOHM))
           / BATTERY_DIVIDER_R2_KOHM;
}
#endif

static uint8_t estimate_percent(void)
{
#if !CONFIG_BATTERY_MONITOR_ENABLE
    return 100;
#elif CONFIG_BATTERY_USE_MOCK
    return clamp_percent(CONFIG_BATTERY_MOCK_PERCENT);
#else
    int mv = read_battery_mv();
    if (mv < 0) {
        return s_percent;
    }
    int pct = (mv - BATTERY_EMPTY_MV) * 100 / (BATTERY_FULL_MV - BATTERY_EMPTY_MV);
    return clamp_percent(pct);
#endif
}

static bool should_play_alert(int64_t now_us)
{
    if (s_last_alert_us == 0) {
        return true;
    }
    return (now_us - s_last_alert_us)
           >= ((int64_t)CONFIG_BATTERY_ALERT_INTERVAL_SEC * 1000000LL);
}

static bool low_protect_applies(void)
{
#if CONFIG_CHARGE_DETECT_ENABLE && CONFIG_CHARGE_SKIP_LOW_PROTECT
    if (s_charging) {
        return false;
    }
#endif
    return true;
}

static void play_battery_alert(uint8_t pct, bool protect_cross)
{
    const bool do_protect = protect_cross && low_protect_applies();

    if (do_protect) {
        ESP_LOGW(TAG, "battery protect %u%% — stop record/AI", (unsigned)pct);
        app_fsm_handle_event(EVT_LOW_BATTERY);
    } else if (protect_cross && s_charging) {
        ESP_LOGI(TAG, "battery %u%% low but charging — keep record/AI allowed", (unsigned)pct);
    } else {
        ESP_LOGW(TAG, "battery low %u%% — voice alert", (unsigned)pct);
    }

    audio_prompt_low_battery(pct);
    ble_notify_low_battery(pct);
    s_last_alert_us = esp_timer_get_time();
}

static void power_task(void *arg)
{
    (void)arg;
    s_was_above_alert = true;
    s_was_above_protect = true;

    while (1) {
        s_charging = sample_charging();
        s_percent = estimate_percent();
        ble_notify_sensor_state(app_fsm_get_sensor_state(), s_percent, power_mgr_get_sensor_flags());

#if CONFIG_BATTERY_MONITOR_ENABLE
        const uint8_t alert_pct = (uint8_t)CONFIG_BATTERY_LOW_ALERT_PERCENT;
        const uint8_t protect_pct = (uint8_t)CONFIG_BATTERY_RECORD_MIN_PERCENT;

        bool below_alert = s_percent <= alert_pct;
        bool below_protect = s_percent <= protect_pct;

        if (!below_alert && s_percent > alert_pct + BATTERY_HYSTERESIS_PERCENT) {
            s_was_above_alert = true;
        }
        if (!below_protect && s_percent > protect_pct + BATTERY_HYSTERESIS_PERCENT) {
            s_was_above_protect = true;
        }

        if (below_alert) {
            bool alert_first = s_was_above_alert;
            bool protect_first = below_protect && s_was_above_protect;
            int64_t now = esp_timer_get_time();

            if (alert_first || protect_first || should_play_alert(now)) {
                play_battery_alert(s_percent, protect_first);
                s_was_above_alert = false;
                if (below_protect) {
                    s_was_above_protect = false;
                }
            }
        }
#endif

        if (s_charging) {
            ESP_LOGD(TAG, "charging, battery %u%%", (unsigned)s_percent);
        } else {
            ESP_LOGD(TAG, "battery %u%%", (unsigned)s_percent);
        }
        vTaskDelay(pdMS_TO_TICKS(CONFIG_BATTERY_SAMPLE_PERIOD_MS));
    }
}

uint8_t power_mgr_get_percent(void)
{
    return s_percent;
}

bool power_mgr_is_charging(void)
{
    return s_charging;
}

uint8_t power_mgr_get_sensor_flags(void)
{
    uint8_t flags = 0;
    if (s_charging) {
        flags |= SENSOR_FLAG_CHARGING;
    }
    return flags;
}

bool power_mgr_can_record(void)
{
#if CONFIG_BATTERY_MONITOR_ENABLE
#if CONFIG_CHARGE_DETECT_ENABLE && CONFIG_CHARGE_ALLOW_RECORD_WHILE_PLUGGED
    if (s_charging) {
        return true;
    }
#endif
    return s_percent > (uint8_t)CONFIG_BATTERY_RECORD_MIN_PERCENT;
#else
    return true;
#endif
}

bool power_mgr_can_use_ai(void)
{
#if CONFIG_BATTERY_MONITOR_ENABLE
#if CONFIG_CHARGE_DETECT_ENABLE && CONFIG_CHARGE_ALLOW_RECORD_WHILE_PLUGGED
    if (s_charging) {
        return true;
    }
#endif
    return s_percent > (uint8_t)CONFIG_BATTERY_RECORD_MIN_PERCENT;
#else
    return true;
#endif
}

void power_mgr_init(void)
{
#if CONFIG_CHARGE_DETECT_ENABLE && !CONFIG_CHARGE_USE_MOCK
    charge_gpio_init();
#endif

#if CONFIG_BATTERY_MONITOR_ENABLE && !CONFIG_BATTERY_USE_MOCK
    adc_oneshot_unit_init_cfg_t init = {
        .unit_id = BATTERY_ADC_UNIT,
    };
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&init, &s_adc));

    adc_oneshot_chan_cfg_t cfg = {
        .bitwidth = ADC_BITWIDTH_DEFAULT,
        .atten = BATTERY_ADC_ATTEN,
    };
    ESP_ERROR_CHECK(adc_oneshot_config_channel(s_adc, BATTERY_ADC_UNIT_CH, &cfg));
    ESP_LOGI(TAG, "ADC GPIO%d alert<=%d%% record<=%d%% charge GPIO%d",
             BATTERY_ADC_GPIO, CONFIG_BATTERY_LOW_ALERT_PERCENT,
             CONFIG_BATTERY_RECORD_MIN_PERCENT, PIN_CHARGE_DETECT);
#elif CONFIG_BATTERY_MONITOR_ENABLE && CONFIG_BATTERY_USE_MOCK
    ESP_LOGW(TAG, "MOCK battery %d%%", CONFIG_BATTERY_MOCK_PERCENT);
#else
    ESP_LOGI(TAG, "battery monitor disabled");
#endif

#if CONFIG_CHARGE_DETECT_ENABLE
    ESP_LOGI(TAG, "charge detect: record_while_plugged=%d skip_low_protect=%d",
             CONFIG_CHARGE_ALLOW_RECORD_WHILE_PLUGGED, CONFIG_CHARGE_SKIP_LOW_PROTECT);
#endif

    if (CONFIG_BATTERY_MONITOR_ENABLE) {
        xTaskCreate(power_task, "power", 3072, NULL, 4, NULL);
    }
}
