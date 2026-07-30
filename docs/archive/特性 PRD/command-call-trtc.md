# PRD: 指挥连线（腾讯云 TRTC）

> 状态: Draft | 日期: 2026-07-20  
> 领域词: [`CONTEXT.md`](../../CONTEXT.md) · ADR: [`docs/adr/0002-trtc-for-command-calls.md`](../adr/0002-trtc-for-command-calls.md)  
> 相关旧稿: [`docs/prd/v2-video-communication.md`](v2-video-communication.md)（专家连线改由本 PRD 的 TRTC 路径替代）

## Problem Statement

指挥中心无法与正在执勤的执法仪建立可靠的实时音视频连线。现有仓库里的 WebRTC 路径仍是信令/预览占位，不能在「本机循环录像不能停」的前提下完成真正的指挥连线；自建 google-webrtc 又带来 STUN/TURN、弱网与工程成本。指挥员需要从浏览器一键呼叫某台已占用设备，立刻看到现场画面并能对讲；执勤员需要单手作业下自动接听，且录像取证不被打断。

## Solution

引入**指挥连线**：媒体走腾讯云 TRTC，呼叫控制走本系统自建**连线信令**（MQTT 主路径，HTTP 兜底）。首期仅平台呼叫设备；设备默认自动接听并进**连线房间**，伴语音提示。本机继续 1080p 循环录像（**连线共摄**）；TRTC 以自定义视频旁路推约 720p。设备侧 **连线对讲** 为 PTT 半双工；平台侧可常开麦。后端签发短期 UserSig，SecretKey 永不下发到 App 或浏览器。指挥来电优先于 AI 全双工语音。不做云端通话录制；设备主动呼叫列为二期。TRTC 正式替代自建 WebRTC 专家连线；GB28181 国标监控保持独立。

## User Stories

