/**
 * 双 INMP441 立体声采集 + 简易降噪（L/R 差分）
 * #1 L/R→GND 左声道，#2 L/R→3.3V 右声道，SD 并接 GPIO44
 */
#include "audio_in.h"
#include "audio_i2s.h"
#include "board_config.h"

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <string.h>

static const char *TAG = "audio_in";

#define AUDIO_IN_FRAME_PAIRS    320   /* 20ms @ 16kHz */

static bool s_ready;
static bool s_streaming;

#if CONFIG_AUDIO_IN_ENABLE

static int32_t calc_rms(const int16_t *s, size_t n)
{
    if (n == 0) {
        return 0;
    }
    int64_t acc = 0;
    for (size_t i = 0; i < n; i++) {
        int32_t v = s[i];
        acc += (int64_t)v * v;
    }
    return (int32_t)(acc / (int64_t)n);
}

static size_t read_raw_stereo(int16_t *lr, size_t max_pairs)
{
    i2s_chan_handle_t rx = audio_i2s_rx_chan();
    if (!rx || !lr || max_pairs == 0) {
        return 0;
    }

    const size_t bytes = max_pairs * 2 * sizeof(int16_t);
    size_t read_bytes = 0;
    esp_err_t err = i2s_channel_read(rx, lr, bytes, &read_bytes, pdMS_TO_TICKS(100));
    if (err != ESP_OK || read_bytes < 4) {
        return 0;
    }
    return read_bytes / (2 * sizeof(int16_t));
}

size_t audio_in_read_stereo(int16_t *lr_buf, size_t max_pairs)
{
    return read_raw_stereo(lr_buf, max_pairs);
}

size_t audio_in_read_denoised_mono(int16_t *mono, size_t max_samples)
{
    if (!mono || max_samples == 0) {
        return 0;
    }

    int16_t stereo[AUDIO_IN_FRAME_PAIRS * 2];
    size_t pairs = read_raw_stereo(stereo, max_samples < AUDIO_IN_FRAME_PAIRS ? max_samples : AUDIO_IN_FRAME_PAIRS);
    if (pairs == 0) {
        return 0;
    }

    for (size_t i = 0; i < pairs && i < max_samples; i++) {
        int32_t l = stereo[i * 2];
        int32_t r = stereo[i * 2 + 1];
        /* 简易降噪：共模噪声取平均抑制，差分保留方向性语音 */
        int32_t common = (l + r) / 2;
        int32_t diff = l - r;
        int32_t out = diff + (l - common) / 2;
        if (out > 32767) {
            out = 32767;
        } else if (out < -32768) {
            out = -32768;
        }
        mono[i] = (int16_t)out;
    }
    return pairs;
}

bool audio_in_self_test_sample(audio_in_levels_t *out)
{
    if (!out || !s_ready) {
        return false;
    }

    int16_t buf[AUDIO_IN_FRAME_PAIRS * 2];
    int32_t sum_l = 0;
    int32_t sum_r = 0;
    size_t total_pairs = 0;
    const int rounds = 10;

    for (int i = 0; i < rounds; i++) {
        size_t pairs = read_raw_stereo(buf, AUDIO_IN_FRAME_PAIRS);
        if (pairs == 0) {
            continue;
        }
        int16_t left[AUDIO_IN_FRAME_PAIRS];
        int16_t right[AUDIO_IN_FRAME_PAIRS];
        for (size_t j = 0; j < pairs; j++) {
            left[j] = buf[j * 2];
            right[j] = buf[j * 2 + 1];
        }
        sum_l += calc_rms(left, pairs);
        sum_r += calc_rms(right, pairs);
        total_pairs += pairs;
    }

    if (total_pairs == 0) {
        return false;
    }

    out->rms_left = sum_l / rounds;
    out->rms_right = sum_r / rounds;
    out->rms_mono = (out->rms_left + out->rms_right) / 2;
    out->left_active = out->rms_left > 500;
    out->right_active = out->rms_right > 500;
    return true;
}

void audio_in_start_stream(void)
{
    s_streaming = true;
    ESP_LOGI(TAG, "mic stream on (stereo denoise)");
}

void audio_in_stop_stream(void)
{
    s_streaming = false;
    ESP_LOGI(TAG, "mic stream off");
}

bool audio_in_streaming(void)
{
    return s_streaming;
}

void audio_in_init(void)
{
#if !CONFIG_AUDIO_OUT_ENABLE
    if (audio_i2s_bus_init() != ESP_OK) {
        ESP_LOGE(TAG, "I2S bus init failed");
        return;
    }
#endif
    s_ready = audio_i2s_bus_ready() && audio_i2s_rx_chan() != NULL;
    if (s_ready) {
        ESP_LOGI(TAG, "dual mic ready DIN=%d (stereo L=#1 R=#2)", PIN_I2S_MIC_DIN);
    } else {
        ESP_LOGW(TAG, "dual mic not ready");
    }
}

bool audio_in_is_ready(void)
{
    return s_ready;
}

#else /* !CONFIG_AUDIO_IN_ENABLE */

size_t audio_in_read_stereo(int16_t *lr_buf, size_t max_pairs)
{
    (void)lr_buf;
    (void)max_pairs;
    return 0;
}

size_t audio_in_read_denoised_mono(int16_t *mono, size_t max_samples)
{
    (void)mono;
    (void)max_samples;
    return 0;
}

bool audio_in_self_test_sample(audio_in_levels_t *out)
{
    (void)out;
    return false;
}

void audio_in_start_stream(void) {}
void audio_in_stop_stream(void) {}
bool audio_in_streaming(void) { return false; }

void audio_in_init(void)
{
    ESP_LOGW(TAG, "audio in disabled");
}

bool audio_in_is_ready(void) { return false; }

#endif /* CONFIG_AUDIO_IN_ENABLE */
