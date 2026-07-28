# PRD: 现场事件工单 — 真转写成单

> 状态: Draft | 日期: 2026-07-28  
> 领域词: [`CONTEXT.md`](../../CONTEXT.md)（**现场事件工单**、**重点标记**、**指挥连线**、**画面监看**）  
> 父能力: [`field-event-ticket.md`](field-event-ticket.md)（工单 CRUD / Web / 短按重点标记已落地；本 PRD 补齐「口述 → 真正文」闭环）  
> 相关: 既有 RealtimeVoice（AI 助手按住说话）

## Problem Statement

执勤员按住 SOS 口述现场事件后，设备只上传空转写或未转写的音频，云端又未挂接转写/整理，导致**建不成有效现场事件工单**，或只能依赖测试替身转录。指挥侧仍拿不到可处理的真实正文（例如落石需调机械这类求助）。

## Solution

真机主路径改为：SOS 按住期间复用既有实时语音通道**仅做用户转写**；松手等待最终转写后，只把非空 `transcript` 交给既有创建接口；云端对正文做轻量 AI 整理（失败则回退原转写）再落库。与 AI 助手按住说话互斥。不新开对外 HTTP 形状，不强制上传/存档 PCM。

## User Stories

1. As an 执勤员, I want 按住 SOS 说话时系统做语音转写而不触发助手对话回答, so that 我的口述只用于上报而不是闲聊。
2. As an 执勤员, I want 松手后等待最终转写完成再提交, so that 工单正文不是半截句子。
3. As an 执勤员, I want 最终转写超时仍为空时听到语音提示且不建单, so that 我知道要重试且不污染队列。
4. As an 执勤员, I want 有效转写提交后云端整理成通顺正文, so that Web 端读起来清晰。
5. As an 执勤员, I want 云端整理失败时仍用原转写建成工单, so that 网络/模型抖动不阻断上报。
6. As an 执勤员, I want 上报成功后听到「工单已上报」并可继续执勤, so that 流程不打断作业。
7. As an 执勤员, I want 创建请求只传转写文本不传原始音频, so that 省流量且符合首期不存音频。
8. As an 执勤员, I want 未占用绑定时仍无法开始 SOS 上报并听到先扫码提示, so that 工单总能关联执勤人员。
9. As an 执勤员, I want 指挥连线中仍无法 SOS 上报并听到连线中无法上报, so that 连线对讲优先。
10. As an 执勤员, I want AI 助手按住说话进行中时 SOS 被拒绝并听到说明, so that 两条语音通道不互相抢占。
11. As an 执勤员, I want SOS 上报进行中时 AI 助手按住说话被拒绝并听到说明, so that 互斥是双向的。
12. As an 执勤员, I want 画面监看中仍可 SOS 上报, so that 只看现场不影响求助。
13. As an 执勤员, I want 正在录像时仍可 SOS 上报且不中断录像, so that 取证与上报可并行。
14. As an 执勤员, I want SOS 短按仍只做重点标记, so that 与工单路径不混淆。
15. As an 执勤员, I want 上报失败后可以再次按住 SOS 重试, so that 弱网或口误可以补报。
16. As an 执勤员, I want 系统不本地排队、不离线补传, so that 失败行为简单可预期。
17. As a Web 管理端用户, I want 看到由真实口述生成的工单正文（含设备/人员/时间）, so that 我能调度（例如调机械清障）。
18. As a Web 管理端用户, I want 新单仍为本公司隔离且新单置顶, so that 与既有工单台一致。
19. As a 后端服务, I want 接收非空 transcript 并轻量 AI 整理后写入 body, so that 正文权威在云端生成。
20. As a 后端服务, I want 整理器异常时回退为原转写仍建单, so that 可用性优先于润色。
21. As a 后端服务, I want 拒绝空/空白 transcript 且不落库, so that 不产生空单。
22. As a 后端服务, I want 创建时仍从占用会话解析设备、人员、公司与时间, so that 审计字段完整。
23. As an Android 设备, I want SOS 路径复用既有实时语音转写能力且不发起助手回复, so that 少引入新 ASR 供应商。
24. As an Android 设备, I want 真机创建调用既有 POST 现场事件工单接口且只带 transcript, so that 不改变对外契约形状。
25. As a QA, I want 在 HTTP 主缝用替身整理器验收整理成功与回退建单, so that 不依赖真实 LLM。
26. As a QA, I want 在门禁策略单测中验收与 AI 助手互斥, so that 不依赖真机抢麦。
27. As a 产品负责人, I want 明确本能力仍是现场事件工单而非报障或 SOS 演示告警, so that 领域边界清晰。

