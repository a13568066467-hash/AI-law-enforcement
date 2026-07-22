# PRD: 画面监看（点选即看）

> 状态: Draft | 日期: 2026-07-22  
> 领域词: [`CONTEXT.md`](../../CONTEXT.md) · ADR: [`docs/adr/0003-live-preview-session.md`](../adr/0003-live-preview-session.md)、[`docs/adr/0002-trtc-for-command-calls.md`](../adr/0002-trtc-for-command-calls.md)  
> 相关: [`docs/prd/command-call-trtc.md`](command-call-trtc.md)（指挥连线；本 PRD 扩展「点选即可看画面」）

## Problem Statement

指挥中心 Web 控制台只有在发起指挥连线后才能看到设备画面；点选设备时卡片上的「待机画面」只是文案，没有实时视频。座席需要先「看一眼现场」再决定是否喊话，但又不想每次点选都当成完整指挥连线（对讲、打断 AI）。常驻多路监控墙不是本需求；需要的是点选一台已占用在线设备即可看到摄像头画面。

## Solution

引入**画面监看**：Web 点选已占用且在线设备即开启独立监看会话；媒体复用腾讯云 TRTC 与连线共摄旁路（ADR-0002/0003），设备只推视频、平台只拉视频，不对讲、不打断 AI、**不改变状态灯**。监看可同房升级为指挥连线（升麦/对讲并打断 AI，仍无 TTS，此时才红灯常亮）；也可不经监看直接发起连线。同一设备同时仅一路监看或一路连线。结束：换设备、停止监看、或 Web 关页/断连；首期无空闲超时。GB28181 与 JPEG `/frame` 不承接本能力。

## User Stories

1. As a 指挥中心座席, I want to点选一台已占用且在线的执法仪后立刻看到实时画面, so that 我无需先发起指挥连线就能判断现场。
2. As a 指挥中心座席, I want 画面监看默认不能对讲、平台不推麦, so that 「只看」与「喊话」语义清晰。
3. As a 指挥中心座席, I want 在监看中点击「发起连线」升级为指挥连线且画面不断流, so that 看完后可以无缝喊话。
4. As a 指挥中心座席, I want 不经监看也能直接「发起连线」, so that 紧急调度不必先走只看路径。
5. As a 指挥中心座席, I want 换选另一台设备时自动结束当前监看并开始新监看, so that 同时只占一路。
6. As a 指挥中心座席, I want 能显式「停止监看」, so that 可以结束推流而不必换设备。
7. As a 指挥中心座席, I want 关闭或刷新浏览器页时监看自动结束, so that 设备不会一直推流占资源。
8. As a 指挥中心座席, I want 点选空闲或离线设备时得到明确提示且不建监看, so that 不会空转 TRTC。
9. As a 指挥中心座席, I want 当设备已有一路监看或指挥连线时第二座席点选被拒绝并提示占用, so that 状态不会冲突。
10. As a 指挥中心座席, I want 监看失败（超时、进房失败等）时看到明确失败原因, so that 我能改选设备或重试。
11. As a 指挥中心座席, I want Web 界面区分「监看中」与「指挥连线中」, so that 我知道当前能否喊话。
12. As an 执勤员, I want 被画面监看时状态灯不变、无语音提示, so that 只看现场不改变机身灯语。
13. As an 执勤员, I want 画面监看不打断正在进行的 AI 助手会话, so that 只看现场不影响助手问答。
14. As an 执勤员, I want 监看升级为指挥连线时打断 AI 并进入对讲待命, so that 指挥喊话优先于助手。
15. As an 执勤员, I want 直接指挥连线与监看升级均无来电语音播报, so that 提示规则统一（连线靠红灯，监看无灯语）。
16. As an 执勤员, I want 画面监看期间本机循环录像不中断且与推流共摄, so that 取证文件完整且不抢第二路相机。
17. As an 执勤员, I want 未在录像时开始监看则自动开录再推流, so that 点选即看不会因未按录像键失败。
18. As an 执勤员, I want 监看结束后灯效仍按录像/待机等原规则显示, so that 不因监看粘滞红灯。
19. As an 执勤员, I want 设备不上报监看应答/拒绝类控制消息, so that 仍保持「只听指挥、自动执行」。
20. As a 后端服务, I want 画面监看有独立会话状态（与指挥连线区分）, so that 可审计「看」与「喊」并正确互斥/升级。
21. As a 后端服务, I want 监看开始/结束经 MQTT 下发并以 HTTP poll 兜底, so that 与现有连线信令通道一致。
22. As a 后端服务, I want 监看与连线共用 UserSig/连线房间签发能力, so that 不引入第二套媒体凭证体系。
23. As a 后端服务, I want 升级时复用同一连线房间并切换会话态为指挥连线, so that Web/设备不断流。
24. As a 后端服务, I want 拒绝未占用、离线或已有活跃监看/连线的设备上的新监看, so that 边界与指挥连线一致并互斥。
25. As a 后端服务, I want Web 断连/心跳失败时结束监看并通知设备, so that 关页清理可靠。
26. As a Web 控制台, I want 点选即调用开始监看并进房只拉视频, so that 符合点选即看。
27. As a Web 控制台, I want 监看中不 startLocalAudio（或等价不开麦）, so that 只看不对讲。
28. As a Web 控制台, I want 升级或直接连线时再开平台麦, so that 喊话能力按需出现。
29. As an Android 设备, I want 收到监看开始后自动进房、共摄推视频、不改状态灯、不启连线对讲、不打断 AI, so that 行为符合画面监看定义。
30. As an Android 设备, I want 收到升级为指挥连线后开启对讲策略并打断 AI, so that 同房升级完整。
31. As an Android 设备, I want 收到监看结束后退房并清理推流, so that 与结束连线对称且不误清录像灯。
32. As a 产品负责人, I want 首期不做多路旁听监看与常驻监控墙, so that 范围可控且不侵占 GB28181。
33. As a 产品负责人, I want 首期不做监看空闲超时, so that 结束规则先保持显式与关页清理。
34. As a 开发者, I want 不复活 JPEG `/frame` 作为监看媒体路径, so that 与 ADR-0003 一致。
35. As a 开发者, I want 不把监看塞进「视频-only 指挥连线」同一状态机冒充, so that 生命周期与 AI 优先级清晰。
36. As a QA, I want 用假 TRTC/假 MQTT 验收监看→升级→结束主路径, so that 不依赖腾讯云黑盒。
37. As a QA, I want 验证监看不打断 AI、升级打断 AI, so that 优先级策略可回归。
38. As a 系统运维, I want SecretKey 仍仅存后端, so that 监看不扩大密钥面。

