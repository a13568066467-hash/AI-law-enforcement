# AI Field Cam — Android 原生 App

> **工程目录：** `text1/android-app/`  
> **BLE 协议：** [`docs/protocol/BLE协议.md`](../docs/protocol/BLE协议.md)  
> **执法仪硬件：** DSJ-ZECN6A1 · [`docs/hardware/DSJ-ZECN6A1硬件参数.txt`](../docs/hardware/DSJ-ZECN6A1硬件参数.txt)

## 技术栈

| 项 | 选型 |
|----|------|
| 语言 | Kotlin |
| 最低 SDK | 26 (Android 8.0) |
| 目标 SDK | 35 |
| UI | Material 3 + ViewBinding |
| 构建 | Gradle 8.9 + AGP 8.7 |

## 项目结构

```
android-app/
├── app/src/main/kotlin/com/aifieldcam/app/
│   ├── MainActivity.kt
│   ├── ble/                     # BLE 连接、拼包、协议常量
│   ├── data/                    # SessionManager、ApiClient
│   ├── platform/                # DSJ-ZECN6A1 / ZE69 sysfs 灯控与夜视
│   └── ui/                      # 首页 / 对话 / 相册 / 录像 / 设置
└── README.md
```

## 打开与运行

### Android Studio（推荐）

1. **File → Open** → 选择 `android-app` 目录
2. Gradle Sync 完成后连接真机，点击 **Run**

### 命令行

```bat
cd android-app
gradlew.bat assembleDebug
```

## 已实现

- [x] BLE 扫描连接 `AI-FieldCam`、MTU 517
- [x] IMAGE_TX / VIDEO_TX 分片接收
- [x] SENSOR 电量与 FSM 状态
- [x] 云端登录、对话、相册识图
- [x] DSJ-ZECN6A1 设备配置与光感夜视（sysfs 需系统签名）
- [x] 手机相机兜底拍照/录像

## 待实现

- [ ] Camera2 本机 1080p H.264 硬编码录像
- [ ] AUDIO_TX / AUDIO_RX Opus 语音链路
- [ ] GB28181 / 4G 推流（执法仪平台阶段）
