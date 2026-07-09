# V2 视频通信协议 — Issues 拆分

> 来源 PRD: [v2-video-communication.md](../prd/v2-video-communication.md)

| # | 阶段 | 模块 | 工时 | 状态 |
|---|------|------|------|------|
| 1 | Phase 1 | M1 `MediaEncoderPipeline` — MediaRecorder → MediaCodec+MediaMuxer | 3-5d | ✅ 完成 |
| 2 | Phase 2 | M5 `VideoStreamManager` — NAL 分发框架 | 1-2d | ✅ 完成 |
| 3 | Phase 3 | M2 `SipUaClient` — GB28181 SIP UA | 3-5d | ✅ 完成 |
| 4 | Phase 4 | M3 `WebRtcPeer` + M4 `MqttTopicRouter` 扩展 | 3-5d | ✅ 完成 |
| 5 | Phase 5 | M6 `SessionManager` 集成 | 1-2d | ✅ 完成 |
| 6 | Phase 6 | M7 PTT 对讲 + M8 `StreamingPipelineWatchdog` | 1-2d | ✅ 完成 |
| 7 | Phase 7 | 端到端测试 + 编译验证 | 2-3d | ✅ 完成 |

## Issue 1: M1 `MediaEncoderPipeline` — 编码管线重构

**目标**：将 `NativeRecorder` 的编码层从 `MediaRecorder`（黑盒）迁移到 `MediaCodec` + `MediaMuxer`，使编码输出可以双路分发给文件写入和推流通道。

**验收**：
- [ ] `MediaCodec` H.264 1080p 30fps 8Mbps 稳定编码
- [ ] `MediaMuxer` 输出 MP4 文件与原有 MediaRecorder 输出一致
- [ ] NAL 单元通过 `ConcurrentLinkedQueue<ByteArray>` 暴露给消费者
- [ ] `subscribeNalConsumer()` / `unsubscribeNalConsumer()` 接口
- [ ] 现有 31 个单元测试全部通过
- [ ] 录制/停止/分段行为不变

## Issue 2: M5 `VideoStreamManager` — NAL 分发框架

**目标**：统一管理 GB28181 和 WebRTC 两个推流通道，从 `MediaEncoderPipeline` 订阅 NAL 单元并分发给活跃通道。

**验收**：
- [ ] `startGb28181()/stopGb28181()` 正确管理通道生命周期
- [ ] `startWebRtc()/stopWebRtc()` 正确管理通道生命周期
- [ ] 支持 `NONE / GB28181 / WEBRTC / BOTH` 四种模式
- [ ] NAL 队列无泄漏（停止时清空订阅）

## Issue 3: M2 `SipUaClient` — GB28181 SIP UA

**目标**：设备作为 SIP UA 注册到国标平台，支持 REGISTER/INVITE/BYE 标准流程。

**验收**：
- [ ] SIP REGISTER（含 MD5 摘要认证）→ 200 OK
- [ ] 接收 INVITE → 200 OK（含正确 SDP）
- [ ] RTP 视频推流（H.264 over PS）
- [ ] BYE 结束推流
- [ ] 断线自动重注册

## Issue 4: M3 `WebRtcPeer` + M4 MQTT 扩展

**目标**：WebRTC 点对点连接，MQTT 承载信令，SRTP 媒体流。

**验收**：
- [ ] PeerConnection 创建 + ICE 协商
- [ ] SDP offer/answer 通过 MQTT 传递
- [ ] ICE candidate 通过 MQTT 传递
- [ ] 浏览器端看到视频画面
- [ ] 4 个新 MQTT Topic 订阅/发布正确

## Issue 5: M6 `SessionManager` 集成

**目标**：将视频通信入口接入 SessionManager，支持云端下发和 SOS 键触发。

**验收**：
- [ ] `startVideoStream()` / `stopVideoStream()` 入口
- [ ] WebRTC 信令回调接线（SDP answer + ICE）
- [ ] 顶栏 UI 提示"推流中"
- [ ] LED 指示灯（红灯+绿灯交替快闪）

## Issue 6: M7 PTT 对讲 + M8 监控

**目标**：视频通话中 PTT 键改为"按住说话"，推流管线健康监控。

**验收**：
- [ ] 视频通话中短按 PTT 不触发白光灯
- [ ] 视频通话中长按 PTT 开始/停止音频发送
- [ ] NAL 队列空 30s → WARN
- [ ] RTP 连续失败 50 次 → 自动关闭推流 + TTS 播报

## Issue 7: 端到端测试

**目标**：全链路验证，确保所有功能正常。

**验收**：
- [ ] 推流 30min + 录制文件完整可播
- [ ] 31 个已有测试全部通过
- [ ] 新增模块单元测试覆盖
- [ ] APK 编译安装成功
