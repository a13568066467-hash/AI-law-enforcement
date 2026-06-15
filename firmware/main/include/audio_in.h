#ifndef AUDIO_IN_H
#define AUDIO_IN_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
    int32_t rms_left;
    int32_t rms_right;
    int32_t rms_mono;
    bool left_active;
    bool right_active;
} audio_in_levels_t;

void audio_in_init(void);
bool audio_in_is_ready(void);

/** 读一帧立体声 PCM；返回样本对数（L/R 交错），失败返回 0 */
size_t audio_in_read_stereo(int16_t *lr_buf, size_t max_pairs);

/** 双麦降噪：L/R 差分增强语音（简易波束），输出 mono */
size_t audio_in_read_denoised_mono(int16_t *mono, size_t max_samples);

/** 自检：采样 200ms 报告两路 RMS */
bool audio_in_self_test_sample(audio_in_levels_t *out);

void audio_in_start_stream(void);
void audio_in_stop_stream(void);
bool audio_in_streaming(void);

#endif /* AUDIO_IN_H */
