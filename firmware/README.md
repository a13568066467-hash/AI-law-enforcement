# 固件 — XIAO ESP32S3 + OV5640（ESP-IDF）



> 详细步骤见 **[docs/guides/固件开发指南.md](../docs/guides/固件开发指南.md)**  

> 硬件接线见 **[docs/hardware/XIAO硬件接线.md](../docs/hardware/XIAO硬件接线.md)**



## 快速开始



```bash

# 在「ESP-IDF 5.x CMD」中执行

cd firmware

idf.py set-target esp32s3

idf.py -p COMx build flash monitor

```



## 当前工程



| 文件 | 状态 | 说明 |

|------|------|------|

| `main/main.c` | ✅ | 入口，初始化 FSM + 按键 |

| `main/app_fsm.c` | ✅ | 录像/识别/AI 状态机 |

| `main/button.c` | ✅ | GPIO 1/2/3 三键 |

| `main/include/board_config.h` | ✅ | XIAO 引脚常量 |

| `main/include/camera_pins.h` | ✅ | OV5640 扩展座引脚 |

| `camera.c` | 🔲 待加 | esp_camera + OV5640 |

| `ble_service.c` | 🔲 待加 | NimBLE GATT |

| `audio_out.c` | ✅ | MAX98357 BCK6/WS43/DIN8 |

| `power_mgr.c` | ✅ | ADC GPIO4、低电、**边充边录**（CHRG GPIO5） |

| `standby_mgr.c` | ✅ | AI 聆听 5s 自动关；空闲轻睡眠 |

| `audio_i2s.c` | ✅ | I2S 全双工总线 |
| `audio_in.c` | ✅ | 双麦立体声 + 简易降噪 |
| `camera.c` | ✅ | OV5640 JPEG |
| `board_selftest.c` | ✅ | 引脚冲突 + 模块自检 |



## 引脚（XIAO Sense）



| 功能 | GPIO |

|------|------|

| 电源 / 快门 / AI | 1 / 2 / 3 |

| I2S 双麦立体声 / 喇叭 | DIN **44** / DOUT **8**（BCK6 WS43） |

| I2S 喇叭 DIN | 8 |

| 电池 ADC / CHRG | 4 / 5 |

| BMI270（可选） | 21 / 9 |

| OV5640 扩展座 | 见 `camera_pins.h` |



> **勿插 MicroSD**（与双麦争用 GPIO7/8/9）。



## 模块规划



```

main/

├── main.c

├── app_fsm.c         # 总状态机

├── button.c          # 三键 → 事件

├── camera.c          # 拍照 / MJPEG

├── audio_in.c        # 双麦 → Opus

├── audio_out.c       # Opus → 喇叭

├── ble_service.c     # BLE GATT

├── power_mgr.c       # 电量 ADC

└── standby_mgr.c     # 待机续航 / AI 省电

```



## 按键逻辑



- **GPIO1** 长按 3s → 开/关机

- **GPIO2** 单击 → 拍照（`app_fsm_on_shutter_click`）

- **GPIO3** 双击 → AI 助手



## 编译要求



- ESP-IDF **v5.2+**

- 目标 **esp32s3**

- 开发板 **Seeed XIAO ESP32S3 Sense**（16MB Flash + PSRAM）


