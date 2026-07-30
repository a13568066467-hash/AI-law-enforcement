# PRD: 占用侧持房（设备建房，Web 进房接听）

> 状态: Draft | 日期: 2026-07-24  
> 领域词: [`CONTEXT.md`](../../CONTEXT.md)（**连线房间**、**房间就绪**、**画面监看**、**指挥连线**、**连线信令**）  
> ADR: [`docs/决策记录/0004-device-owned-command-room.md`](../决策记录/0004-device-owned-command-room.md)（修订 0002/0003 中平台发起进房）  
> 相关: [`live-preview-watch.md`](live-preview-watch.md)、[`command-call-trtc.md`](command-call-trtc.md)

## Problem Statement

指挥中心点选监看时，平台先建房、Web 先入房，再等设备经 MQTT/HTTP 收到开始信令后进房推流。设备漏信令或进房慢时，会话长期停在 `watch/connecting`，Web 已「已进房 · 只听」但画面全黑。座席无法可靠「点选即看」；执勤侧有网占用也不预先占房。

## Solution

改为**占用侧持房**：设备在**已占用**期间向后端要房并进房占坑（解绑作废）。无人看时可不推或近似不推；Web 点选开启**画面监看**业务会话并加入**同一连线房间**只拉视频（「接听」= 挂上已有房）。有人进房后设备经 TRTC 远端事件为主、本系统信令兜底开始共摄推流。升级/冷启动**指挥连线**仍同房升麦。监看与连线仍为独立业务会话（互斥、心跳、审计）。建房失败不否定占用；以**房间就绪**、心跳与无流超时防止业务僵尸房。首期仍一路 Web；不做多路旁听或常驻监控墙。

## User Stories

1. As an 执勤员, I want 扫码占用成功后设备自动持有连线房间, so that 指挥侧随时可挂上看/连。
2. As an 执勤员, I want 解绑后房间作废且设备退房, so that 不会留下可被误连的房。
3. As an 执勤员, I want 无人监看时设备不持续推视频, so that 省电省流量。
4. As an 执勤员, I want Web 开始监看时设备再共摄推流, so that 点选即有画面。
5. As an 执勤员, I want 监看结束或 Web 离开后停止推流但尽量留房, so that 下次点选更快。
6. As an 执勤员, I want 建房/进房失败时占用仍有效, so that 本机执勤、录像与现场事件工单不受阻。
7. As an 执勤员, I want 画面监看仍不改状态灯、不打断 AI、无 TTS, so that 只被看不改变机身体感。
8. As an 执勤员, I want 指挥连线（含升级与从工单发起）仍红灯常亮、打断 AI、无 TTS, so that 喊话优先级不变。
9. As an 执勤员, I want 短断网重连后回到同一 roomId, so that Web 不因换房黑屏。
10. As a 指挥中心座席, I want 点选房间就绪的已占用在线设备即进房看到画面, so that 不再卡在设备未进房的 connecting。
11. As a 指挥中心座席, I want 房间未就绪时看到明确提示且不能开监看/连线, so that 不会空进 TRTC。
12. As a 指挥中心座席, I want 监看默认不开麦, so that 「只看」与「喊话」清晰。
13. As a 指挥中心座席, I want 监看中可升级为指挥连线且同房不断流, so that 看完可喊话。
14. As a 指挥中心座席, I want 不经监看也能直接发起连线（进同一占用房间并升连线态）, so that 紧急调度仍可用。
15. As a 指挥中心座席, I want 同一设备同时仅一路监看或连线, so that 第二座席被拒绝。
16. As a 指挥中心座席, I want 停止监看/换设备/关页结束监看会话, so that 设备停推且会话清理。
17. As a 指挥中心座席, I want 进房后长时间无远端视频时看到失败提示, so that 能重试或改选设备。
18. As a 后端服务, I want 占用成立时签发连线房间与设备 UserSig, so that 设备可进房占坑。
19. As a 后端服务, I want 设备上报房间就绪, so that Web 门禁有权威状态。
20. As a 后端服务, I want 解绑时作废占用房间, so that 防业务僵尸房。
21. As a 后端服务, I want start_watch / start_command_call 复用占用房间而非新建进房房, so that Web 与设备同房。
22. As a 后端服务, I want 监看/连线业务会话仍互斥、可升级、可心跳超时结束, so that 审计与清理不变。
23. As a 后端服务, I want 连线信令承担推流启停兜底与升麦/结束会话, so that 不依赖「首次靠呼叫才进房」。
24. As a 后端服务, I want 拒绝未占用、离线、房间未就绪或已忙设备上的新监看/连线, so that 边界清晰。
25. As a Web 控制台, I want 设备列表或选中态展示房间就绪与否, so that 座席知道能否点选。
26. As a Web 控制台, I want 点选后用占用房间凭证进房只拉视频, so that 符合进房接听。
27. As an Android 设备, I want 占用后拉取房间凭证并进房且默认不推流, so that 占坑待命。
28. As an Android 设备, I want 感知 Web 进房（TRTC 为主）或收到推流信令（兜底）后开始共摄推视频, so that 双通道可靠。
29. As an Android 设备, I want 监看结束/远端离开后停推并留房, so that 符合占用持房。
30. As an Android 设备, I want 收到升麦/连线态后开对讲策略并打断 AI, so that 指挥连线行为完整。
31. As a 产品负责人, I want 首期不做多路旁听与常驻监控墙, so that 不侵占 GB28181。
32. As a 产品负责人, I want 明确本能力不是设备振铃呼叫座席, so that 「接听」仅指 Web 挂上已有房。
33. As a QA, I want 用假 TRTC 配置与假 MQTT 验收占用建房→就绪→监看→升级→结束主路径, so that 不依赖腾讯云黑盒。
34. As a 系统运维, I want SecretKey 仍仅存后端, so that 设备侧只有 UserSig。

