#ifndef CAMERA_H
#define CAMERA_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

esp_err_t camera_init(void);
bool camera_is_ready(void);

esp_err_t camera_capture_jpeg(uint8_t **buf, size_t *len);
void camera_release_jpeg(uint8_t *buf);

esp_err_t camera_start_record(void);
esp_err_t camera_stop_record(void);

/** 结束录像并取出文件数据（调用方用 camera_release_record_data 释放） */
esp_err_t camera_take_record_file(uint8_t **buf, size_t *len);
void camera_release_record_data(uint8_t *buf);

/** 自检：拍一张 JPEG 并释放 */
bool camera_self_test_capture(void);

#endif /* CAMERA_H */
