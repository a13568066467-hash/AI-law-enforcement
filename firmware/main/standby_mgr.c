/**

 * 待机续航管理

 *

 * - AI_ASSIST：无活动超过 AI_LISTEN_IDLE_SEC → 自动关 AI（对齐 VAD 静音 ~5s）

 * - IDLE：无活动 +（可选）IMU 静置 → 轻睡眠，GPIO 按键唤醒

 * - RECORD/CAPTURE：不进入轻睡眠

 */

#include "standby_mgr.h"



#include "app_fsm.h"

#include "board_config.h"
#include "power_mgr.h"

#include "driver/gpio.h"

#include "esp_log.h"

#include "esp_sleep.h"

#include "esp_timer.h"

#include "freertos/FreeRTOS.h"

#include "freertos/task.h"



static const char *TAG = "standby";



static int64_t s_last_activity_us;

static standby_profile_t s_profile = STANDBY_PROFILE_ACTIVE;

static bool s_imu_motion;



#if CONFIG_STANDBY_IMU_ENABLE

/* TODO: BMI270 I2C 读加速度，判断静置 */

static bool imu_read_motion(void)

{

#if CONFIG_STANDBY_IMU_MOCK_MOTION

    return true;

#else

    return s_imu_motion;

#endif

}

#else

static bool imu_read_motion(void)

{

    (void)s_imu_motion;

    return true;

}

#endif



static int64_t idle_seconds(void)

{

    return (esp_timer_get_time() - s_last_activity_us) / 1000000LL;

}



static void gpio_wake_setup(void)

{

    const gpio_num_t pins[] = { PIN_BTN_POWER, PIN_BTN_SHUTTER, PIN_BTN_AI };

    for (size_t i = 0; i < sizeof(pins) / sizeof(pins[0]); i++) {

        gpio_wakeup_enable(pins[i], GPIO_INTR_LOW_LEVEL);

    }

    esp_sleep_enable_gpio_wakeup();

}



static void try_light_sleep(void)

{

#if !CONFIG_STANDBY_LIGHT_SLEEP_ENABLE

    return;

#endif

    if (!app_fsm_is_powered()) {

        return;

    }

#if CONFIG_CHARGE_DETECT_ENABLE
    if (power_mgr_is_charging()) {
        ESP_LOGD(TAG, "light sleep skipped — charging");
        return;
    }
#endif

    if (app_fsm_get_state() != GLASS_STATE_IDLE) {

        return;

    }

    if (!imu_read_motion()) {

        ESP_LOGD(TAG, "light sleep skipped — IMU motion");

        standby_mgr_notify_activity();

        return;

    }



    ESP_LOGI(TAG, "light sleep enter (idle %lld s)", (long long)idle_seconds());

    s_profile = STANDBY_PROFILE_LIGHT_SLEEP;

    gpio_wake_setup();



    esp_err_t err = esp_light_sleep_start();

    if (err != ESP_OK) {

        ESP_LOGW(TAG, "light sleep failed %s", esp_err_to_name(err));

    }



    s_profile = STANDBY_PROFILE_ACTIVE;

    ESP_LOGI(TAG, "light sleep wake (%s)", esp_sleep_get_wakeup_cause() == ESP_SLEEP_WAKEUP_GPIO

                                              ? "GPIO"

                                              : "other");

    standby_mgr_notify_activity();

}



static void check_ai_listen_idle(void)

{

#if !CONFIG_STANDBY_MGR_ENABLE

    return;

#endif

    if (app_fsm_get_state() != GLASS_STATE_AI_ASSIST) {

        return;

    }

    if (idle_seconds() < (int64_t)CONFIG_STANDBY_AI_LISTEN_IDLE_SEC) {

        s_profile = STANDBY_PROFILE_AI_LISTEN;

        return;

    }



    ESP_LOGI(TAG, "AI listen idle %lld s — auto stop (save power)",

             (long long)idle_seconds());

    app_fsm_handle_event(EVT_AI_LISTEN_IDLE_TIMEOUT);

    standby_mgr_notify_activity();

}



static void check_idle_standby(void)

{

#if !CONFIG_STANDBY_MGR_ENABLE

    return;

#endif

    if (!app_fsm_is_powered()) {

        s_profile = STANDBY_PROFILE_ACTIVE;

        return;

    }



    glass_state_t st = app_fsm_get_state();

    if (st == GLASS_STATE_RECORD || st == GLASS_STATE_CAPTURE) {

        s_profile = STANDBY_PROFILE_ACTIVE;

        return;

    }

    if (st == GLASS_STATE_AI_ASSIST) {

        return;

    }



    if (idle_seconds() < (int64_t)CONFIG_STANDBY_IDLE_TIMEOUT_SEC) {

        s_profile = STANDBY_PROFILE_IDLE_WAIT;

        return;

    }



    try_light_sleep();

}



static void standby_task(void *arg)

{

    (void)arg;

    standby_mgr_notify_activity();



    while (1) {

#if CONFIG_STANDBY_MGR_ENABLE

        check_ai_listen_idle();

        check_idle_standby();

#else

        s_profile = STANDBY_PROFILE_ACTIVE;

#endif

        vTaskDelay(pdMS_TO_TICKS(CONFIG_STANDBY_POLL_MS));

    }

}



void standby_mgr_notify_activity(void)

{

    s_last_activity_us = esp_timer_get_time();

}



void standby_mgr_on_state_changed(glass_state_t state)

{

    standby_mgr_notify_activity();



    switch (state) {

    case GLASS_STATE_AI_ASSIST:

        s_profile = STANDBY_PROFILE_AI_LISTEN;

        ESP_LOGI(TAG, "profile AI listen (auto-off after %ds idle)",

                 CONFIG_STANDBY_AI_LISTEN_IDLE_SEC);

        break;

    case GLASS_STATE_IDLE:

        ESP_LOGD(TAG, "profile idle wait (light sleep after %ds)",

                 CONFIG_STANDBY_IDLE_TIMEOUT_SEC);

        break;

    default:

        s_profile = STANDBY_PROFILE_ACTIVE;

        break;

    }

}



standby_profile_t standby_mgr_get_profile(void)

{

    return s_profile;

}



void standby_mgr_init(void)

{

    s_imu_motion = false;

    standby_mgr_notify_activity();



#if CONFIG_STANDBY_MGR_ENABLE

    xTaskCreate(standby_task, "standby", 3072, NULL, 3, NULL);

    ESP_LOGI(TAG, "standby on — AI idle %ds, device idle %ds, light_sleep=%d",

             CONFIG_STANDBY_AI_LISTEN_IDLE_SEC, CONFIG_STANDBY_IDLE_TIMEOUT_SEC,

             CONFIG_STANDBY_LIGHT_SLEEP_ENABLE);

#else

    ESP_LOGI(TAG, "standby disabled");

#endif

}


