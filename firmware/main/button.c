/**
 * 电源 / 快门 / AI 三键扫描
 *
 * 电源：松开且按住>=3s → 开关机
 * 快门：短按松开 → 拍照；录像中长按>=1s 松开 → 停录
 * AI：600ms 内两次「松开」→ 切换 AI（与常见双击一致）
 */
#include "button.h"

#include "board_config.h"
#include "app_fsm.h"
#include "standby_mgr.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "button";

typedef struct {
    gpio_num_t pin;
    int last_level;
    int stable_level;
    int64_t last_change_us;
    int64_t press_start_us;
    int click_count;
    int64_t last_click_us;
} btn_t;

static btn_t s_power = { .pin = PIN_BTN_POWER };
static btn_t s_shutter = { .pin = PIN_BTN_SHUTTER };
static btn_t s_ai = { .pin = PIN_BTN_AI };

/** 1=按下(低电平)，0=松开 */
static int read_pressed(const btn_t *b)
{
    return gpio_get_level(b->pin) == 0;
}

static void gpio_init_btn(gpio_num_t pin)
{
    gpio_config_t cfg = {
        .pin_bit_mask = 1ULL << pin,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&cfg);
}

static void ai_on_release(int64_t now_us)
{
    int64_t gap = now_us - s_ai.last_click_us;
    if (s_ai.last_click_us != 0 && gap > (BTN_AI_DOUBLE_TAP_MS * 1000)) {
        s_ai.click_count = 0;
    }
    s_ai.click_count++;
    s_ai.last_click_us = now_us;
    ESP_LOGD(TAG, "AI release #%d gap=%lld ms", s_ai.click_count, (long long)(gap / 1000));

    if (s_ai.click_count >= 2) {
        ESP_LOGI(TAG, "AI double-tap");
        app_fsm_handle_event(EVT_TOUCH_DOUBLE_TAP);
        s_ai.click_count = 0;
        s_ai.last_click_us = 0;
    }
}

static void scan_button(btn_t *b, int64_t now_us)
{
    int raw = read_pressed(b);
    if (raw != b->last_level) {
        b->last_level = raw;
        b->last_change_us = now_us;
    }
    if ((now_us - b->last_change_us) < (BTN_DEBOUNCE_MS * 1000)) {
        return;
    }
    if (raw == b->stable_level) {
        return;
    }

    int was = b->stable_level;
    b->stable_level = raw;
    standby_mgr_notify_activity();

    if (b->pin == PIN_BTN_POWER) {
        if (!was && raw) {
            b->press_start_us = now_us;
        } else if (was && !raw) {
            if (b->press_start_us == 0) {
                return;
            }
            int64_t held = now_us - b->press_start_us;
            b->press_start_us = 0;
            if (held >= (BTN_POWER_LONG_MS * 1000)) {
                ESP_LOGI(TAG, "power long press");
                app_fsm_handle_event(EVT_POWER_LONG_PRESS);
            }
        }
        return;
    }

    if (b->pin == PIN_BTN_SHUTTER) {
        if (!was && raw) {
            b->press_start_us = now_us;
        } else if (was && !raw) {
            if (b->press_start_us == 0) {
                return;
            }
            int64_t held = now_us - b->press_start_us;
            b->press_start_us = 0;
            if (held >= (BTN_SHUTTER_LONG_MS * 1000)) {
                ESP_LOGI(TAG, "shutter long release (%lld ms)", (long long)(held / 1000));
                app_fsm_on_shutter_long_press();
            } else {
                ESP_LOGI(TAG, "shutter click");
                app_fsm_on_shutter_click();
            }
        }
        return;
    }

    if (b->pin == PIN_BTN_AI) {
        if (!app_fsm_is_powered()) {
            return;
        }
        if (was && !raw) {
            ai_on_release(now_us);
        }
    }
}

static void button_task(void *arg)
{
    (void)arg;
    while (1) {
        int64_t now = esp_timer_get_time();
        scan_button(&s_power, now);
        scan_button(&s_shutter, now);
        scan_button(&s_ai, now);
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

void button_init(void)
{
    gpio_init_btn(PIN_BTN_POWER);
    gpio_init_btn(PIN_BTN_SHUTTER);
    gpio_init_btn(PIN_BTN_AI);

    s_power.last_level = s_power.stable_level = read_pressed(&s_power);
    s_shutter.last_level = s_shutter.stable_level = read_pressed(&s_shutter);
    s_ai.last_level = s_ai.stable_level = read_pressed(&s_ai);
    s_ai.click_count = 0;
    s_ai.last_click_us = 0;

    xTaskCreate(button_task, "button", 3072, NULL, 5, NULL);
    ESP_LOGI(TAG, "buttons GPIO %d/%d/%d (AI dbl-rel %dms, shutter long %dms)",
             PIN_BTN_POWER, PIN_BTN_SHUTTER, PIN_BTN_AI,
             BTN_AI_DOUBLE_TAP_MS, BTN_SHUTTER_LONG_MS);
}
