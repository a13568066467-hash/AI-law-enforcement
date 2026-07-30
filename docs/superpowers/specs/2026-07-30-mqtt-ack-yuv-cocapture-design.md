# 指挥连线：MQTT Ack + YUV 共摄旁路

> **状态:** 已批准，实现中 · **日期:** 2026-07-30  
> **选择:** MQTT 送达方案 A（设备 HTTP ack）；画面方案 B（YUV 直喂 TRTC）

## 问题

1. 指挥信令靠设备 2s HTTP poll，点监看/连线平均多等约 1s。  
2. 通话中画面走 JPEG→Bitmap→I420，CPU 高、易卡、多一次有损。

## 方案

### P0 MQTT + Ack

- 后端实现阿里云 IoT `Pub` 发布器；未配置凭证时仍用 Logging。  
- Topic：`/sys/{pk}/{dn}/thing/service/command_call/start|end`，载荷与 poll 一致。  
- `device_id` 默认映射 `device_name=device_id`，`product_key` 来自环境变量。  
- 设备消费 start/occupy 后 `POST .../ack` 置 `start_delivered` / `join_delivered`。  
- MQTT 与 poll 并存；处理去重；MQTT 故障仍可靠 poll。

### P1 YUV 直喂

- 录像同会话 `ImageReader` 改为 `YUV_420_888`，drain 为 I420（连线期提高拷贝频率）。  
- `CommandCallVideoFrame` 支持 I420；TRTC 直送，不再经 JPEG。  
- `grabRecordingFrame` 仍返回 JPEG（由 YUV 压缩），供 PTT/AI。  
- 本机 1080p 录像不停；不第二相机。

## 非目标

云存储直播；替换 TRTC；删除 HTTP poll；硬编双路（原方案 C）。

## 验收

- 配置 IoT 后，点连线可在下一次 poll 前于设备日志见到 MQTT 并 ack。  
- 未配置 IoT 时行为与现网一致（Logging + poll）。  
- 连线中推流 CPU/流畅优于 JPEG 路径；录像文件完整。
