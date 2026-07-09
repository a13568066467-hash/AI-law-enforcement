# PRD: V2 视频通信协议（GB28181 + WebRTC）

> 状态: Draft | 作者: Agent | 日期: 2026-07-08
> 父文档: [产品需求.md](../product/产品需求.md) §8.3 · [通信协议规范.md](../architecture/通信协议规范.md)

---

## 1. 背景

当前 V1 通信架构仅有 HTTP（短连接请求-响应）和 MQTT（轻量信令），无法承载实时视频流。V2 引入双通道视频通信：

| 场景 | 协议 | 远端 | 用途 |
|------|------|------|------|
| 专家远程连线 | WebRTC | 指挥中心 Web 控制台 | 低延迟视频通话 + 双向音频 |
| 平台级监控 | GB28181 | 国标 SIP 平台（海康/大华等） | 标准视频监控接入 |

**核心约束**：推流期间本机 1080p H.264 录制**不能中断**。

---

## 2. 目标与非目标

### 2.1 目标

| # | 目标 | 验收标准 |
|---|------|---------|
| G1 | 设备作为 GB28181 SIP UA 注册到国标平台 | 平台可拉取实时视频流，延迟 ≤ 2s |
| G2 | 设备通过 WebRTC 与 Web 控制台建立点对点视频通话 | 浏览器端看到画面，延迟 ≤ 500ms |
| G3 | 推流 + 本机录制同时进行 | 推流 30min 后本机文件完整可播放 |
| G4 | 指挥中心可同时查看多台设备画面 | 4 台设备同时推流，Web 端切换不卡顿 |
| G5 | 双向音频对讲 | PTT 按住说话，远程端清晰可辨 |
| G6 | 两种触发方式 | 云端下发指令 + SOS 键主动推流 |
| G7 | MQTT 信令不阻塞视频流 | SDP/ICE 通过 MQTT 传递，媒体流走 P2P |

### 2.2 非目标

- 不替代现有 HTTP 图片上传（识图仍走 `/v1/vision`）
- 不实现 RTMP/RTSP/HLS 等协议
- 不实现录像文件远程回放（那是 P3 需求）
- 本地录制格式不变（保持 H.264 MP4）
- 不实现群组视频会议（N > 2）

---

## 3. 架构

```
┌─────────────────────────────────────────────────────────────────┐
│                          指挥中心 Web                             │
│  ┌──────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │ GB28181 平台  │  │ WebRTC 浏览器端   │  │ MQTT 管理面      │  │
│  │ (SIP Server) │  │ (js SDK)         │  │                  │  │
│  └──────┬───────┘  └────────┬─────────┘  └────────┬─────────┘  │
│         │ SIP                │ SDP/ICE via MQTT    │             │
│         ▼                    ▼                     ▼             │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                  云 IoT 平台（MQTT Broker）                │   │
│  │  · WebRTC 信令通道        · 设备在线管理                  │   │
│  └──────────────────────────────────────────────────────────┘   │
│         │                                                        │
│         │ MQTT (SDP offer/answer/ICE)                            │
│         ▼                                                        │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              DSJ-ZECN6A1 Android 设备                      │   │
│  │  ┌──────────────┐  ┌─────────────────┐  ┌──────────────┐ │   │
│  │  │ GB28181 SIP  │  │ WebRTC Peer     │  │ MediaRecorder│ │   │
│  │  │ UA (注册/推流)│  │ (P2P 媒体流)    │  │ (本机录制)    │ │   │
│  │  └──────────────┘  └─────────────────┘  └──────────────┘ │   │
│  │         │                │                     │           │   │
│  │         └────────────────┼─────────────────────┘           │   │
│  │                          ▼                                 │   │
│  │            Camera2 H.264 编码器（单路 1080p 30fps）         │   │
│  │                          │                                 │   │
│  │              ┌───────────┴───────────┐                     │   │
│  │              │ Encoder Output Buffer │                     │   │
│  │              └───┬───────────────┬───┘                     │   │
│  │                  │               │                          │   │
│  │                  ▼               ▼                          │   │
│  │          MediaRecorder    MediaCodec                        │   │
│  │          (MP4 存文件)      (RTP 推流)                        │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 3.1 推流架构：单编码器 + 双输出

```
Camera2 (YUV 420)
    │
    ▼
