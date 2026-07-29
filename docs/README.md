# 文档索引

```
docs/
├── adr/              # 架构决策记录
├── architecture/     # 架构与数据/协议
├── 架构流程图/        # Mermaid 架构 / MVC / 时序图
├── product/          # 产品需求与交互
├── hardware/         # DSJ-ZECN6A1 执法仪
├── prd/              # 特性 PRD
└── guides/           # 开发指南
```

## 产品

| 文档 | 说明 |
|------|------|
| [产品需求.md](product/产品需求.md) | 赢筑AI MVP 与路线图（主文档） |
| [交互设计.md](product/交互设计.md) | 按键、LED、语音 |

## 硬件

| 文档 | 说明 |
|------|------|
| [DSJ-ZECN6A1硬件参数.txt](hardware/DSJ-ZECN6A1硬件参数.txt) | 执法仪规格 |
| [执法仪接口文档.md](hardware/执法仪接口文档.md) | 厂商接口整理（LED 以专文为准） |
| [LED控制接口.md](hardware/LED控制接口.md) | 灯控 sysfs 真机接口 + 纠错 |
| [按键映射.md](hardware/按键映射.md) | 物理按键与状态灯 |
| [存储与TF卡.md](hardware/存储与TF卡.md) | SD/Rom 卷路由、停录兜底 |
| [ZE69-驱动控制接口.txt](hardware/ZE69-驱动控制接口.txt) | 厂商摘录（节点名过时） |
| [系统签名与适配.md](hardware/系统签名与适配.md) | platform/release 签名 |
| [ZE69刷机与预装.md](hardware/ZE69刷机与预装.md) | 刷机、预装、默认桌面 |

## 架构

| 文档 | 说明 |
|------|------|
| [项目总览.md](architecture/项目总览.md) | 数据流与目录结构 |
| [通信协议规范.md](architecture/通信协议规范.md) | MQTT / HTTP / 视频 / 音频 / 存储 |
| [数据库设计.md](architecture/数据库设计.md) | MySQL 表、权威源、扫码写库 |
| [架构流程图/](架构流程图/README.md) | 系统 / MVC / 业务域 / 扫码绑定 **PNG 架构图** |

## ADR

| 文档 | 说明 |
|------|------|
| [0001 专机软锁 + ROM](adr/0001-kiosk-soft-lock-plus-rom.md) | 专机锁定策略 |
| [0002 TRTC 指挥连线](adr/0002-trtc-for-command-calls.md) | 连线媒体选型 |
| [0003 画面监看会话](adr/0003-live-preview-session.md) | 监看与连线互斥 |
| [0004 占用侧持房](adr/0004-device-owned-command-room.md) | 设备占用建房，Web 进房接听 |

## 开发指南

| 文档 | 说明 |
|------|------|
| [API接口.md](guides/API接口.md) | 云端 HTTP 接口 |
| [云端AI代理.md](guides/云端AI代理.md) | Agent A/B、Vision |

## PRD

| 文档 | 说明 |
|------|------|
| [扫码绑定](prd/qr-scan-device-login.md) | 公司设备池 + 扫码占用 |
| [MQTT 信令](prd/mqtt-signaling-channel.md) | 设备 MQTT 云端信令 |
| [V2 视频通信](prd/v2-video-communication.md) | GB28181 + 推流规划 |
| [指挥连线 TRTC](prd/command-call-trtc.md) | 指挥连线 |
| [画面监看](prd/live-preview-watch.md) | 点选监看 |
| [占用侧持房](prd/device-owned-command-room.md) | 设备占用建房，Web 进房接听 |

## 工程 README

- [android-app/README.md](../android-app/README.md)
- [mobile-app/README.md](../mobile-app/README.md)
- [backend/README.md](../backend/README.md)
