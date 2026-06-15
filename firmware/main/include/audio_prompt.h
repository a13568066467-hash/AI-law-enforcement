#ifndef AUDIO_PROMPT_H
#define AUDIO_PROMPT_H

#include <stdint.h>

/** 低电量语音提醒（<10%）；无 I2S 时打日志，后续接 MAX98357 本地 PCM 或 App TTS */
void audio_prompt_low_battery(uint8_t percent);

#endif /* AUDIO_PROMPT_H */