MediaCodec (H.264, 1080p, 30fps, 8Mbps)
    │
    ├──► MediaMuxer ──► MP4 文件（本机录制，不受推流影响）
    │
    └──► ByteBuffer (提取 H.264 NAL 单元)
              │
              ├──► RTP 打包 ──► GB28181 SIP UA ──► 国标平台（PS 流 / RTP over UDP）
              │
              └──► RTP 打包 ──► WebRTC PeerConnection ──► 浏览器（SRTP/SCTP）
```

**关键设计**：
- **切换方案**：当前 `MediaRecorder` 是黑盒，无法从中提取 H.264 码流。V2 将编码层从 `MediaRecorder` 迁移到 `MediaCodec`，编码器输出同时喂给 `MediaMuxer`（写 MP4）和两个推流通道。
- **兼容性**：录制文件格式不变（H.264 MP4），不影响现有相册、存储等模块。

---

## 4. 模块拆解

### 4.1 M1: `MediaEncoderPipeline` — 编码管线重构

**影响范围**：`NativeRecorder.kt` 核心录制逻辑。

**改动**：
- 将 `MediaRecorder` 替换为 `MediaCodec` + `MediaMuxer` 组合
- `MediaCodec.Callback.onOutputBufferAvailable()` 中：
  - 将编码后的 `ByteBuffer` 写入 `MediaMuxer`（MP4 录制）
  - 同时将 NAL 单元缓存到线程安全的 `ConcurrentLinkedQueue<ByteArray>`
- 提供 `subscribeNalConsumer(name: String, consumer: (ByteArray) -> Unit)` 接口
- 确保录制停止时通知所有消费者取消订阅

**验收**：
- 录制的 MP4 文件与现有 `MediaRecorder` 输出**字节一致**（同一视频源、同一参数）
- 编码器在推流期间不丢帧（30fps 稳定输出）
- 31 个已有单元测试全部通过

### 4.2 M2: `SipUaClient` — GB28181 SIP UA

**库选择**：
- 方案 A：自研轻量 SIP UA（基于 UDP + SDP 解析）
- 方案 B：集成 `liblinphone` / `PJSIP`（推荐）

| 对比 | 自研 | PJSIP |
|------|------|-------|
| 开发量 | 大（SIP 状态机、SDP 解析、MD5 摘要认证） | 小（封装 API） |
| 体积 | 0（纯 Kotlin） | ~5MB .so |
| 可靠性 | 需大量测试 | 业界验证 |
| 建议 | — | **推荐 PJSIP**（国标 SIP 协议栈成熟） |

**SIP 消息流**：
```
Device                                SIP Server
  │                                       │
  │ REGISTER (Authorization: MD5)         │
  │ ────────────────────────────────────► │
  │ 200 OK                                │
  │ ◄──────────────────────────────────── │
  │                                       │
  │          ... (Server 发起 INVITE)      │
  │                                       │
  │ INVITE sdp (H.264 payload 96)         │
  │ ◄──────────────────────────────────── │
  │ 200 OK sdp (device video/audio port)  │
  │ ────────────────────────────────────► │
  │ ACK                                   │
  │ ◄──────────────────────────────────── │
  │                                       │
  │ ◄══════════ RTP (video) ════════════► │
  │ ◄══════════ RTP (audio) ════════════► │
  │                                       │
  │ BYE                                   │
  │ ◄────────── or ───────────►           │
```

**SDP 关键字段**：
```
m=video {port} RTP/AVP 96
a=rtpmap:96 H264/90000
a=fmtp:96 profile-level-id=42001f;packetization-mode=1

m=audio {port} RTP/AVP 8
a=rtpmap:8 PCMA/8000
```

**模块接口**：
```kotlin
object SipUaClient {
    fun configure(serverIp: String, serverPort: Int, deviceId: String, password: String)
    fun register()
    fun unregister()
    fun startStream(rtpVideoPort: Int, rtpAudioPort: Int)
    fun stopStream()
    fun isRegistered(): Boolean
    fun isStreaming(): Boolean
    fun setNalConsumer(consumer: (ByteArray) -> Unit)
}
```

### 4.3 M3: `WebRtcPeer` — WebRTC 点对点连接

**库选择**：Google WebRTC Android SDK（`org.webrtc:google-webrtc:1.0.+`）

**信令流程（通过 MQTT）**：

```
Device                                Cloud (MQTT)                              Browser
  │                                       │                                       │
  │ Publish SDP offer                     │                                       │
  │ /s/{pk}/{dn}/event/webrtc/sdp/offer   │                                       │
  │ ────────────────────────────────────► │ Subscribe                             │
  │                                       │ ────────────────────────────────────► │
  │                                       │                                       │
  │                                       │ Publish SDP answer                    │
  │ Subscribe                             │ /s/{pk}/{dn}/service/webrtc/sdp       │
  │ ◄──────────────────────────────────── │ ◄──────────────────────────────────── │
  │                                       │                                       │
  │               ICE Candidate 交换（同上双向）                                  │
  │                                       │                                       │
  │ ◄══════════════ SRTP/SCTP (P2P) ═══════════════════════════════════════════► │
