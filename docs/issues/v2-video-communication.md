# V2 视频通信协议 — Issues 拆分

> 来源 PRD: [v2-video-communication.md](../prd/v2-video-communication.md)

| # | 阶段 | 模块 | 状态 | 说明 |
|---|------|------|------|------|
| 1 | Phase 1 | M1 `MediaEncoderPipeline` | 🟡 代码完成 | 默认未启用；推流 NAL 路径待切换 |
| 2 | Phase 2 | M5 `VideoStreamManager` | 🟡 框架完成 | 预览模式已接线；GB28181/WebRTC NAL 消费者待接 |
| 3 | Phase 3 | M2 `SipUaClient` | 🟡 代码完成 | 未自动注册/未与 SessionManager 接线 |
| 4 | Phase 4 | M3/M4 WebRTC + MQTT | 🟢 部分上线 | MQTT 信令 + **HTTP 信令中继**（text1 backend） |
| 5 | Phase 5 | M6 SessionManager | 🟢 部分上线 | 预览推流、设备轮询、Web 联动 |
| 6 | Phase 6 | M7/M8 PTT + Watchdog | 🟡 部分 | PTT 分支存在；Watchdog 仅 NAL 模式 |
| 7 | Phase 7 | E2E | 🟡 进行中 | **AI-screen ↔ text1 ↔ 设备** JPEG 预览链路可测 |

## 当前可验收（2026-07-10）

- [x] text1 `POST /v1/webrtc/call/start` → 设备 HTTP poll 收到 `call_start`
- [x] 设备自动开录（MediaRecorder 不变）+ JPEG 预览 POST 到 backend
- [x] AI-screen（`D:\AI-srceen`）轮询 `/frame` 显示实时画面
- [x] Web 挂断 → 设备 poll 收到 `call_end` 并停止推流
- [x] 推流中 LED 红绿交替；不影响普通录像/拍照/侧键

## 待完成

- [ ] 启用 `MediaEncoderPipeline` + NAL 推流（真 WebRTC SRTP / GB28181 RTP）
- [ ] `google-webrtc` Android SDK 或等效媒体通道
- [ ] GB28181 `SipUaClient.register()` 生产配置与 INVITE 接线
- [ ] 推流 30min + 本地 MP4 完整 E2E（PRD §9 #4）
- [ ] PTT 对讲音频通道
- [ ] 多设备 4 路 Web 端压测

## Issue 1–7 原始验收项

见下方各 Issue 详情（历史记录）；以「当前可验收 / 待完成」为准。
