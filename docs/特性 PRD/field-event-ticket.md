# PRD: 现场事件工单（SOS 按住上报）

> **状态: Shipped** | 日期: 2026-07-23（实现后对齐）  
> 领域词: [`CONTEXT.md`](../../CONTEXT.md)（**现场事件工单**、**重点标记**）  
> 「真转写落库」细则已归档：[`archive/特性 PRD/field-event-ticket-real-upload.md`](../archive/特性%20PRD/field-event-ticket-real-upload.md)  
> 竖切 issues 归档：[`archive/superpowers/2026-07-23-field-event-ticket.md`](../archive/superpowers/2026-07-23-field-event-ticket.md)

## Problem Statement

执勤员在现场需要把事件或求助快速报给本公司 Web 管理端，但当前 SOS 长按跑的是应急**演示**（还可能连带开录），首页「事件上报」同样是演示入口。指挥侧拿不到可处理的正文工单；执勤员也无法用「按住说话」自然完成上报后继续执勤。

## Solution

将 SOS **长按/按住**改为提交**现场事件工单**：按住说话 → 松手转写 → AI 整理正文 → 上传到 Web（附带设备、执勤人员、时间）。短按仍为本地**重点标记**。首页「事件上报」暂不改。Web 按本公司隔离查看列表/详情、手改状态，并可从工单**可选**发起既有**指挥连线**。不自动开录、不本地排队；失败语音提示且不建空单。

## User Stories

1. As an 执勤员, I want to按住 SOS 键说话, so that 我可以单手口述现场事件或求助。
2. As an 执勤员, I want to松手后自动转写并由 AI 整理成工单正文, so that 我不必打字。
3. As an 执勤员, I want 工单自动带上设备、执勤人员与时间, so that 管理端能定位是谁在哪台设备上报。
4. As an 执勤员, I want 上报成功后收到明确提示并继续执勤, so that 流程不打断作业。
5. As an 执勤员, I want 无有效语音或上报失败时听到语音提示且不产生空工单, so that 我知道要重试且不会污染队列。
6. As an 执勤员, I want 失败后可以再次按住 SOS 重试, so that 弱网或口误可以补报。
7. As an 执勤员, I want 上报失败时系统不本地排队、不离线补传, so that 行为简单可预期。
8. As an 执勤员, I want SOS 短按仍做重点标记, so that 本地证据片标记能力保留。
9. As an 执勤员, I want 未占用绑定时按住 SOS 不收音并提示先扫码绑定, so that 工单始终能关联执勤人员。
10. As an 执勤员, I want 指挥连线中按住 SOS 不抢麦并提示连线中无法上报, so that 连线对讲优先且语义不混。
11. As an 执勤员, I want 画面监看中仍可按住 SOS 上报工单, so that 只看现场不影响求助上报。
12. As an 执勤员, I want 正在录像时仍可上报且麦走共麦、不中断录像, so that 取证与上报可并行。
13. As an 执勤员, I want 上报过程不因演示场景自动开始录像, so that SOS 不再误触发开录。
14. As an 执勤员, I want 首页「事件上报」入口首期保持不动, so that 范围不膨胀到改首页交互。
15. As a Web 管理端用户, I want to看到本公司新上报的现场事件工单列表（新单置顶）, so that 我能及时处理。
16. As a Web 管理端用户, I want to打开工单详情阅读 AI 整理正文及设备/人员/时间, so that 我能理解现场诉求。
17. As a Web 管理端用户, I want to将工单状态在待处理 / 处理中 / 已关闭间手改, so that 我能跟踪处理进度。
18. As a Web 管理端用户, I want to从工单详情可选发起对该设备的指挥连线, so that 需要喊话时复用现有连线能力。
19. As a Web 管理端用户, I want 只能看到本公司边界内的工单, so that 与公司设备池隔离一致。
20. As a Web 管理端用户, I want 首期不做复杂分派、流转与统计, so that 交付范围可控。
21. As a 后端服务, I want 接收设备松手后的语音并完成转写与 AI 整理再落库, so that 工单正文在云端权威生成。
22. As a 后端服务, I want 拒绝空转录/无效语音且不落库, so that 不产生空单。
23. As a 后端服务, I want 工单创建时写入设备、执勤人员、时间与公司, so that 列表过滤与审计成立。
24. As a 后端服务, I want 列表与详情按公司隔离, so that 跨公司不可见。
25. As a 后端服务, I want 从工单发起指挥连线时调用既有平台发起连线能力, so that 不另造进房模型。
26. As an Android 设备, I want SOS 按住≥约 500ms 开始收音、松手提交, so that 与短按重点标记可区分。
27. As an Android 设备, I want 用既有共麦采集策略在录像中采集口述音, so that 不打断录像伴随音。
28. As an Android 设备, I want 长按路径不再调用 sos_emergency 演示及 start-record 类设备指令, so that 行为与现场事件工单一致。
29. As a QA, I want 在 HTTP 主缝上验收创建/拒空/公司隔离/状态变更/从工单开连线, so that 不依赖真机与真实 ASR 黑盒即可回归核心契约。
30. As a 产品负责人, I want 明确本能力不是报障工单、不是设备主动进房呼叫, so that 领域边界清晰。

