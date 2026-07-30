# 赢筑AI — Android 主控 App

> **硬件：** DSJ-ZECN6A1 · [`docs/DSJ-ZECN6A1 执法仪/`](../docs/DSJ-ZECN6A1%20执法仪/)（参数、ZE69 驱动、系统签名）

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
├── data/           # SessionManager、ApiClient、扫码绑定
├── platform/       # DSJ 本机 Camera2、ZE69 灯控、物理按键
├── demo/           # 九大场景演示
└── ui/             # 首页 / AI / 相册 / 我的（扫码出码）
```

## 已实现

- [x] DSJ 本机 Camera2 1080p H.264 录像
- [x] 物理按键：录像 / 拍照 / SOS 长按
- [x] 扫码绑定：执法仪 QR 轮询 + 手机 `mobile-app` 确认
- [x] 云端对话、相册识图、九大场景演示
- [x] 设置精简（人员信息 / 解绑 / 关于我们）；相册含照片 + 录像
- [x] ZE69 录像状态灯；红外补光已禁用（见 [`系统签名与适配.md`](../docs/DSJ-ZECN6A1%20执法仪/系统签名与适配.md)）

## 待实现

- [x] 本机 TTS 播报（识图 + AI 回复）
- [ ] ASR 真 PTT + 云端 TTS 流式（P2）
- [x] 指挥连线 / 监看（TRTC，见 [`command-call-trtc.md`](../docs/特性%20PRD/command-call-trtc.md)）
- [ ] GB28181 国标推流（独立演进）
- [ ] 系统级签名、开机默认桌面（[`ZE69刷机与预装.md`](../docs/DSJ-ZECN6A1%20执法仪/ZE69刷机与预装.md) P0–P2）

## 运行

```bat
cd android-app
gradlew.bat installDebug
```

后端地址：设置页或 `local.properties` 的 `backend.host`