1. As a 指挥中心座席, I want to从设备列表选择一台在线执法仪并发起指挥连线, so that 我能立即查看现场画面。
2. As a 指挥中心座席, I want the 连线房间与 UserSig 由后端安全签发, so that 密钥不会出现在浏览器或 APK 中。
3. As a 指挥中心座席, I want to在浏览器中看到设备上行的实时视频（约 720p）, so that 我能判断现场情况。
4. As a 指挥中心座席, I want to常开麦克风对设备喊话, so that 指挥指令能即时传达到现场。
5. As a 指挥中心座席, I want to主动结束连线, so that 通话可以干净收尾并释放房间。
6. As a 指挥中心座席, I want 设备在来电时自动进房而无需等待人工点接听, so that 不会因漏接延误调度。
7. As a 指挥中心座席, I want 呼叫超时或设备离线时得到明确失败反馈, so that 我知道需要改呼其他设备或稍后重试。
8. As a 指挥中心座席, I want 同一时刻对一台设备只保持一路指挥连线, so that 不会出现重复进房或状态混乱。
9. As an 执勤员, I want 平台来电时设备自动接听并有语音提示, so that 我单手作业时也能知道指挥已连上。
10. As an 执勤员, I want 指挥连线期间本机循环录像不中断, so that 现场取证文件完整可回放。
11. As an 执勤员, I want 连线画面与录像同源共摄而非第二路抢相机, so that 录像与推流不会互斥失败。
12. As an 执勤员, I want 连线中长按 PTT 才向平台送麦, so that 嘈杂环境下不会持续上行环境噪音。
13. As an 执勤员, I want 连线中短按 PTT 仍只切换白光灯, so that 既有按键习惯不被破坏。
14. As an 执勤员, I want 连线中松开 PTT 后停止向平台送麦, so that 半双工语义清晰。
15. As an 执勤员, I want 正在与 AI 助手全双工语音时若指挥来电则打断 AI 并自动接听, so that 调度优先于助手问答。
16. As an 执勤员, I want 指挥结束后 AI 会话不自动恢复, so that 不会在未知状态下继续占麦。
17. As an 执勤员, I want 平台下发结束连线后设备自动退房并清理指示灯/采音/推流状态, so that 不会遗留蓝灯或占麦。
18. As an 执勤员, I want 设备不上报应答、忙线或挂断控制消息, so that 交互保持「只听指挥、自动执行」的简单模型。
19. As an 执勤员, I want 息屏或 App 在前台时都能收到连线信令并自动进房, so that 现场不看屏幕也能被叫通（依赖既有侧键无障碍与网络通道）。
20. As a 系统运维, I want `/health` 或等价检查能反映 TRTC/UserSig 配置是否齐全, so that 缺 Workspace/Secret 时能快速定位。
21. As a 系统运维, I want SDKAppID 与 SecretKey 仅存在于后端环境变量, so that 泄露面可控且可轮换。
22. As a 后端服务, I want MQTT 下发呼叫开始与结束连线, so that 设备能在低延迟下收到控制。
23. As a 后端服务, I want HTTP 轮询/拉取作为连线信令兜底, so that MQTT 短暂不可用时仍可能送达呼叫。
24. As a 后端服务, I want 为平台用户与设备分别签发短时 UserSig 与房间号, so that 双方能进入同一连线房间。
25. As a 后端服务, I want 拒绝为未占用或不存在的设备创建连线, so that 不会呼叫空设备。
26. As a Web 控制台, I want 使用 TRTC Web SDK 进房拉流与推麦, so that 无需自建 WebRTC P2P。
27. As an Android 设备, I want 使用 TRTC Android SDK + 自定义视频源进房, so that 满足连线共摄。
28. As an Android 设备, I want 连线期间录像伴随音与连线对讲共麦策略不互相抢第二路 AudioRecord, so that 录像片中不静音。
29. As a 产品负责人, I want 首期严格 1 平台座席 + 1 设备, so that 复杂度可控且可预留旁听扩展。
30. As a 产品负责人, I want 首期不做腾讯云侧通话云端录制, so that 证据仍以本机循环录像为准。
31. As a 产品负责人, I want 设备主动呼叫指挥中心明确为二期, so that 首期范围不膨胀。
32. As a 开发者, I want 冻结/替代 stub 自建 WebRTC 真媒体路线, so that 不会维护两套专家连线实现。
33. As a 开发者, I want GB28181 国标通道保持独立, so that 监控接入不被 TRTC 替换。
34. As a QA, I want 能用假 TRTC/可注入房间适配器验收主路径, so that 不必依赖腾讯云内部实现细节。
35. As a QA, I want 验证弱网下仍尽量保持可听可看或有明确失败清理, so that 现场不会卡在半连接状态。
36. As a 指挥中心座席, I want 看到连线中/已结束等平台侧状态, so that 我知道当前是否在通话。
37. As an 执勤员, I want 来电语音提示简短且不阻塞进房, so that 提示不会拖死连线建立。
38. As a 安全审计, I want UserSig 具备过期时间且不可被客户端改房间越权, so that 不能伪造进入任意房间。

## Implementation Decisions

### Architecture

- 媒体：腾讯云 TRTC（ADR-0002）；信令：自建连线信令，不采用 TUICallKit 整包。
- 方向：首期仅平台 → 设备；设备主动呼叫二期。
- 房间：1v1；架构预留旁听，首期不实现。
- 接听：设备默认自动接听 + TTS/语音提示；无拒接 UI；无设备上行应答/忙线/挂断。
- 结束：平台经连线信令下发结束连线；设备执行退房与本地清理。
- 录像：连线共摄，本机 1080p30 不停；TRTC 旁路约 720p。
- 音频：连线对讲 = 设备 PTT 半双工，平台可常开麦；与 AI 全双工 PTT 互斥，指挥来电打断 AI。
- 录制：首期无 TRTC 云端录制。
- SecretKey / SDKAppID：仅后端；客户端只拿 UserSig、sdkAppId、roomId、userId。

### Modules (logical)

