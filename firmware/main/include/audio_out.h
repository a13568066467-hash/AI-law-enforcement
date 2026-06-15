#ifndef AUDIO_OUT_H
#define AUDIO_OUT_H

#include <stddef.h>
#include <stdint.h>

/** 初始化 I2S → MAX98357A；menuconfig 可关闭（仅日志） */
void audio_out_init(void);

/** 播放 16bit mono PCM（采样率与初始化一致，默认 16kHz） */
void audio_out_play_pcm(const int16_t *samples, size_t num_samples);

/** 低电量提醒：喇叭播放（合成语音节奏 + 可选嵌入 PCM） */
void audio_out_play_low_battery_alert(void);

/** 开机自检短促双音 */
void audio_out_play_startup_beep(void);

#endif /* AUDIO_OUT_H */
