# 文档索引

**权威顺序**：根目录 [`CONTEXT.md`](../CONTEXT.md)（领域词）→ [`决策记录/`](决策记录/)（ADR）→ [`架构流程图/`](架构流程图/README.md)（现行结构）→ 本目录在研/已交付 PRD → 硬件专文。

```
docs/
├── 决策记录/           # ADR（架构决策）
├── 架构流程图/         # Mermaid → PNG
├── 架构与数据/协议/    # 总览、通信    设计
├── 产品需求与交/       # 总产品 PRD、交互设计
├── DSJ-ZECN6A1 执法仪/ # 硬件与刷机
├── 特性 PRD/           # 特性规格（摘要或交付稿）
├── 开发指南/           # API、云端 AI
├── agents/             # Agent 作业说明
└── archive/            # 已归档旧稿 / 过程件（日常勿改）
```

## 产品

| 文档 | 说明 |
|------|------|
| [产品需求.md](产品需求与交/产品需求.md) | MVP 与路线图（部分章节可能滞后于 CONTEXT） |
| [交互设计.md](产品需求与交/交互设计.md) | 按键、LED、语音体验 |

## 硬件（DSJ-ZECN6A1）

| 文档 | 说明 |
|------|------|
| [执法仪接口文档.md](DSJ-ZECN6A1%20执法仪/执法仪接口文档.md) | 厂商接口整理（LED 以专文为准） |
| [LED控制接口.md](DSJ-ZECN6A1%20执法仪/LED控制接口.md) | 灯控 sysfs 真机接口 |
| [按键映射.md](DSJ-ZECN6A1%20执法仪/按键映射.md) | 物理按键与状态灯 |
| [存储与TF卡.md](DSJ-ZECN6A1%20执法仪/存储与TF卡.md) | SD/Rom 卷路由 |
| [系统签名与适配.md](DSJ-ZECN6A1%20执法仪/系统签名与适配.md) | platform/release 签名 |
| [ZE69刷机与预装.md](DSJ-ZECN6A1%20执法仪/ZE69刷机与预装.md) | 刷机与预装 |

## 架构

| 文档 | 说明 |
|------|------|
| [架构流程图/](架构流程图/README.md) | **现行**系统 / MVC / 业务域 / 扫码时序图 |
| [项目总览.md](架构与数据/协议/项目总览.md) | 数据流与目录（部分路线图需对照 CONTEXT） |
| [通信协议规范.md](架构与数据/协议/通信协议规范.md) | MQTT / HTTP / 媒体；TRTC 现行见文首说明 |
| [数据库设计.md](架构与数据/协议/数据库设计.md) | 表与权威源分层 |

## 决策记录（ADR）

| 文档 | 说明 |
|------|------|
| [0001 专机软锁 + ROM](决策记录/0001-kiosk-soft-lock-plus-rom.md) | 专机锁定 |
| [0002 TRTC 指挥连线](决策记录/0002-trtc-for-command-calls.md) | 连线媒体选型 |
| [0003 画面监看会话](决策记录/0003-live-preview-session.md) | 监看与连线互斥 |
| [0004 占用侧持房](决策记录/0004-device-owned-command-room.md) | 设备占用建房 |

## 开发指南

| 文档 | 说明 |
|------|------|
| [四端连接总览.md](开发指南/四端连接总览.md) | 设备 / 手机 / Web / 后端怎么连、用哪些接口 |
| [设备连接接口.md](开发指南/设备连接接口.md) | 入库 → 扫码占用 → MQTT 信令上线 |
| [API接口.md](开发指南/API接口.md) | 云端 HTTP（部分端点可能滞后；以代码/OpenAPI 为准） |
| [云端AI代理.md](开发指南/云端AI代理.md) | Agent A/B、Vision、Realtime |

## 特性 PRD

见 [特性 PRD/README.md](特性%20PRD/README.md)。

| 文档 | 状态 |
|------|------|
| [扫码绑定](特性%20PRD/qr-scan-device-login.md) | Shipped |
| [现场事件工单](特性%20PRD/field-event-ticket.md) | Shipped |
| [指挥连线摘要](特性%20PRD/command-call-trtc.md) | 摘要 → ADR |
| [MQTT+YUV 设计](superpowers/specs/2026-07-30-mqtt-ack-yuv-cocapture-design.md) | 信令 Ack + YUV 旁路（实现中） |
| [画面监看](特性%20PRD/live-preview-watch.md) | 摘要 → ADR-0003 |
| [占用侧持房](特性%20PRD/device-owned-command-room.md) | 摘要 → ADR-0004 |
| [MQTT 信令](特性%20PRD/mqtt-signaling-channel.md) | Historical / 部分落地 |

## Agent

| 文档 | 说明 |
|------|------|
| [issue-tracker.md](agents/issue-tracker.md) | GitHub Issues 用法 |
| [triage-labels.md](agents/triage-labels.md) | 分流标签 |
| [domain.md](agents/domain.md) | 领域文档布局 |

## 归档

过程件与已否决规划见 [`archive/README.md`](archive/README.md)。

## 工程 README

- [android-app/README.md](../android-app/README.md)
- [mobile-app/README.md](../mobile-app/README.md)
- [backend/README.md](../backend/README.md)
