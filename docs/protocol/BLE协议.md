# BLE 通信协议（V1 草案）

## Service UUID

```
主服务: 0000A001-0000-1000-8000-00805F9B34FB
```

## Characteristics

| UUID 后缀 | 名称 | 方向 | 说明 |
|-----------|------|------|------|
| A002 | CMD_WRITE | App → 设备 | 命令控制 |
| A003 | CMD_NOTIFY | 设备 → App | 事件通知 |
| A004 | AUDIO_TX | 设备 → App | 麦克风 Opus 帧 |
| A005 | AUDIO_RX | App → 设备 | TTS Opus 帧 |
| A006 | IMAGE_TX | 设备 → App | JPEG 分片 |
| A007 | VIDEO_TX | 设备 → App | 录像文件分片 |
| A008 | SENSOR_NOTIFY | 设备 → App | 电量/IMU/状态 |
| A009 | OTA | 双向 | 固件升级 |

## 命令格式 (CMD_WRITE)

```
[CMD_ID:1B][PAYLOAD_LEN:2B][PAYLOAD:变长]
```

| CMD_ID | 名称 | Payload |
|--------|------|---------|
| 0x01 | START_RECORD | 无 |
| 0x02 | STOP_RECORD | 无 |
| 0x03 | CAPTURE | 无 |
| 0x04 | START_AI_LISTEN | 无 |
| 0x05 | STOP_AI_LISTEN | 无 |
| 0x06 | SET_VOLUME | [0-100] |
| 0x07 | POWER_OFF | 无 |
| 0x08 | START_OTA | [size:4B][crc:4B] |

## 事件通知 (CMD_NOTIFY)

| EVT_ID | 名称 | Payload |
|--------|------|---------|
| 0x81 | RECORD_STARTED | 无 |
| 0x82 | RECORD_STOPPED | [duration_ms:4B][file_size:4B] |
| 0x83 | CAPTURE_DONE | [image_size:4B] |
| 0x84 | AI_LISTENING | 无 |
| 0x85 | AI_IDLE | 无 |
| 0x86 | LOW_BATTERY | [percent:1B] |
| 0x87 | ERROR | [code:1B] |

## 音频帧 (AUDIO_TX / AUDIO_RX)

```
[SEQ:2B][TIMESTAMP:4B][OPUS_FRAME:变长]
```

- 采样率：16 kHz
- 帧长：20 ms
- 编码：Opus

## 图片分片 (IMAGE_TX)

```
[MSG_ID:2B][SEQ:2B][TOTAL:2B][DATA:≤512B]
```

## 录像文件分片 (VIDEO_TX)

```
[FILE_ID:4B][SEQ:2B][TOTAL:2B][DATA:≤512B]
```

## 传感器上报 (SENSOR_NOTIFY)

```
[BATTERY:1B][STATE:1B][FLAGS:1B]

STATE: 0=IDLE 1=AI 2=CAPTURE 3=RECORD
FLAGS: bit0=charging（边充边录时=1）bit1=mounted（胸挂/头戴）

**充电中（bit0=1）：** 允许边充边录像/AI；低电不强制停录。见 [边充边录.md](../hardware/边充边录.md)。
```

## 状态互斥

App 收到 STATE=RECORD(3) 时：
- 不应发送 START_AI_LISTEN，除非先 STOP_RECORD
- 设备固件收到 START_AI_LISTEN 且正在录像 → 返回 ERROR(0x01) 或自动停录（可配置）