## Implementation Decisions

### Architecture

- 不新开对外创建 URL；沿用既有现场事件工单创建接口。
- **转写在设备侧**（复用 AI 助手所用的实时语音通道，模式为「只转写、不回答」）。
- **正文整理在云端**（轻量润色：通顺、去赘词、保留事实）；失败回退原转写。
- 真机主路径 **只提交 transcript**；`audio_pcm_base64` 可保留兼容/测试，不作为真机必传。
- SOS 与 **AI 助手按住说话**双向互斥；指挥连线门禁保持。
- 松手后等待最终转写（短超时）；超时仍空 → 语音提示、不请求创建。

### Modules (logical)

- **现场事件工单服务**：挂接正文整理器；创建以 transcript 为主路径；整理失败回退。
- **SOS 上报控制器（Android）**：按住进入只转写会话；松手等最终转写；提交 transcript；提示语不变（整理中 / 已上报 / 失败原因）。
- **SOS 门禁策略**：在既有未绑定、指挥连线拒绝之上，增加「AI 助手占用中」拒绝原因；对称地，AI 助手启动时若 SOS 上报中亦拒绝。

### API contracts (behavioral)

- `POST` 创建：占用会话凭证；`transcript` 非空 → 200 + 工单（含整理后或回退的 `body`）；空白 → 4xx 不落库。
- 不要求真机传 PCM；不要求本 PRD 改变列表/详情/状态/从工单开连线契约。

### Interactions

```
按住 SOS（已绑定 ∧ 非指挥连线 ∧ 非 AI 助手占用）
  → 实时语音：只转写用户口述
松手
  → 提示整理中 → 等待最终转写（短超时）
  → 非空 transcript → POST 创建（仅 transcript）
  → 云端轻量整理（失败则原转写）→ 落库 pending
  → 成功「工单已上报」/ 失败语音提示且不落库
```

### Device gating

| 条件 | 行为 |
|------|------|
| 未占用绑定 | 不开始转写，提示先扫码 |
| 指挥连线中 | 不开始转写，提示连线中无法上报 |
| AI 助手按住说话中 | 不开始转写，提示助手使用中稍后再报 |
| SOS 上报进行中 | AI 助手按住被拒，提示事件上报中 |
| 画面监看中 | 允许 |
| 正在录像 | 允许，不中断录像 |

## Testing Decisions

只测外部可观察行为，不断言具体 ASR/LLM 供应商、Realtime 帧细节或 UI 像素。

**主接缝 — 现场事件工单 HTTP 创建**

- 非空 transcript + 替身整理器 → 创建成功，`body` 为整理结果，含设备/人员/时间/公司。
- 整理器抛错/返回空 → 仍建单，`body` 为原转写。
- 空/空白 transcript → 拒绝，库中无行。
- 真机主路径不把 PCM 作为本 PRD 必测项。

先验：既有 `field_event_ticket` 后端测试风格。

**辅接缝 — SOS 上报门禁策略**

- 未绑定 / 指挥连线中 → 拒绝（回归）。
- AI 助手占用中 → 拒绝（新增）。
- 监看中 / 录像中语义保持允许（策略层不因本 PRD 收紧）。

先验：既有 `FieldEventSosPolicy` 单测风格。

## Out of Scope

- 重新设计对外 HTTP 或 Web 工单台 UI
- 云端批处理 ASR 作为真机主路径
- 工单原始音频存档、本地排队、离线补传
- 标题/类型/紧急等级等结构化字段
- 首页「事件上报」改版
- 报障工单、SOS 演示场景复活为长按主路径
- 从工单发起指挥连线（父 PRD 已覆盖则不重复做）

## Further Notes

- 测试接缝已与产品确认（2026-07-28）：主缝 = 创建 HTTP；辅缝 = 门禁策略（含 AI 助手互斥）。
- 父 PRD 中「后端接收语音并转写」的表述，以本 PRD 为准修正为：**端侧转写 + 云端整理**。
- 发布 Issue 时标签：`ready-for-agent`。
