# 赢筑扫码 — 手机 App

> **包名：** `com.aifieldcam.mobile` · 与执法仪 `android-app` 配对使用

## 职责

- 在岗人员 **人脸登录** / **自助注册**（`POST /auth/mobile/login|register`）
- 扫描执法仪「我的」页二维码（`device` + `token`）
- 调用 `POST /auth/device/bind/confirm` 完成当次执勤绑定

执法仪端负责出码与轮询；身份校验在云端 `device_bind_store` 统一完成。

## 技术栈

| 项 | 选型 |
|----|------|
| 语言 | Kotlin |
| 最低 SDK | 26 |
| 目标 SDK | 35 |
| UI | Material + ViewBinding |
| 扫码 | CameraX + ML Kit（全屏预览，无提示层） |
| 人脸 | CameraX 前置拍照 → base64 |

## 快速开始

```bat
cd mobile-app
copy local.properties.example local.properties
gradlew.bat installDebug
```

`local.properties` 配置 `sdk.dir`；可选 `backend.host`（如 `192.168.1.106:8000`）。登录页也可修改并持久化 API 地址。

## 页面流程

```
LoginActivity → FaceCaptureActivity → HomeActivity → ScanActivity → BindConfirmActivity
RegisterActivity（可选，注册后直达 HomeActivity）
```

## 相关文档

- [扫码绑定 PRD](../docs/特性%20PRD/qr-scan-device-login.md)
- [API 接口](../docs/开发指南/API接口.md) — §4 扫码绑定、§5 手机端认证
- [CONTEXT.md](../CONTEXT.md) — 领域术语