- **后端连线会话服务**：创建连线、签发 UserSig、校验设备占用、下发 MQTT 呼叫开始/结束、HTTP 兜底查询。
- **后端 UserSig 签发器**：封装腾讯云签名，短 TTL。
- **Android 指挥连线控制器**：收信令 → 打断 AI → 自动进房 → 绑定自定义视频/对讲 → 收结束 → 清理。
- **Android TRTC 房间适配器**：可替换实现，便于测试注入假房间。
- **自定义视频源**：从现有编码/预览旁路取帧缩放至约 720p 注入 TRTC，禁止二次 openCamera。
- **连线对讲与按键**：在「指挥连线中」时 F6 长按上行、短按白光；复用/扩展现有按键分发与共麦策略。
- **Web 控制台连线页**：选设备、发起/结束、进房播放远端视频与本地麦。
- **VideoStream / 推流协调**：将「专家 WebRTC stub」路径收敛为指挥连线（TRTC），避免双栈。

### API / signaling contracts (behavioral)

- 平台：`start_command_call(device_id)` → 返回 roomId、双方进房所需凭证摘要（平台侧 UserSig 等）。
- 平台：`end_command_call(call_id|room_id)` → 设备经 MQTT/HTTP 收到结束并退房。
- 设备：订阅呼叫开始 / 结束；**不**发布 answer / busy / hangup。
- MQTT 不可达时，设备可通过既有 HTTP 拉取机制获得待处理呼叫（兜底）。

### Interactions

```
平台发起 → 后端建连线房间并签 UserSig → MQTT 呼叫开始
  → 设备打断 AI、语音提示、自动进房、开始共摄推流与对讲待命
  → 平台进房看听、常开麦
  → 设备 PTT 半双工上行
  → 平台结束 → MQTT 结束 → 设备退房清理
```

## Testing Decisions

### What makes a good test

只断言外部可观察行为（是否进房、是否仍在录像、PTT 是否上行、结束后是否空闲），不断言 TRTC SDK 内部、不测厂商弱网算法、不做像素级画面比对。

### Seams (方案 A：主接缝 + 4 个支撑接缝)

**主接缝 — 指挥连线会话**

平台呼叫设备 → 设备自动进房 → 平台可见/可听 → 设备 PTT 对讲 → 平台结束连线 → 双方状态清理。

用可注入的房间适配器（假 TRTC）或契约级集成完成；不依赖腾讯云黑盒细节。

**支撑接缝**

1. **连线信令**：平台呼叫开始 / 结束的载荷与设备侧执行结果；无应答/忙线/挂断上行用例。
2. **UserSig 签发**：仅后端、短时有效、缺配置失败明确；密钥不出现在客户端配置。
3. **连线共摄**：连线中本机仍为录像中；不二次独占打开相机。
4. **连线对讲**：指挥连线中 F6 长按上行、短按白光；与 AI PTT 互斥且来电能打断 AI。

### Prior art

- MQTT / 设备指令类单测与策略测试风格（现有 recorder key / session policy 测试）。
- 媒体管线旁路与共麦测试（PCM tee / 录像策略）。
- 后端 FastAPI 契约测试（现有 `backend/tests`）。

## Out of Scope

- 设备主动呼叫 / SOS 求助发起指挥连线（二期）
- 设备拒接、忙线回执、设备挂断按钮与上行信令
- TUICallKit / 腾讯云整包通话 UI
- 自建 google-webrtc 真 SRTP 媒体通道（冻结，由本 PRD 替代）
- 多人会议、群组对讲
- 首期旁听观众（仅预留）
- TRTC 云端通话录制与回传
- 用 TRTC 替代 GB28181
- 改本机录像分辨率/封装格式
- 改变 AI 全双工助手协议本身（仅定义与指挥连线的优先级）

## Further Notes

- 对话或文档中曾出现的腾讯云 SecretKey 必须视为泄露并轮换；仅写入后端私密环境变量。
- 旧 PRD `v2-video-communication.md` 中「WebRTC 专家连线」目标由本 PRD 接管；其中 GB28181 部分仍有效，不在本 PRD 实施范围内重做。
- 发布 Issue 时标签：`ready-for-agent`（需 `gh auth login` 后创建）。
- 建议后续 `/to-issues` 拆分：UserSig+会话 API、连线信令、Android 进房与共摄、连线对讲按键、Web 控制台、替换 stub WebRTC、验收剧本。
