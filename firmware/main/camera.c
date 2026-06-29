/**
 * OV5640 @ XIAO ESP32S3 Sense 扩展座
 */
#include "camera.h"
#include "camera_pins.h"

#include "esp_camera.h"
#include "esp_log.h"
#include <stdlib.h>
#include <string.h>

static const char *TAG = "camera";
static bool s_ready;
static camera_fb_t *s_pending_fb;
static bool s_recording;
static uint8_t *s_record_data;
static size_t s_record_len;

#if CONFIG_CAMERA_ENABLE

static camera_config_t build_config(void)
{
    camera_config_t c = {
        .pin_pwdn = CAM_PWDN_GPIO_NUM,
        .pin_reset = CAM_RESET_GPIO_NUM,
        .pin_xclk = CAM_XCLK_GPIO_NUM,
        .pin_sccb_sda = CAM_SIOD_GPIO_NUM,
        .pin_sccb_scl = CAM_SIOC_GPIO_NUM,
        .pin_d7 = CAM_Y9_GPIO_NUM,
        .pin_d6 = CAM_Y8_GPIO_NUM,
        .pin_d5 = CAM_Y7_GPIO_NUM,
        .pin_d4 = CAM_Y6_GPIO_NUM,
        .pin_d3 = CAM_Y5_GPIO_NUM,
        .pin_d2 = CAM_Y4_GPIO_NUM,
        .pin_d1 = CAM_Y3_GPIO_NUM,
        .pin_d0 = CAM_Y2_GPIO_NUM,
        .pin_vsync = CAM_VSYNC_GPIO_NUM,
        .pin_href = CAM_HREF_GPIO_NUM,
        .pin_pclk = CAM_PCLK_GPIO_NUM,
        .xclk_freq_hz = CAM_XCLK_FREQ_HZ,
        .ledc_timer = LEDC_TIMER_0,
        .ledc_channel = LEDC_CHANNEL_0,
        .pixel_format = PIXFORMAT_JPEG,
        .frame_size = FRAMESIZE_SVGA,
        .jpeg_quality = 12,
        .fb_count = 2,
        .fb_location = CAMERA_FB_IN_PSRAM,
        .grab_mode = CAMERA_GRAB_WHEN_EMPTY,
    };
    return c;
}

esp_err_t camera_init(void)
{
    if (s_ready) {
        return ESP_OK;
    }

    camera_config_t cfg = build_config();
    esp_err_t err = esp_camera_init(&cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_camera_init failed %s", esp_err_to_name(err));
        return err;
    }

    sensor_t *s = esp_camera_sensor_get();
    if (s && s->id.PID == OV5640_PID) {
        ESP_LOGI(TAG, "OV5640 detected");
    } else if (s) {
        ESP_LOGW(TAG, "sensor PID=0x%04x (expect OV5640)", s ? s->id.PID : 0);
    }

    s_ready = true;
    ESP_LOGI(TAG, "camera ready JPEG SVGA");
    return ESP_OK;
}

bool camera_is_ready(void)
{
    return s_ready;
}

esp_err_t camera_capture_jpeg(uint8_t **buf, size_t *len)
{
    if (!s_ready || !buf || !len) {
        return ESP_ERR_INVALID_STATE;
    }
    if (s_pending_fb) {
        esp_camera_fb_return(s_pending_fb);
        s_pending_fb = NULL;
    }

    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) {
        return ESP_FAIL;
    }
    if (fb->format != PIXFORMAT_JPEG) {
        esp_camera_fb_return(fb);
        return ESP_ERR_INVALID_RESPONSE;
    }

    s_pending_fb = fb;
    *buf = fb->buf;
    *len = fb->len;
    return ESP_OK;
}

void camera_release_jpeg(uint8_t *buf)
{
    if (!buf || !s_pending_fb || s_pending_fb->buf != buf) {
        return;
    }
    esp_camera_fb_return(s_pending_fb);
    s_pending_fb = NULL;
}