```

**MQTT Topic 扩展**：

| Topic | 方向 | 内容 |
|-------|------|------|
| `/event/webrtc/sdp/offer` | 设备→云端 | `{type:"offer", sdp:"..."}` |
| `/event/webrtc/ice/add` | 设备→云端 | `{candidate:"...", sdpMid:"...", sdpMLineIndex:0}` |
| `/service/webrtc/sdp/answer` | 云端→设备 | `{type:"answer", sdp:"..."}` |
| `/service/webrtc/ice/add` | 云端→设备 | `{candidate, sdpMid, sdpMLineIndex}` |
| `/service/webrtc/call/start` | 云端→设备 | `{caller:"web-console"}` |
| `/service/webrtc/call/end` | 云端→设备 | `{}` |

**PeerConnection 配置**：
```kotlin
PeerConnectionFactory.builder()
    .setVideoEncoderFactory(DefaultVideoEncoderFactory(...))
    .setVideoDecoderFactory(DefaultVideoDecoderFactory(...))
    .createPeerConnection(
        listOf(iceServer),  // STUN/TURN
        observer
    )
```

**视频轨道**：直接从 `MediaEncoderPipeline` 的 NAL 队列获取帧，通过 `MediaStreamTrack` 推送。

### 4.4 M4: `MqttTopicRouter` 扩展 — WebRTC 信令 Topic

在现有 `MqttTopicRouter.kt` 的 `serviceTopics` 中新增：

```kotlin
private val webrtcTopics = listOf(
    "/service/webrtc/sdp/answer",
    "/service/webrtc/ice/add",
    "/service/webrtc/call/start",
    "/service/webrtc/call/end",
)
```

`dispatch()` 中新增分发分支到 `WebRtcPeer`。

### 4.5 M5: `VideoStreamManager` — 统一推流管理

**职责**：协调 GB28181 和 WebRTC 两个推流通道，管理 NAL 订阅。

```kotlin
object VideoStreamManager {
    enum class Mode { NONE, GB28181, WEBRTC, BOTH }

    fun startGb28181()
    fun stopGb28181()
    fun startWebRtc()
    fun stopWebRtc()
    fun isStreaming(): Boolean
    fun currentMode(): Mode
    fun setNalSource(source: ConcurrentLinkedQueue<ByteArray>)
}
```

### 4.6 M6: `SessionManager` 扩展

**新增方法**：

```kotlin
// 视频通信入口
fun startVideoStream(mode: VideoStreamManager.Mode, reason: String)
fun stopVideoStream(reason: String)

// WebRTC 信令处理
fun onWebRtcSdpAnswer(sdp: String)
fun onWebRtcIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)
fun onWebRtcCallStart(caller: String)
fun onWebRtcCallEnd()

// GB28181 状态
fun onSipRegistered()
fun onSipUnregistered()
fun onSipStreamActive()
```

**状态栏 UI 提示**：
```
推流中 · GB28181     ← 顶栏红点 + 文字
推流中 · 专家连线    ← WebRTC 激活时
```

### 4.7 M7: PTT 视频通话对讲

**改动的文件**：`RecorderKeyDispatcher.kt`

**PTT 行为状态机**：

```
常态（无视频通话）：
  短按 → 白光灯
  长按 → AI 抓帧问答

视频通话中：
  短按 → 无效（通话中白光灯不可用）
  长按 → 开始发送音频（按住说话），松手停止
```

**实现**：
```kotlin
fun handleKeyEvent(session: SessionManager, event: KeyEvent, source: RecorderKeyRoute.Source): Boolean {
    if (pttKeys.contains(event.keyCode)) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (VideoStreamManager.isStreaming()) {
                    // 视频通话中：按住说话
                    handlePttTalkDown()
                } else {
                    // 常态：长按 AI
                    startPttLongPressTimer()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (VideoStreamManager.isStreaming()) {
                    handlePttTalkUp()
                } else {
                    handlePttRelease()
                }
            }
        }
    }
}
```

### 4.8 M8: `StreamingPipelineWatchdog` — 推流管线监控

类似 `RecordingPipelineWatchdog`，监控推流健康状态：

| 检测项 | 阈值 | 动作 |
|--------|------|------|
| NAL 队列为空 | 30s | WARN 日志 |
| RTP 包发送失败 | 连续 50 次 | 自动降级：关闭推流 → TTS 播报 |
| 网络带宽不足 | 丢包率 > 20% | 尝试降帧率/码率 |

---

## 5. 多设备同时推流

指挥中心 Web 端通过 MQTT 向多台设备同时下发推流指令：

```
Web Console ──► MQTT ──► /service/webrtc/call/start (device_1)
                      ──► /service/webrtc/call/start (device_2)
                      ──► /service/webrtc/call/start (device_3)
                      ──► /service/webrtc/call/start (device_4)
