#ifndef AUDIO_I2S_H
#define AUDIO_I2S_H

#include "esp_err.h"
#include "driver/i2s_std.h"

#define AUDIO_I2S_SAMPLE_RATE   16000

/** 共享 I2S 总线：TX→MAX98357(GPIO8)，RX←双麦立体声(GPIO44) */
esp_err_t audio_i2s_bus_init(void);
i2s_chan_handle_t audio_i2s_tx_chan(void);
i2s_chan_handle_t audio_i2s_rx_chan(void);
bool audio_i2s_bus_ready(void);

#endif /* AUDIO_I2S_H */
