# App 开发指南（Android）

> **工程：** [`android-app/`](../../android-app/) · Android Studio 打开此目录  
> **硬件：** Seeed XIAO ESP32S3 + OV5640 / DSJ-ZECN6A1 执法仪  
> **配套：** [`BLE协议.md`](../protocol/BLE协议.md) · [`云端AI代理.md`](云端AI代理.md) · [`总方案手册.md`](../architecture/总方案手册.md)

---

## 1. 三层分工（先记住这个）

```
┌──────────────┐   BLE GATT   ┌──────────────┐   HTTPS/WSS   ┌──────────────┐
│  XIAO 机身    │ ◄──────────► │   手机 App    │ ◄───────────► │   公司云端    │
│  采集 + 播放  │              │  网关 + 界面  │               │  AI + 鉴权   │
└──────────────┘              └──────────────┘               └──────────────┘
  按键/相机/双麦/喇叭             连蓝牙、拼包、展示               Agent A/B、识图
  不持 API Key                  不持 API Key                    持百炼密钥
  不直连大模型                  不直连百炼                      ASR / TTS / 会话
```

| 层 | 定位 | 负责 | 不负责 |
|----|------|------|--------|
| **固件** | 传感器 + 执行器 | 拍照 JPEG、采麦、播喇叭、BLE 上报 | 识图、对话、ASR/TTS |
| **App** | **蓝牙网关 + 工人 UI** | 连设备、收发包、登录、调你们 API、相册/对话页 | 大模型推理、存 API Key |
| **云端** | **智能与运维** | Agent A/B、`qwen3-vl-8b-instruct` 识图、ASR/TTS、额度日志 | 直连设备 GPIO |

**一句话：** 设备只采集和播放，手机是网关，聪明在公司云上。

---

## 2. 还要云端 AI 代理吗？

| 目标 | 要不要云端 |
|------|------------|
| **完整产品**（语音助手 + 识图 + 语音控录像） | **要**，见 [`云端AI代理.md`](云端AI代理.md) |
| **只调板子**（按键、相机、双麦、电量、BLE） | **暂时不要**，App 先做 BLE + 本地相册 |
| **UI 联调、后端未建** | 云端用 **mock**，不删方案 |

| 能力 | 执行层 |
|------|--------|
| 语音听清 | 云端 ASR |
| 「开始录像」、短聊 | 云端 Agent A（`qwen-turbo`） |
| 追问上一张图 | 云端 Agent B |
| 快门识图解释 | 云端 `qwen3-vl-8b-instruct` |
| 播报 | 云端 TTS → App → 设备 `AUDIO_RX` |

换 XIAO 开发板 **只换了机身里的固件/硬件**，上述分工**不变**。

---

## 3. App 能做什么（按阶段）

### 阶段 A — 不依赖云端（★ 建议先做）

配合 XIAO 板级自检，固件需实现 `ble_service.c`（GATT）。

| 功能 | 页面 / 文件 | 验收 |
|------|-------------|------|
| 扫描 / 连接 BLE | `index` · `ble.uts` | 显示已连接 |
| 电量 / 充电 / 状态 | `ble.uts` 订阅 SENSOR | 与固件一致 |
| 发控制命令 | `ble.uts` 写 CMD | 0x01 开录、0x03 拍照等 |
| 收 JPEG 拼包 | `album` · `ble.uts` | 快门后相册有一张真图 |
| 本地预览 | `album` | 仅看图，不调 Vision |

### 阶段 B — Mock 云端（后端未建）

| 功能 | 做法 |
|------|------|
| 假登录 | `settings` · `api.uts` 返回固定 token |
| 假识图 | 收图后显示写死说明 |
| 假对话 | `chat` 本地 echo |

### 阶段 C — 真云端（需 `backend/`）

| 功能 | API | App |
|------|-----|-----|
| 工人登录 | `POST /auth/login` | `settings` |
| 语音对话 | WSS ASR + `POST /v1/chat` | `chat` · `session.uts` |
| 快门识图 | `POST /v1/vision` | `album` · `session.uts` |
| 语音控设备 | intent → BLE CMD | `session.uts` |
| TTS 播报 | 音频 → `AUDIO_RX` | `ble.uts` |