```

每台设备独立建立 PeerConnection，Web 端渲染为 4 个 `<video>` 标签。

**性能预估**（单台 DSJ 设备）：
- 编码：H.264 1080p 30fps 8Mbps ≈ 1MB/s
- 网络：4G 上行带宽约 10~30Mbps，单路推流安全
- 内存：MediaCodec 缓冲区 ≈ 2MB，RTP 打包缓冲 ≈ 1MB

---

## 6. 不影响现有功能

| 模块 | 影响 |
|------|------|
| `NativeRecorder` | **重构**：MediaRecorder → MediaCodec + MediaMuxer，保持 API 不变 |
| `SessionManager` | 新增 7 个方法，不修改现有方法签名 |
| `RecorderKeyDispatcher` | 新增视频通话 PTT 分支 |
| `MqttTopicRouter` | 新增 4 个 WebRTC Topic |
| `ApiClient` (HTTP) | 无影响 |
| `PttSnapAskController` | 无影响（视频通话中不触发 AI 交互） |
| `DeviceStatusIndicator` | 新增"推流中"指示灯状态（红灯+绿灯交替快闪） |
| 存储（录制文件） | 无影响（编码输出不变） |

---

## 7. 实施顺序

| 阶段 | 模块 | 预估工作量 | 依赖 |
|------|------|-----------|------|
| **Phase 1** | M1 `MediaEncoderPipeline` 重构 | 3~5 天 | 无 |
| **Phase 2** | M5 `VideoStreamManager` NAL 分发框架 | 1~2 天 | M1 |
| **Phase 3** | M2 `SipUaClient` GB28181 SIP UA | 3~5 天 | M5 |
| **Phase 4** | M4 + M3 WebRTC 信令 + PeerConnection | 3~5 天 | M5 |
| **Phase 5** | M6 `SessionManager` 集成 | 1~2 天 | M2, M3 |
| **Phase 6** | M7 PTT 对讲 + M8 监控 | 1~2 天 | M5 |
| **Phase 7** | 端到端测试 + 多设备压测 | 2~3 天 | M6 |

---

## 8. 风险

| 风险 | 缓释 |
|------|------|
| MediaCodec 异步模式兼容性 | 备选：`MediaCodec.createInputSurface()` + 同步模式 |
| PJSIP .so 体积 → APK 增大 5MB | 接受（执法仪 8GB ROM 可承受） |
| 4G 弱网导致推流卡顿 | FEC（前向纠错）+ 动态码率调整 |
| WebRTC Android 库与现有依赖冲突 | 单独验证 `google-webrtc` 在 API 29 的兼容性 |
| GB28181 SIP Server 未搭建 | Phase 3 开发期间用 `sip-test` 工具模拟 |

---

## 9. 验收总表

| # | 验收项 | 标准 |
|---|--------|------|
| 1 | GB28181 注册 | 设备启动后 10s 内注册到 SIP Server |
| 2 | GB28181 平台拉流 | 平台发起 INVITE → 5s 内出画面，延迟 ≤ 2s |
| 3 | WebRTC 通话 | 浏览器端延迟 ≤ 500ms，音画同步 |
| 4 | 推流+录制并存 | 推流 30min 后 MP4 文件完整可播 |
| 5 | 多设备推流 | 4 台设备同时推流，Web 端不卡顿 |
| 6 | PTT 对讲 | 按住 200ms 内远端听到声音 |
| 7 | 云端下发推流 | `/service/webrtc/call/start` → 3s 内建立连接 |
| 8 | SOS 主动推流 | 长按 F3 → 5s 内 Web 端收到视频 |
| 9 | 已有单元测试 | 31 个全部通过 |
| 10 | 存储空间 | 推流不产生额外本地文件 |

---

## 10. 下一步

PRD 确认后，按 `/to-issues` 拆分为独立可抓取的 GitHub Issues。