## Implementation Decisions

### Architecture

- 遵循 ADR-0003：独立画面监看会话；媒体 TRTC；同房升级指挥连线；非 JPEG、非 GB28181 Web 墙。
- 遵循 ADR-0002：UserSig 仅后端签发；连线信令 MQTT 主路径 + HTTP 兜底。
- 指挥连线 PRD 中「来电语音提示」由本 PRD 修正为：**直接连线与监看升级均无 TTS**；**仅指挥连线**红灯常亮（画面监看不改灯）；升级/直接连线仍打断 AI。
- 设备互斥：`device_id` 上同时最多一个活跃会话（监看或连线）。

### Modules (logical)

- **后端监看/连线会话服务**：扩展现有指挥连线会话，增加监看状态、开始/结束监看、升级、互斥、Web 心跳/断连清理。
- **监看信令**：平台→设备的监看开始/结束；升级可复用「升为连线」或等价连线开始载荷（同房凭证不变）。
- **Android 监看/连线控制器**：区分监看进房与连线进房（共摄推视频共用；对讲与 AI 门闸仅连线态）。
- **Web 控制台**：点选开监看、停止监看、换设备切换、关页结束、发起连线（冷启动或升级）、状态展示。

### API / signaling contracts (behavioral)

- `start_watch(device_id)` → 监看会话 + 双方进房凭证；设备自动进房只推视频。
- `end_watch(session_id)` → 设备退房清理；换设备/停止/关页均走到结束。
- `upgrade_watch_to_call(session_id)` 或监看中 `start_command_call` 幂等升级 → 同房进入指挥连线态。
- 冷启动 `start_command_call(device_id)` 仍可用（无既有监看）。
- 设备 poll/MQTT：可消费监看开始、监看结束、连线开始（含升级）、连线结束；不上行应答/忙线/挂断。
- Web 需周期性心跳或等价机制；丢失则服务端结束监看。

### Interactions

```
点选已占用在线设备
  → 后端建监看会话与连线房间、签 UserSig
  → MQTT/HTTP 监看开始 → 设备共摄进房推视频、不改灯、不打断 AI
  → Web 进房只拉视频
  →（可选）发起连线 → 同房升级：平台开麦、设备对讲待命、打断 AI、无 TTS
  → 停止/换设备/关页 → 结束监看或结束连线 → 双方清理
```

### Testing Decisions（已与产品确认的接缝）

只断言外部可观察行为，不断言 TRTC SDK 内部、不做像素比对。

**主接缝 — 画面监看会话**

点选 → 设备进房只推视频 → Web 见画面 → 升级为指挥连线或结束 → 状态清理。假 TRTC / 假 MQTT。

**支撑接缝**

1. 监看/连线互斥与同房升级  
2. 监看信令（开始/结束；无 TTS；监看不打断 AI）  
3. 连线共摄（监看与连线共用）  
4. Web 点选生命周期（换设备、停止、关页）

**Prior art**

- `backend/tests` 指挥连线会话骨架测试  
- Android `CommandCallController*` / `CommandCallAiPriority*` 测试  
- Web 控制台现有连线 API 调用模式  

## Out of Scope

- 常驻多路监控墙 / 大屏多画面同屏  
- 多路旁听监看  
- 监看空闲超时自动结束（二期可加）  
- 复活 HTTP JPEG `/frame` 作为监看媒体  
- 用 GB28181 承接 Web 点选监看  
- 设备主动发起监看或连线  
- 改变本机录像分辨率/封装  
- TRTC 云端录制  

## Further Notes

- 发布 Issue 时标签：`ready-for-agent`。  
- 建议 `/to-issues` 按垂直切片拆：会话 API（监看+互斥+升级+心跳）、设备监看进房与 AI/灯效、Web 点选生命周期、升级/冷启动连线与无 TTS 修正、端到端验收。  
- 实施时同步修正指挥连线相关文案/行为中仍要求「来电语音提示」的部分，与本 PRD 一致。