bool camera_self_test_capture(void)
{
    uint8_t *buf = NULL;
    size_t len = 0;
    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) {
        ESP_LOGE(TAG, "self-test fb_get failed");
        return false;
    }
    buf = fb->buf;
    len = fb->len;
    bool ok = (fb->format == PIXFORMAT_JPEG && len > 1000);
    ESP_LOGI(TAG, "self-test JPEG %u bytes %s", (unsigned)len, ok ? "OK" : "FAIL");
    esp_camera_fb_return(fb);
    (void)buf;
    return ok;
}

static void record_buffer_clear(void)
{
    if (s_record_data) {
        free(s_record_data);
        s_record_data = NULL;
    }
    s_record_len = 0;
}

esp_err_t camera_start_record(void)
{
    if (s_recording) {
        return ESP_OK;
    }
    record_buffer_clear();
    s_recording = true;
    ESP_LOGI(TAG, "record started (stop 时导出快照/占位文件)");
    return ESP_OK;
}

esp_err_t camera_stop_record(void)
{
    s_recording = false;
    return ESP_OK;
}

esp_err_t camera_take_record_file(uint8_t **buf, size_t *len)
{
    if (!buf || !len) {
        return ESP_ERR_INVALID_ARG;
    }
    *buf = NULL;
    *len = 0;
    record_buffer_clear();

    if (s_ready) {
        uint8_t *jpeg = NULL;
        size_t jlen = 0;
        esp_err_t err = camera_capture_jpeg(&jpeg, &jlen);
        if (err == ESP_OK && jpeg && jlen > 0) {
            s_record_data = (uint8_t *)malloc(jlen);
            if (!s_record_data) {
                camera_release_jpeg(jpeg);
                return ESP_ERR_NO_MEM;
            }
            memcpy(s_record_data, jpeg, jlen);
            s_record_len = jlen;
            camera_release_jpeg(jpeg);
            *buf = s_record_data;
            *len = s_record_len;
            ESP_LOGI(TAG, "record file JPEG %u bytes", (unsigned)jlen);
            return ESP_OK;
        }
        if (jpeg) {
            camera_release_jpeg(jpeg);
        }
    }

    static const char stub[] = "AIFC-VIDEO-STUB-V1";
    s_record_data = (uint8_t *)malloc(sizeof(stub) - 1);
    if (!s_record_data) {
        return ESP_ERR_NO_MEM;
    }
    memcpy(s_record_data, stub, sizeof(stub) - 1);
    s_record_len = sizeof(stub) - 1;
    *buf = s_record_data;
    *len = s_record_len;
    ESP_LOGW(TAG, "record file stub %u bytes (no camera)", (unsigned)s_record_len);
    return ESP_OK;
}

void camera_release_record_data(uint8_t *buf)
{
    if (buf && buf == s_record_data) {
        record_buffer_clear();
    }
}

#else /* !CONFIG_CAMERA_ENABLE */

esp_err_t camera_init(void)
{
    ESP_LOGW(TAG, "camera disabled in menuconfig");
    return ESP_ERR_NOT_SUPPORTED;
}

bool camera_is_ready(void) { return false; }

esp_err_t camera_capture_jpeg(uint8_t **buf, size_t *len)
{
    (void)buf;
    (void)len;
    return ESP_ERR_NOT_SUPPORTED;
}

void camera_release_jpeg(uint8_t *buf) { (void)buf; }

bool camera_self_test_capture(void) { return false; }

esp_err_t camera_start_record(void)
{
    s_recording = true;
    return ESP_OK;
}

esp_err_t camera_stop_record(void)
{
    s_recording = false;
    return ESP_OK;
}

esp_err_t camera_take_record_file(uint8_t **buf, size_t *len)
{
    if (!buf || !len) {
        return ESP_ERR_INVALID_ARG;
    }
    static const char stub[] = "AIFC-VIDEO-STUB-V1";
    uint8_t *copy = (uint8_t *)malloc(sizeof(stub) - 1);
    if (!copy) {
        return ESP_ERR_NO_MEM;
    }
    memcpy(copy, stub, sizeof(stub) - 1);
    *buf = copy;
    *len = sizeof(stub) - 1;
    return ESP_OK;
}

void camera_release_record_data(uint8_t *buf)
{
    if (buf) {
        free(buf);
    }
}

#endif /* CONFIG_CAMERA_ENABLE */
