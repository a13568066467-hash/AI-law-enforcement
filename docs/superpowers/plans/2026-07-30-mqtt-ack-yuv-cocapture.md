# MQTT Ack + YUV 共摄 Implementation Plan

> **For agentic workers:** 按任务顺序实现；每任务可测。设计见 `docs/superpowers/specs/2026-07-30-mqtt-ack-yuv-cocapture-design.md`。

**Goal:** P0 打通指挥连线 MQTT 下行 + 设备 ack；P1 旁路改为 YUV 直喂 TRTC。

**Architecture:** 后端 `CommandCallMqttPublisher` 真发布；ack API 置 delivered；设备 MQTT/poll 双通道去重。Android `NativeRecorder` YUV drain → `CommandCallVideoFrame(I420)` → TRTC。

**Tech stack:** FastAPI/httpx、Aliyun IoT Pub、Kotlin Camera2/TRTC。

---

### Task 1: 后端 ack + 测试

**Files:** `backend/app/command_call/service.py`, `router.py`, `schemas.py`, `tests/test_command_call_ack.py`

- `ack_device_start(call_id, device_id)` / `ack_occupancy_join(device_id)`
- 路由：`POST /v1/command-call/{call_id}/device-ack`、`POST .../occupancy-room/join-ack`

### Task 2: 阿里云 IoT Publisher

**Files:** `backend/app/command_call/mqtt.py`, `aliyun_iot_pub.py`, `main.py`, `.env.example`

- 环境变量齐全则 `use_mqtt(Aliyun…)`，否则 Logging

### Task 3: 设备 ack + 去重

**Files:** `ApiClient.kt`, `SessionManager.kt`, `MqttTopicRouter.kt`

### Task 4: YUV 帧类型 + TRTC

**Files:** `CommandCallVideoFrame.kt`, `TrtcCommandCallRoomAdapter.kt`, `CommandCallCoCapture.kt`, `RecordingCommandCallFrameSource.kt`, `NativeRecorder.kt`, 单测

### Task 5: 回归

- `pytest` command_call 相关；Android 相关单元测试可编译
