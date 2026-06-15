/**
 * I2S 喇叭输出 — MAX98357A（与双麦共用 audio_i2s 总线）
 */
#include "audio_out.h"
#include "audio_i2s.h"
#include "board_config.h"

#include "driver/i2s_std.h"
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include <math.h>
#include <string.h>

static const char *TAG = "audio_out";

static SemaphoreHandle_t s_play_mutex;
static bool s_ready;

#if CONFIG_AUDIO_OUT_ENABLE

static esp_err_t write_pcm(const int16_t *samples, size_t num_samples)
{
    i2s_chan_handle_t tx = audio_i2s_tx_chan();
    if (!s_ready || !tx || !samples || num_samples == 0) {
        return ESP_ERR_INVALID_STATE;
    }

    const size_t bytes = num_samples * sizeof(int16_t);
    size_t written = 0;
    const uint8_t *ptr = (const uint8_t *)samples;

    while (written < bytes) {
        size_t chunk_written = 0;
        esp_err_t err = i2s_channel_write(tx, ptr + written, bytes - written,
                                          &chunk_written, portMAX_DELAY);
        if (err != ESP_OK) {
            return err;
        }
        written += chunk_written;
    }

    int16_t silence[64] = { 0 };
    size_t dummy = 0;
    i2s_channel_write(tx, silence, sizeof(silence), &dummy, pdMS_TO_TICKS(50));
    return ESP_OK;
}

static int synth_tone_at(int16_t *buf, int pos, int sample_rate, int ms, int freq_hz, int amplitude)
{
    const int n = (sample_rate * ms) / 1000;
    for (int i = 0; i < n; i++) {
        double t = (double)i / (double)sample_rate;
        double s = sin(2.0 * M_PI * (double)freq_hz * t);
        double env = 1.0;
        if (n > 20) {
            if (i < n / 10) {
                env = (double)i / (double)(n / 10);
            } else if (i > n - n / 10) {
                env = (double)(n - i) / (double)(n / 10);
            }
        }
        buf[pos + i] = (int16_t)(s * (double)amplitude * env);
    }
    return pos + n;
}

static int synth_silence_at(int16_t *buf, int pos, int sample_rate, int ms)
{
    const int n = (sample_rate * ms) / 1000;
    memset(buf + pos, 0, n * sizeof(int16_t));
    return pos + n;
}

static void play_low_battery_synth(void)
{
    const int rate = AUDIO_I2S_SAMPLE_RATE;
    const int max_samples = rate * 3;
    int16_t *pcm = heap_caps_malloc(max_samples * sizeof(int16_t), MALLOC_CAP_INTERNAL);
    if (!pcm) {
        ESP_LOGE(TAG, "pcm alloc failed");
        return;
    }

    int pos = 0;
    pos = synth_tone_at(pcm, pos, rate, 180, 880, 9000);
    pos = synth_silence_at(pcm, pos, rate, 60);
    pos = synth_tone_at(pcm, pos, rate, 180, 988, 9000);
    pos = synth_silence_at(pcm, pos, rate, 60);
    pos = synth_tone_at(pcm, pos, rate, 160, 1100, 8500);
    pos = synth_silence_at(pcm, pos, rate, 50);
    pos = synth_tone_at(pcm, pos, rate, 320, 740, 9000);
    pos = synth_silence_at(pcm, pos, rate, 120);

    ESP_LOGI(TAG, "play low battery synth %u samples", (unsigned)pos);
    write_pcm(pcm, (size_t)pos);
    heap_caps_free(pcm);
}

void audio_out_play_startup_beep(void)
{
    const int rate = AUDIO_I2S_SAMPLE_RATE;
    int16_t pcm[rate / 2];
    int pos = 0;
    pos = synth_tone_at(pcm, pos, rate, 80, 660, 6000);
    pos = synth_silence_at(pcm, pos, rate, 40);
    pos = synth_tone_at(pcm, pos, rate, 80, 880, 6000);
    write_pcm(pcm, (size_t)pos);
}

void audio_out_play_pcm(const int16_t *samples, size_t num_samples)
{
    if (!s_play_mutex || xSemaphoreTake(s_play_mutex, pdMS_TO_TICKS(5000)) != pdTRUE) {
        return;
    }
    write_pcm(samples, num_samples);
    xSemaphoreGive(s_play_mutex);
}

void audio_out_play_low_battery_alert(void)
{
    if (!s_play_mutex || xSemaphoreTake(s_play_mutex, pdMS_TO_TICKS(5000)) != pdTRUE) {
        return;
    }
    play_low_battery_synth();
    xSemaphoreGive(s_play_mutex);
}

void audio_out_init(void)
{
    s_play_mutex = xSemaphoreCreateMutex();
    if (!s_play_mutex) {
        ESP_LOGE(TAG, "mutex create failed");
        return;
    }

    if (audio_i2s_bus_init() != ESP_OK) {
        ESP_LOGE(TAG, "I2S bus init failed");
        return;
    }
    s_ready = audio_i2s_bus_ready();
    ESP_LOGI(TAG, "MAX98357 ready BCK=%d WS=%d DIN=%d",
             PIN_I2S_BCK, PIN_I2S_WS, PIN_I2S_AMP_DIN);
}

#else /* !CONFIG_AUDIO_OUT_ENABLE */

void audio_out_play_pcm(const int16_t *samples, size_t num_samples)
{
    (void)samples;
    (void)num_samples;
}

void audio_out_play_low_battery_alert(void) {}
void audio_out_play_startup_beep(void) {}

void audio_out_init(void)
{
#if CONFIG_AUDIO_IN_ENABLE
    ESP_ERROR_CHECK(audio_i2s_bus_init());
#endif
    ESP_LOGW(TAG, "audio out disabled in menuconfig");
}

#endif /* CONFIG_AUDIO_OUT_ENABLE */
