# AI Field Cam — uni-app x（HBuilderX）

> 仓库路径：`text1/apptext/` · 用 **HBuilderX** 打开**本目录**（不是整个 text1 根目录）

## 打开方式

1. HBuilderX → 文件 → 打开目录 → 选择 `d:\text1\apptext`
2. 运行 → 运行到手机或模拟器 → **Android App**（需 USB 调试）
3. 发行 → 原生 App-云打包 → Android apk

## 工程结构

```
apptext/
├── pages/
│   ├── index/      # 首页：BLE 连接
│   ├── chat/       # AI 对话 → /v1/chat
│   ├── album/      # 相册（IMAGE_TX + vision）
│   ├── video/      # 录像列表（VIDEO_TX）
│   └── settings/   # 登录、API 域名
├── services/
│   ├── session.uts # ★ 统一入口（对齐 项目总览 device_session）
│   ├── ble.uts
│   └── api.uts
├── common/config.uts  # API 域名、BLE UUID
├── pages.json
└── manifest.json
```

## 配置

| 文件 | 改什么 |
|------|--------|
| `common/config.uts` | `API_BASE_URL` 改为你们正式后端 |
| `manifest.json` | App 名称、Android 蓝牙/定位/网络权限（可视化界面） |

## 协议与文档

- **分工与开发阶段：** [`../docs/App开发指南.md`](../docs/App开发指南.md)（设备 / App / 云端）
- BLE：[`../docs/BLE协议.md`](../docs/BLE协议.md)
- 云端 AI：[`../docs/云端AI代理.md`](../docs/云端AI代理.md)
- 交互参考：[`../demo-web/`](../demo-web/)

## 当前进度

| 模块 | 状态 |
|------|------|
| 五页 + TabBar | ✅ |
| `ble.uts` GATT/拼包/状态 | ✅ |
| `api.uts` 真后端 + 离线 mock | ✅ |
| `session.uts` 相册/对话/录像编排 | ✅ |
| 逻辑测试 | `python tools/app_ble_logic_test.py` 等 |
| Opus / VIDEO_TX 回放 | 🔲 P2/P4 |

## AI 意图路由（`session.uts`）

| intent | 动作 |
|--------|------|
| start_recording | BLE CMD 0x01 |
| stop_recording | BLE CMD 0x02 |
| capture_and_recognize | BLE CMD 0x03 + `/v1/vision` |
| chat | TTS 播报 |

## 技术栈

- **uni-app x**（`.uvue` + `.uts`）
- 目标：**Android 工人端**，零配置登录，无 API Key 页
- 识图模型：**`qwen3-vl-8b-instruct`**（见 `docs/云端AI代理.md`）
