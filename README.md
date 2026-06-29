# AI Field Cam — AI 实地相机

> **项目现场 AI 胸牌相机**：拍照识图 · AI 语音助手 · 录像留痕（执法仪/手机网关 + 云端 AI + BLE 外接相机）。

## 快速导航

| 目的 | 入口 |
|------|------|
| **文档索引** | [`docs/README.md`](docs/README.md) |
| **总方案** | [`docs/architecture/总方案手册.md`](docs/architecture/总方案手册.md) |
| **Android App** | [`android-app/`](android-app/) |
| **云端后端** | [`backend/README.md`](backend/README.md) |
| **ESP32 固件** | [`firmware/README.md`](firmware/README.md) |
| **BLE 协议** | [`docs/protocol/BLE协议.md`](docs/protocol/BLE协议.md) |

## 标准项目结构

```
text1/
├── README.md                 # 本文件
├── docs/                     # 全部文档（按主题分子目录）
│   ├── README.md
│   ├── architecture/         # 架构、总览、路线图
│   ├── product/              # 产品需求与设计
│   ├── hardware/             # 硬件参数、接线、采购
│   ├── protocol/             # BLE 等协议
│   └── guides/               # 开发指南
├── backend/                  # 云端 API（FastAPI）
│   └── app/                  # Python 应用包
├── firmware/                 # XIAO ESP32S3 固件（ESP-IDF）
├── android-app/              # Android 原生 App（Kotlin）
└── tools/                    # 主机侧测试脚本
    └── tests/
```

## 常用命令

```bat
REM 固件
cd firmware
idf.py set-target esp32s3
idf.py build flash monitor

REM 后端
cd backend
venv\Scripts\activate
uvicorn app.main:app --host 0.0.0.0 --port 8000

REM Android
cd android-app
gradlew.bat assembleDebug

REM 逻辑回归
python tools/tests/fsm_host_test.py
```

## 验收要点

- [ ] BLE 连接 `AI-FieldCam` 并拍照收图
- [ ] 录像停止后 VIDEO_TX 文件可回放
- [ ] 云端登录后 AI 对话与识图
- [ ] DSJ-ZECN6A1 光感夜视 / 录像灯（需系统签名）
