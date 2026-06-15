/**
 * 本地语音提醒 — 经 I2S 喇叭播放
 */
#include "audio_prompt.h"

#include "audio_out.h"

#include "esp_log.h"

static const char *TAG = "audio";

void audio_prompt_low_battery(uint8_t percent)
{
    ESP_LOGW(TAG, "low battery %u%% — speaker alert", (unsigned)percent);
    audio_out_play_low_battery_alert();
}
