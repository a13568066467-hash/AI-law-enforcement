# 文档索引

```
docs/
├── architecture/     # 架构、总览、路线图
├── product/          # 产品需求、设计、交互
├── hardware/         # 硬件参数、接线、采购
├── protocol/         # BLE 等通信协议
└── guides/           # 各模块开发指南
```

## 架构

| 文档 | 说明 |
|------|------|
| [项目总览.md](architecture/项目总览.md) | 完整思路、数据流、开发顺序 |
| [总方案手册.md](architecture/总方案手册.md) | 模块备注与引导（推荐入口） |
| [完整AI功能路线.md](architecture/完整AI功能路线.md) | 端到端验收路线 |

## 产品

| 文档 | 说明 |
|------|------|
| [产品设计方案.md](product/产品设计方案.md) | 执法记录仪产品方案（初稿） |
| [产品需求.md](product/产品需求.md) | 需求与 MVP |
| [交互设计.md](product/交互设计.md) | 按键、LED、语音 |
| [外观形态.md](product/外观形态.md) | 胸牌结构与挂载 |

## 硬件

| 文档 | 说明 |
|------|------|
| [DSJ-ZECN6A1硬件参数.txt](hardware/DSJ-ZECN6A1硬件参数.txt) | 执法仪规格 |
| [XIAO硬件接线.md](hardware/XIAO硬件接线.md) | ESP32 接线 |
| [已购硬件.md](hardware/已购硬件.md) · [待购清单.md](hardware/待购清单.md) | 采购 |
| [电量监测.md](hardware/电量监测.md) · [边充边录.md](hardware/边充边录.md) · [待机省电.md](hardware/待机省电.md) | 电源 |

## 协议

| 文档 | 说明 |
|------|------|
| [BLE协议.md](protocol/BLE协议.md) | GATT、CMD、分片（联调必查） |

## 开发指南

| 文档 | 说明 |
|------|------|
| [固件开发指南.md](guides/固件开发指南.md) | ESP-IDF 编译烧录 |
| [App开发指南.md](guides/App开发指南.md) | Android App |
| [云端AI代理.md](guides/云端AI代理.md) | 后端 Agent A/B |

## 工程 README

- [firmware/README.md](../firmware/README.md)
- [android-app/README.md](../android-app/README.md)
- [backend/README.md](../backend/README.md)
