/**
 * 硬件引脚 — Seeed XIAO ESP32S3 + Sense 扩展座 + OV5640 相机模组
 *
 * 相机：扩展板固定占用 GPIO10–18,38–40,47,48 → camera_pins.h
 * 音频：双 INMP441 + MAX98357 使用 D5/D6/D7 + GPIO7/8（扩展板 SD 槽不插卡）
 * 详述：docs/XIAO硬件接线.md
 */
#ifndef BOARD_CONFIG_H
#define BOARD_CONFIG_H

#include "driver/gpio.h"
#include "hal/adc_types.h"

/* ── 按键（XIAO D0–D2） ── */
#define PIN_BTN_POWER   GPIO_NUM_1   /* D0 */
#define PIN_BTN_SHUTTER GPIO_NUM_2   /* D1 */
#define PIN_BTN_AI      GPIO_NUM_3   /* D2 */

/* ── I2S 双麦降噪 + MAX98357（勿插 MicroSD，否则 GPIO7/8/9 冲突） ── */
#define PIN_I2S_BCK     GPIO_NUM_6   /* D5 */
#define PIN_I2S_WS      GPIO_NUM_43  /* D6 */
/* 双 INMP441：#1 L/R→GND(左) #2 L/R→3.3V(右)，两路 SD 并接 D7(GPIO44) */
#define PIN_I2S_MIC_DIN GPIO_NUM_44  /* D7 */
#define PIN_I2S_AMP_DIN GPIO_NUM_8   /* 扩展座 SD_DATA0，MAX98357 DIN */
/* 仅自检：若 Mic2 SD 误接 SD_CLK(GPIO7) 会告警 */
#define PIN_I2S_MIC_SD2_WARN GPIO_NUM_7

/* ── IMU（可选）接 GPIO9/21；与 SD_CMD/LED 相邻，V1 可不焊 ── */
#define PIN_I2C_SDA     GPIO_NUM_21
#define PIN_I2C_SCL     GPIO_NUM_9
#define BOARD_IMU_OPTIONAL  1

/* ── TP4056 CHRG → 低=充电中 ── */
#define PIN_CHARGE_DETECT         GPIO_NUM_5   /* D4 */
#define CHARGE_DETECT_ACTIVE_LOW  1
#define CHARGE_DETECT_PULLUP      1

#define BTN_DEBOUNCE_MS         30
#define BTN_POWER_LONG_MS       3000
#define BTN_AI_DOUBLE_TAP_MS    600
#define BTN_SHUTTER_LONG_MS   1000

/* ── 电池分压 → ADC（XIAO D3 = GPIO4） ── */
#define BATTERY_ADC_GPIO            4
#define BATTERY_ADC_UNIT            ADC_UNIT_1
#define BATTERY_ADC_UNIT_CH         ADC_CHANNEL_3
#define BATTERY_ADC_ATTEN           ADC_ATTEN_DB_12
#define BATTERY_DIVIDER_R1_KOHM     200
#define BATTERY_DIVIDER_R2_KOHM     100
#define BATTERY_FULL_MV             4200
#define BATTERY_EMPTY_MV            3000
#define BATTERY_HYSTERESIS_PERCENT  2

#endif /* BOARD_CONFIG_H */