### 阶段 D — 录像（P4）

| 功能 | 说明 |
|------|------|
| VIDEO_TX 分片落盘 | `video` 页 |
| 列表 / 回放 | 本地文件 |

---

## 4. 推荐开发顺序

```
P1  App 收到一张真 JPEG（BLE）     ← 与 XIAO 板子并行
  ↓
P0  最小后端（登录 + mock chat）
  ↓
P3  真识图播报（/v1/vision）
  ↓
P2  真语音对话（ASR + Agent A）
  ↓
P4  录像 · P5 语音控设备 · P6 运维
```

**原则：** P1 未通前，不要全力做 Opus 优化或录像。

| 里程碑 | 验收 |
|--------|------|
| P1 | 手机相册收到 **1 张** 设备拍的 JPEG |
| P3 | 快门后听到识图播报 |
| P2 | 双击 AI 后对话 + 播报 |

---

## 5. 工程与代码结构

```
text1/android-app/      ← Android Studio 打开此目录
├── app/src/main/kotlin/com/aifieldcam/app/
│   ├── ble/            # BleManager、BleConfig、拼包
│   ├── data/           # SessionManager、ApiClient、ApiConfig
│   ├── platform/       # DSJ-ZECN6A1 / ZE69 硬件
│   └── ui/             # 首页 / 对话 / 相册 / 录像 / 设置
└── README.md
```

**规则：** 页面只调 `SessionManager`；勿在 Fragment 里直接写 GATT；工人端 API 地址在设置页配置。

### AI 意图 → 动作（`SessionManager` + 云端 `ble_cmds`）

| intent | 动作 |
|--------|------|
| `start_recording` | BLE CMD `0x01` |
| `stop_recording` | BLE CMD `0x02` |
| `capture_and_recognize` | CMD `0x03` + `/v1/vision` |
| `chat` | TTS 播报（待 P2） |

---

## 6. 快速运行

1. 安装 [Android Studio](https://developer.android.com/studio)
2. 打开目录 `text1/android-app`
3. 连接 Android 真机 → Run
4. 设置页配置 `API_BASE_URL`（如 `http://电脑IP:8000`）
5. 授予蓝牙、相机、相册权限

---

## 7. 当前进度

| 模块 | 状态 |
|------|------|
| 五 Tab + SessionManager | ✅ |
| BLE MTU / IMAGE / VIDEO 拼包 | ✅ |
| 云端登录 / 对话 / 识图 | ✅ |
| DSJ-ZECN6A1 光感夜视 | ✅（sysfs 需系统签名） |
| Opus / WSS ASR / TTS | 🔲 P2 |

**逻辑测试：** `tools/tests/app_ble_logic_test.py` · `app_session_logic_test.py`

**端到端步骤：** [`完整AI功能路线.md`](../architecture/完整AI功能路线.md)

---

## 8. 常见问题

| 问题 | 处理 |
|------|------|
| 扫不到设备 | 固件是否烧录 NimBLE；Android 定位权限 |
| 登录失败 | 后端未部署 → 用阶段 B mock |
| 识图没反应 | 先确认 P1 相册有图，再查 `/v1/vision` |
| 换 XIAO 后 App 要改吗？ | **协议不变**，仍按 `BLE协议.md` |

---

## 9. 相关文档

| 文档 | 何时读 |
|------|--------|
| [`BLE协议.md`](../protocol/BLE协议.md) | 联调 BLE 必查 |
| [`云端AI代理.md`](云端AI代理.md) | 建后端、调 prompt |
| [`XIAO硬件接线.md`](../hardware/XIAO硬件接线.md) | 板子接线与自检 |
| [`固件开发指南.md`](固件开发指南.md) | 烧录 `firmware/` |
| [`android-app/README.md`](../../android-app/README.md) | 工程内说明 |

---

*维护：架构未变时只更新 §3 阶段与 §7 进度。*
