# AI Field Cam — Android 主控 App

> **硬件：** DSJ-ZECN6A1 · [`docs/hardware/DSJ-ZECN6A1硬件参数.txt`](../docs/hardware/DSJ-ZECN6A1硬件参数.txt)

## 技术栈

| 项 | 选型 |
|----|------|
| 语言 | Kotlin |
| 最低 SDK | 26 (Android 8.0) |
| 目标 SDK | 35 |
| UI | Material 3 + ViewBinding |

## 项目结构

```
android-app/app/src/main/kotlin/com/aifieldcam/app/
├── MainActivity.kt
├── data/           # SessionManager、ApiClient、巡查员存储
├── platform/       # DSJ 本机 Camera2、ZE69 灯控、物理按键
├── demo/           # 九大场景演示
└── ui/             # 首页 / 场景 / AI / 相册 / 我的
```

## 已实现

- [x] DSJ 本机 Camera2 1080p H.264 录像
- [x] 物理按键：录像 / 拍照 / SOS 长按
- [x] 云端对话、相册识图、九大场景演示
- [x] 巡查员 8 步注册 + 人脸登录 + MySQL
- [x] ZE69 光感夜视与录像状态灯（sysfs 需系统签名）

## 待实现

- [ ] ASR/TTS 全双工语音（替代文字 PTT 演示）
- [ ] GB28181 / WebRTC
- [ ] 系统级签名、开机自启

## 运行

```bat
cd android-app
gradlew.bat installDebug
```

后端地址：设置页或 `local.properties` 的 `backend.host`
