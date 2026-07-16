# V2 视频通信协议 — Issues 拆分

> 来源 PRD: [v2-video-communication.md](../prd/v2-video-communication.md)

| # | 阶段 | 模块 | 状态 | 说明 |
|---|------|------|------|------|
| 1 | Phase 1 | M1 `MediaEncoderPipeline` | 🟢 默认启用 | 循环录像默认走管线；SPS/PPS 进 NAL；通话结束不误清标志 |
| 2 | Phase 2 | M5 `VideoStreamManager` | 🟢 RTP 已接 | `RtpPacketizer` FU-A；GB28181 消费者发真 RTP |
| 3 | Phase 3 | M2 `SipUaClient` | 🟡 接线完成 | INVITE → 开录 + RTP；未接生产 SIP 平台配置 |
| 4 | Phase 4 | M3/M4 WebRTC + MQTT | 🟡 部分 | HTTP 信令 + JPEG/NAL；**无 google-webrtc SRTP** |
| 5 | Phase 5 | M6 SessionManager | 🟢 部分 | 预览 + GB28181 拉流编排 + Watchdog |
| 6 | Phase 6 | M7/M8 PTT + Watchdog | 🟡 部分 | Watchdog 已挂；PTT 音频轨未完成 |
| 7 | Phase 7 | E2E | 🟡 进行中 | JPEG 预览可测；真 WebRTC/国标平台联调待做 |

## 2026-07-16 本轮修复

- [x] CSD（SPS/PPS）写入 NAL 队列；推流中途开启时从 MediaFormat 注入
- [x] `endCall` 录像中不清 `useMediaEncoderPipeline`（防停录走错路径）
- [x] 循环录像默认 `useMediaEncoderPipeline = true`
- [x] `RtpPacketizer`（单包 + FU-A）+ 单元测试
- [x] GB28181 `createGb28181Consumer` 发标准 RTP；`SipUaClient.startStreaming` 挂接分发
- [x] `HttpNalRelay` 对 SPS/PPS/IDR 不节流

## 当前可验收

- [x] 循环录像默认 MediaEncoderPipeline（本机 MP4 + 可抽 NAL）
- [x] text1 HTTP 信令 + JPEG 预览 E2E
- [x] 连线时 NAL POST `/nal`（含参数集）
- [x] INVITE 路径可启动 RTP UDP（需配置 SIP 服务器联调）

## 待完成（朝 PRD 终态）

- [ ] 接入 `google-webrtc` 真 SRTP 媒体（替换 stub `WebRtcPeer`）
- [ ] GB28181 生产 `configure` + 自动 `register`（平台账号）
- [ ] 推流 30min + 本地 MP4 完整 E2E
- [ ] PTT 对讲音频通道
- [ ] 多设备 4 路 Web 端压测
