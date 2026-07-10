# 文档索引

```
docs/
├── architecture/     # 架构与路线
├── product/          # 产品需求与设计
├── hardware/         # DSJ-ZECN6A1 执法仪参数
└── guides/           # 开发指南
```

## 产品

| 文档 | 说明 |
|------|------|
| [产品需求.md](product/产品需求.md) | 赢筑AI MVP 与路线图（主文档） |
| [产品需求.md](product/产品需求.md) | 功能需求与 MVP |
| [交互设计.md](product/交互设计.md) | 按键、LED、语音 |

## 硬件

| 文档 | 说明 |
|------|------|
| [DSJ-ZECN6A1硬件参数.txt](hardware/DSJ-ZECN6A1硬件参数.txt) | 执法仪规格 |
| [存储与TF卡.md](hardware/存储与TF卡.md) | SD/Rom 卷路由、停录兜底、录像中删旧片 |
| [ZE69-驱动控制接口.txt](hardware/ZE69-驱动控制接口.txt) | sysfs 灯控 / 夜视 / 光感 |
| [系统签名与适配.md](hardware/系统签名与适配.md) | platform/release 签名与真机灯控 |
| [ZE69刷机与预装.md](hardware/ZE69刷机与预装.md) | 刷机包、预装、默认桌面四阶段 |

## 架构

| 文档 | 说明 |
|------|------|
| [项目总览.md](architecture/项目总览.md) | 数据流与开发顺序 |
| [总方案手册.md](architecture/总方案手册.md) | 模块索引 |
| [通信协议规范.md](architecture/通信协议规范.md) | MQTT / HTTP / 视频 / 音频 / 存储 / 硬件 |
| [完整AI功能路线.md](architecture/完整AI功能路线.md) | 端到端验收 |

## 开发指南

| 文档 | 说明 |
|------|------|
| [云端AI代理.md](guides/云端AI代理.md) | 后端 Agent A/B、Vision |

## 工程 README

- [android-app/README.md](../android-app/README.md)
- [backend/README.md](../backend/README.md)

## PRD 设计文档

| 文档 | 说明 |
|------|------|
| [MQTT 信令通道](prd/mqtt-signaling-channel.md) | 设备 MQTT 云端信令设计 |
| [V2 视频通信协议](prd/v2-video-communication.md) | GB28181 + WebRTC 双通道视频通信 |