## Implementation Decisions

### Architecture

- 遵循 ADR-0004：连线房间绑定**设备占用**；Web 业务会话挂上已有房。
- UserSig / sdk_app_id 仍仅后端签发（ADR-0002）。
- 画面监看与指挥连线仍为独立业务会话；不再承担「首次拉设备进房」的主职责。
- 推流启停：TRTC 远端用户进/退房为主，本系统信令兜底。

### Modules (logical)

- **占用房间服务（后端）**：占用时 ensure 房间、设备就绪上报、解绑释放、查询就绪态；一占用一 roomId；短断网不换房。
- **监看/连线会话服务（后端）**：start/end/upgrade/心跳/互斥；平台凭证针对**已有** room_id；信令含推流启停兜底与升麦/结束。
- **Android 占用持房与按需推流**：占用后进房占坑；按 TRTC/信令启停共摄推流；连线态才对讲/红灯/打断 AI。
- **Web 指挥台**：房间未就绪门禁；点选进已有房只看；升级同房升麦。

### API / signaling contracts (behavioral)

- 占用成功后（或设备恢复占用）：ensure 占用房间 → 设备可拉取设备侧进房凭证 → 进房成功后标记**房间就绪**。
- 解绑 / 占用结束：作废房间；设备退房；进行中的监看/连线会话结束。
- `start_watch(device_id)`：要求已占用、在线、**房间就绪**、无活跃会话 → 建监看会话 + 平台进房凭证（同 room_id）→ 下发监看开始（语义：开始推流/有人在看，非首次建房）。
- `end_watch` / 心跳超时：结束监看会话 → 通知停推；设备留房。
- `upgrade` / 冷启动 `start_command_call`：同占用房间升连线态或直接连线态进房。
- 设备 poll/MQTT：可消费占用进房凭证（若尚未持有）、推流启停兜底、升麦、会话结束；不上行应答/忙线/挂断。
- 建房/进房失败：占用仍有效；就绪为 false。

### Interactions

```
扫码占用成功
  → 后端签发连线房间 + 设备 UserSig
  → 设备进房占坑（不推或近似不推）→ 上报房间就绪
Web 点选（房间就绪）
  → 建监看会话 + 平台凭证（同 room）
  → Web 进房只拉；信令/TRTC → 设备开始推流
  →（可选）升级连线：同房升麦、红灯、打断 AI
  → 停止/换设备/关页 → 结束会话、设备停推留房
解绑 → 作废房间、设备退房、清理会话
```

### Anti-zombie（占用一房）

- 解绑作废房与会话。
- 房间就绪依赖设备上报/心跳；超时未就绪 → Web 不可监看。
- Web 进房后无远端流超时 → 会话失败提示（不轻易换 roomId）。
- 不采用定时换房。

## Testing Decisions

只测外部可观察行为；假 TRTC 配置与假 MQTT；不断言 SDK 内部或像素。

**主接缝 — 占用房间 + 监看/连线会话（后端）**

- 占用 ensure → 设备凭证可拉 → 标记就绪。
- 未就绪时 start_watch / start_command_call 拒绝。
- 就绪后 start_watch 复用同一 room_id；poll 得监看开始；end 后设备可再 poll 到结束且房间仍在（直到解绑）。
- 解绑释放房间；再监看须重新占用/ensure。
- 升级同房；第二路互斥；心跳结束监看。
- 先验：`backend/tests/test_live_preview_watch.py`、`test_command_call_skeleton.py`。

**辅接缝 — 设备推流门闸策略**

- 仅占用进房不推；远端进房或信令推流开始 → 允许推；结束/远端离开 → 停推留房。
- 先验：`CommandCallController` / IntercomPolicy 单测风格。

**辅接缝 — Web 门禁（若有契约测）**

- 未就绪不可开监看；就绪后点选走 start_watch 并进房只拉。

## Out of Scope

- 多路旁听监看、常驻监控墙 / 大屏
- 设备振铃式呼叫座席、设备上行应答/忙线/挂断
- 定时换房
- 建房失败则占用失败
- 监看改灯或打断 AI
- 改 GB28181 / 复活 JPEG `/frame`
- 现场事件工单正文流程变更（仅连线改为挂占用房）

## Further Notes

- 测试缝：主缝 = 后端占用房间 + 会话；辅缝 = 设备推流门闸。
- 实现建议垂直切片：① 占用 ensure/就绪/释放 + 改写 start_watch 挂房；② 设备占用进房与按需推流；③ Web 就绪门禁与进房；④ 升级/冷启动连线与防僵尸收口。
- 发布 Issue 时标签：`ready-for-agent`。
- 旧 PRD `live-preview-watch.md` / `command-call-trtc.md` 中「平台建房拉设备进房」表述以本 PRD 与 ADR-0004 为准。