## Implementation Decisions

### Architecture

- 领域对象为**现场事件工单**（见 `CONTEXT.md`）；与**报障**工单分离。
- SOS **短按** = **重点标记**；**按住说话、松手提交** = 现场事件工单。取代长按 `sos_emergency` 演示及因此触发的开录。
- 进房仍仅平台发起**指挥连线**；工单详情上的连线是可选动作，不是上报的必达结果。
- 首页「事件上报」卡片首期不改行为。

### Modules (logical)

- **现场事件工单服务（后端）**：创建（语音→转写→AI 正文→落库）、按公司列表/详情、状态更新；创建时解析占用会话得到设备/人员/公司/时间。
- **SOS 上报门禁与按住收音（Android）**：门禁（已绑定、非指挥连线中；监看/录像允许）；按住收音、松手上传；成功/失败提示（失败须语音）。
- **Web 工单台**：本公司列表、详情、三态手改、可选「发起指挥连线」（复用现有 start command call）。

### API contracts (behavioral)

- 设备（需占用会话凭证）`POST` 创建工单：上传口述音频（或等价载荷）；成功返回工单 id 与正文；空/无效语音 → 4xx，不落库。
- Web（本公司作用域）`GET` 列表（默认新单在前）、`GET` 详情、`PATCH` 状态 ∈ {待处理, 处理中, 已关闭}。
- Web「发起指挥连线」：对工单关联 `device_id` 调用既有指挥连线开始；设备忙/离线时返回既有错误语义。
- 不要求 MQTT SOS `/thing/event/sos/post` 作为本能力主路径；旧演示/纯告警路径从 SOS 长按移除。

### Interactions

```
按住 SOS（已绑定且非指挥连线）
  → 共麦收音（录像不中断）
松手
  → 提示整理中 → 上传语音
  → 后端转写 + AI 整理正文
  → 落库（设备/人员/时间/公司，状态=待处理）
  → 成功提示 / 失败语音提示且不落库
Web：本公司列表 → 详情 → 改状态 →（可选）发起指挥连线
```

### Device gating

| 条件 | 行为 |
|------|------|
| 未占用绑定 | 不收音，提示先扫码 |
| 指挥连线中 | 不收音，提示连线中无法上报 |
| 画面监看中 | 允许上报 |
| 正在录像 | 允许上报，共麦，不中断录像 |

## Testing Decisions

只测外部可观察行为，不断言具体 ASR/LLM 供应商实现细节或 UI 像素。

**主接缝 — 现场事件工单 HTTP API**

- 有效语音（或测试替身转录）→ 创建成功，正文非空，含设备/人员/时间/公司。
- 空/无效输入 → 拒绝且库中无行。
- 列表/详情公司隔离；他公司不可见。
- 状态待处理→处理中→已关闭可更新。
- 从工单发起指挥连线走到既有连线开始（可用假会话/桩）。

先验：`backend/tests` 中 device bind、command-call、live-preview 的契约/验收风格。

**辅接缝 — SOS 上报门禁策略**

- 未绑定 / 指挥连线中 → 不允许开始收音。
- 监看中 / 录像中 → 允许。

先验：Android 侧 `CommandCallIntercomPolicy` 等策略单测风格。

## Out of Scope

- 首页「事件上报」改版或对齐物理键
- 本地排队、离线补传、草稿箱
- 工单附带原始音频存档（首期可只传处理、不强制长期存音频）
- 工单强制标题、类型枚举、紧急等级、复杂分派/统计
- 跨公司总览 / 超管
- 设备主动进房呼叫、申请振铃式连线
- 报障工单流程
- 复活 `sos_emergency` 演示作为长按主路径
- 改重点标记的云端同步或防删语义

## Further Notes

- 测试缝已与产品确认：主缝 = 现场事件工单 HTTP API；辅缝 = SOS 上报门禁。
- 实现时可拆独立 issue（建议后续 `/to-issues`）：后端 API+库表、Android 按住上报、Web 工单台、去掉长按演示开录、从工单开连线接线。
- 发布 Issue 时标签：`ready-for-agent`（需 `gh auth login`）。
- 替换旧文档中「SOS 长按 = 应急演示 / 纯 MQTT sos」的表述时，以本 PRD 与 `CONTEXT.md` 为准。
