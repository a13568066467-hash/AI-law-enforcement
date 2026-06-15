# AI Field Cam — AI 实地相机

> **项目现场 AI 胸牌相机**，胸挂佩戴：**拍照识图 · AI 语音助手 · 录像留痕**（手机网关 + 云端 AI，非机身 4G 执法仪）。

## 三大功能

| 功能 | 说明 | 触发 |
|------|------|------|
| **录像** | 720p，App 相册回放 | 语音「开始/停止录像」 |
| **拍照** | 存相册，可 AI 解释 | 单击快门 |
| **AI 助手** | 对话、控设备、识图追问 | 双击 AI 键；「识别一下」 |

## 从这里开始

| 目的 | 文档 / 目录 |
|------|-------------|
| **总方案手册（推荐）** | [`docs/总方案手册.md`](docs/总方案手册.md) — 模块备注与引导思路 |
| **完整思路** | [`docs/项目总览.md`](docs/项目总览.md) |
| 产品需求 | [`docs/产品需求.md`](docs/产品需求.md) |
| 待购清单 | [`docs/待购清单.md`](docs/待购清单.md) |
| 硬件接线 / 已购 | [`docs/XIAO硬件接线.md`](docs/XIAO硬件接线.md) · [`docs/已购硬件.md`](docs/已购硬件.md) |
| 固件编译烧录 | [`docs/固件开发指南.md`](docs/固件开发指南.md) · [`firmware/`](firmware/) |
| BLE 协议 | [`docs/BLE协议.md`](docs/BLE协议.md) |
| 无硬件体验 | [`demo-web/index.html`](demo-web/index.html) |
| **完整 AI 路线** | [`docs/完整AI功能路线.md`](docs/完整AI功能路线.md) — 端到端验收 |
| **App + 云端分工** | [`docs/App开发指南.md`](docs/App开发指南.md) — 三层定位、开发阶段 |
| **云端后端** | [`backend/README.md`](backend/README.md) |
| **手机 App（HBuilder）** | [`apptext/`](apptext/) |
| 待机续航 | [`docs/待机省电.md`](docs/待机省电.md) |

## 项目结构

```
text1/
├── README.md
├── docs/                 # 方案、硬件、协议文档
├── backend/              # 云端 API（Agent A/B + Vision）
├── firmware/             # XIAO ESP32S3 固件（ESP-IDF）
├── apptext/              # 手机 App（HBuilderX 打开此目录）
├── demo-web/             # 无硬件交互 Demo
├── assets/               # 产品示意图等
└── tools/
    └── fsm_host_test.py  # FSM 逻辑回归（无需板子）
```

## 固件快速编译

```bat
cd firmware
idf.py set-target esp32s3
idf.py build flash monitor
```

## 验收要点

- [ ] 语音「开始录像」3s 内开录
- [ ] 720p 连续录 5 min 不崩
- [ ] 单击快门后识图播报
- [ ] 双击 AI 键 1s 内「我在」
- [ ] 电量 ≤10% 喇叭提醒（默认每 120s）
