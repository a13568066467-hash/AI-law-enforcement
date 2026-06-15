/**
 * I2S 全双工总线 — 喇叭 TX 与双麦 RX 共用 BCK/WS
 */
#include "audio_i2s.h"
#include "board_config.h"

#include "driver/gpio.h"
#include "esp_log.h"

static const char *TAG = "audio_i2s";

static i2s_chan_handle_t s_tx;
static i2s_chan_handle_t s_rx;
static bool s_ready;

esp_err_t audio_i2s_bus_init(void)
{
    if (s_ready) {
        return ESP_OK;
    }

    i2s_chan_config_t chan_cfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    chan_cfg.dma_desc_num = 6;
    chan_cfg.dma_frame_num = 240;

#if CONFIG_AUDIO_IN_ENABLE
    esp_err_t err = i2s_new_channel(&chan_cfg, &s_tx, &s_rx);
#else
    esp_err_t err = i2s_new_channel(&chan_cfg, &s_tx, NULL);
#endif
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "i2s_new_channel failed %s", esp_err_to_name(err));
        return err;
    }

    i2s_std_config_t tx_cfg = {
        .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(AUDIO_I2S_SAMPLE_RATE),
        .slot_cfg = I2S_STD_MSB_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_MONO),
        .gpio_cfg = {
            .mclk = I2S_GPIO_UNUSED,
            .bclk = PIN_I2S_BCK,
            .ws = PIN_I2S_WS,
            .dout = PIN_I2S_AMP_DIN,
            .din = I2S_GPIO_UNUSED,
            .invert_flags = { .mclk_inv = false, .bclk_inv = false, .ws_inv = false },
        },
    };
    err = i2s_channel_init_std_mode(s_tx, &tx_cfg);
    if (err != ESP_OK) {
        return err;
    }
    err = i2s_channel_enable(s_tx);
    if (err != ESP_OK) {
        return err;
    }

#if CONFIG_AUDIO_IN_ENABLE
    i2s_std_config_t rx_cfg = {
        .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(AUDIO_I2S_SAMPLE_RATE),
        .slot_cfg = I2S_STD_MSB_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO),
        .gpio_cfg = {
            .mclk = I2S_GPIO_UNUSED,
            .bclk = PIN_I2S_BCK,
            .ws = PIN_I2S_WS,
            .dout = I2S_GPIO_UNUSED,
            .din = PIN_I2S_MIC_DIN,
            .invert_flags = { .mclk_inv = false, .bclk_inv = false, .ws_inv = false },
        },
    };
    err = i2s_channel_init_std_mode(s_rx, &rx_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "RX init failed %s", esp_err_to_name(err));
        return err;
    }
    err = i2s_channel_enable(s_rx);
    if (err != ESP_OK) {
        return err;
    }
    ESP_LOGI(TAG, "I2S duplex BCK=%d WS=%d DIN=%d DOUT=%d @%dHz stereo mic",
             PIN_I2S_BCK, PIN_I2S_WS, PIN_I2S_MIC_DIN, PIN_I2S_AMP_DIN, AUDIO_I2S_SAMPLE_RATE);
#else
    ESP_LOGI(TAG, "I2S TX only BCK=%d WS=%d DOUT=%d @%dHz",
             PIN_I2S_BCK, PIN_I2S_WS, PIN_I2S_AMP_DIN, AUDIO_I2S_SAMPLE_RATE);
#endif

    s_ready = true;
    return ESP_OK;
}

i2s_chan_handle_t audio_i2s_tx_chan(void)
{
    return s_tx;
}

i2s_chan_handle_t audio_i2s_rx_chan(void)
{
    return s_rx;
}

bool audio_i2s_bus_ready(void)
{
    return s_ready;
}
