/**
 * OV5640 @ Seeed XIAO ESP32S3 Sense 扩展座（官方 14 GPIO 相机座）
 * 来源：Seeed limengdu/XIAO-ESP32S3-Sense-camera camera_pins.h
 * 勿改：与扩展板 OV5640 模组硬连线一致
 */
#ifndef CAMERA_PINS_H
#define CAMERA_PINS_H

#include "driver/gpio.h"

#define CAM_PWDN_GPIO_NUM     (-1)
#define CAM_RESET_GPIO_NUM    (-1)
#define CAM_XCLK_GPIO_NUM     GPIO_NUM_10
#define CAM_SIOD_GPIO_NUM     GPIO_NUM_40
#define CAM_SIOC_GPIO_NUM     GPIO_NUM_39

#define CAM_Y9_GPIO_NUM       GPIO_NUM_48
#define CAM_Y8_GPIO_NUM       GPIO_NUM_11
#define CAM_Y7_GPIO_NUM       GPIO_NUM_12
#define CAM_Y6_GPIO_NUM       GPIO_NUM_14
#define CAM_Y5_GPIO_NUM       GPIO_NUM_16
#define CAM_Y4_GPIO_NUM       GPIO_NUM_18
#define CAM_Y3_GPIO_NUM       GPIO_NUM_17
#define CAM_Y2_GPIO_NUM       GPIO_NUM_15
#define CAM_VSYNC_GPIO_NUM    GPIO_NUM_38
#define CAM_HREF_GPIO_NUM     GPIO_NUM_47
#define CAM_PCLK_GPIO_NUM     GPIO_NUM_13

#define CAM_XCLK_FREQ_HZ      20000000

/* Sense 扩展板 SD 焊盘（V1 双麦方案勿插卡，GPIO7/8/9 给 I2S） */
#define SD_PIN_CLK            GPIO_NUM_7
#define SD_PIN_CMD            GPIO_NUM_9
#define SD_PIN_DATA0          GPIO_NUM_8

#endif /* CAMERA_PINS_H */
