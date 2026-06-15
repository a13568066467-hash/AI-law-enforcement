# AI Field Cam — Android 原生 App

> **工程目录：** `text1/android-app/`  
> **与 uni-app 版：** `text1/apptext/` 功能对齐，BLE 协议见 [`docs/BLE协议.md`](../docs/BLE协议.md)

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
│   ├── MainActivity.kt          # 底部导航容器
│   ├── ble/
│   │   ├── BleConfig.kt         # UUID / 命令常量（与 apptext 一致）
│   │   └── BleManager.kt        # BLE 扫描、连接、拼包
│   └── ui/
│       ├── home/                # 连接 / 控制
│       ├── album/               # JPEG 预览
│       ├── chat/                # AI 对话（mock）
│       ├── video/               # 录像控制
│       └── settings/            # API 配置
└── README.md
```

## 打开与运行

### 方式一：Android Studio（推荐）

1. 安装 [Android Studio](https://developer.android.com/studio)（自带 JDK 17）
2. **File → Open** → 选择 `android-app` 目录
3. 等待 Gradle Sync 完成
4. 连接真机（需支持 BLE）或模拟器，点击 **Run**

### 方式二：命令行

```bat
cd android-app
gradlew.bat assembleDebug
```

首次需在 Android Studio 中打开一次以生成 `local.properties`（指向 Android SDK）。

## 已实现（阶段 A）

- [x] 扫描并连接 `AI-FieldCam` 设备
- [x] 订阅 SENSOR / CMD_NOTIFY / IMAGE_TX
- [x] 电量、充电、FSM 状态显示
- [x] 发送录像 / 拍照命令
- [x] JPEG 分片拼包与本地相册预览

## 待实现

- [ ] VIDEO_TX 录像文件接收
- [ ] AUDIO_TX / AUDIO_RX 语音链路
- [ ] 云端 API 登录与对话（`backend/`）
- [ ] OTA 升级

## 与 apptext 的关系

| 目录 | 说明 |
|------|------|
| `apptext/` | HBuilderX uni-app x 版，跨端 |
| `android-app/` | 纯原生 Android，便于深度 BLE 调试与 Play 上架 |

两者共用 `docs/BLE协议.md` 与 `BleConfig` 常量定义，可并行开发。
