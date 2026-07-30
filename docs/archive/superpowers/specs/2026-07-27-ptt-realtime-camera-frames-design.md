# PTT 实时语音期间推摄像头帧（方案 A）

**日期:** 2026-07-27  
**状态:** 已实现

## 背景

物理 PTT 全双工已接入百炼 `qwen3.5-omni-flash-realtime`，但会话只推 PCM。模型指令写明「看不到摄像头」，现场/型号/隐患依赖工具 `capture_and_explain`（抓一帧 → `/v1/vision`）。Omni Realtime 本身支持 `input_image_buffer.append`（约 1 帧/秒），当前未使用。

## 目标

按住 PTT 收音期间将当前摄像头 JPEG 送入 Realtime：录像中约 **1 fps**，未录像每轮最多 **1 帧**；松手 `commit` 后模型用「语音 + 同步画面」直接回答型号、隐患等。

## 非目标

- 未按 PTT 时后台常开推帧
- WebRTC/TRTC 视频轨直喂 Realtime
- 改 VAD 为全双工免按键对话
- 替换或删除 `capture_and_explain`（保留作兜底与录像控制配套）

## 架构与数据流

```
按住 PTT
  ├─ PCM（现有二进制）──► 后端桥 ──► input_audio_buffer.append
  └─ JPEG（录像 ~1fps / 未录像最多 1 帧）──► 后端桥 ──► input_image_buffer.append
松手
  └─ commit ──► 同时提交音/图缓冲 ──► response.create ──► 口语回答
```

边界：

- 仅在 `LISTENING`（按住）推帧；思考/播报不推
- 帧源复用 `SessionManager.grabSnapshot`（录像中从录像流；未录像则临时开相机一次，不再每秒开）
- JPEG 约 480p；单张 Base64 后 ≤256KB（建议原图 ≤190KB）
- 须先有至少一包音频，再发图（百炼 API 约束）
- 指挥连线中仍禁止 AI（现有 `CommandCallAiPriority`）

## 协议

App ↔ 后端（现有 `/v1/realtime/voice` WebSocket）：

| 方向 | 形态 | 含义 |
|------|------|------|
| App→后端 | 二进制 | PCM（不变） |
| App→后端 | JSON `{"type":"image","image":"<jpeg-base64>"}` | 新增一帧 |
| App→后端 | `commit` / `cancel` / `tool_result` | 不变 |
| 后端→百炼 | `input_audio_buffer.append` / `input_image_buffer.append` | 桥接翻译 |

后端对非法或过大图：丢弃该帧并打日志，不断开会话。

## 指令与工具

`DEFAULT_INSTRUCTIONS`：

- 删除「你本身看不到摄像头」
- 写明：按住期间会收到连续画面帧；回答现场/型号/隐患时优先依据这些帧
- 仅当帧缺失、模糊或仍不够时，再调 `capture_and_explain`

保留工具：`start_recording` / `stop_recording` / `capture_and_explain`。  
画面问答主路径 = 推帧；工具 = 兜底。

## App 采帧节奏

| 项 | 约定 |
|----|------|
| 频率 | 录像中目标 1 fps；上一帧未完成则跳过，不排队。未录像每轮按住最多 1 帧（避免反复 `grabSingleFrame` 开相机占锁） |
| 起点 | `beginCapture` 后先发至少一包 PCM，再开定时抓帧 |
| 终点 | `onPttUp` / `cancel` / 指挥打断 → 立刻停定时器 |
| 压缩 | JPEG，约 480p 宽边；过大再降质量，仍超则丢帧 |
| 线程 | 抓帧/编码不挡录音线程 |

## 失败与降级

- 单帧抓拍/编码/发送失败 → 跳过该帧，继续收音
- 整段按住 0 帧成功 → 仍可 `commit` 纯语音；模型可再调 `capture_and_explain`
- 非 DSJ 且未录像无法抓帧 → 同 0 帧，不额外弹窗打断
- Realtime 断线 → 沿用现有重连/报错，不因单帧失败单独报连不上

## 涉及改动

- Android：`PttSnapAskController` 定时抓帧；`RealtimeVoiceClient` / `RealtimeVoiceProtocol` 发 `image`
- 后端：`realtime_voice` 桥接 `input_image_buffer.append`；更新 `DEFAULT_INSTRUCTIONS`
- 测试：协议/桥接单测；手测验收剧本

## 验收

1. 绑机后对准铭牌，按住 PTT 问「什么型号」→ 松手后能答出可读型号（或明确说看不清）
2. 按住期间可见约每秒一条 `image`；松手后不再发
3. 未录像也能抓到帧（DSJ）；指挥连线中按住 PTT 仍不起 AI
4. 遮住镜头问隐患 → 不瞎编具体读数；可要求重拍或工具兜底
5. 弱网丢几帧 → 整轮不崩，仍能出回答

## 决策记录

- 选型方案 A（按住期间连续推帧），否决松手前仅 1～2 帧（B）与维持纯工具抓拍（C）作为主路径
- 推帧与 TRTC 指挥连线解耦；AI 画面不走 TRTC 轨
