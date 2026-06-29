# 完整 AI 功能 — 开发路线与验收

> 换 XIAO 板子后架构不变：**固件采集 → App 网关 → 云端 AI**  
> 更新：2026-06-02

---

## 1. 端到端数据流

```
工人说话/按键
    → XIAO 固件（麦/相机/FSM/BLE）
    → 手机 App（GATT 拼包 + HTTPS）
    → 云端 backend（Agent A/B + Vision + ASR/TTS）
    → App → BLE → 设备喇叭 / 相册
```

---

## 2. 三块并行开发

| 块 | 目录 | 当前 | 完整 AI 必达 |
|----|------|------|--------------|
| **云端** | `backend/` | ✅ 骨架 | 登录、`/v1/chat`、`/v1/vision`；配 Key 走真百炼 |
| **固件** | `firmware/` | 🟡 | `ble_service.c` GATT + JPEG 分片；Opus 麦 P2 |
| **App** | `android-app/` | 🟡 | BLE 连接；相册识图；对话页 |

---

## 3. 启动顺序（你今天可做）

### ① 云端

```bash
cd backend
python -m venv venv && venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

演示账号：`13800000000` / `demo`

### ② 改 App API 地址

Android 设置页 `ApiConfig` → 改为电脑局域网 IP（如 `http://192.168.1.100:8000`）

### ③ 烧录固件

```bash
cd firmware
idf.py build flash monitor
```

串口应见：`advertising as AI-FieldCam`

### ④ App 联调

1. 设置页 → 模拟登录  
2. 首页 → 连接相机（扫 `AI-FieldCam`）  
3. 相册 → 远程快门或机身快门 → 收图 + `/v1/vision` 说明  
4. 对话页 → 输入「识别一下」「开始录像」→ 看 `ble_cmds` 执行  

---

## 4. 完整 AI 验收清单

| # | 功能 | 验收 |
|---|------|------|
| 1 | BLE 连接 | App 显示已连接 + 电量 |
| 2 | 快门拍照 | 相册出现 JPEG |
| 3 | 识图播报 | 相册显示云端说明（或 mock 文案） |
| 4 | 语音/文字控设备 | 「开始录像」→ 设备进入录状态 |
| 5 | 追问识图 | 拍后问「刚才那个型号？」→ Agent B |
| 6 | 双击 AI 键 | 固件开麦 + 云端对话（P2 Opus） |
| 7 | TTS 喇叭 | 云端回复 → `AUDIO_RX`（P2） |

**V1 完整 AI 最小闭环：** 1 + 2 + 3 + 4 + 5（文字对话可先代替语音 ASR）

---

## 5. 仍待 P2 的部分

| 项 | 说明 |
|----|------|
| Opus 麦上行 | 固件 `audio_in` → `AUDIO_TX`；App WSS ASR |
| TTS 下行 | 云端 CosyVoice → App → `AUDIO_RX` |
| 720p 录像文件 | `VIDEO_TX` + 相册回放 |
| 真机语音开麦 | 替代对话页打字 |

---

## 6. 相关文档

- [`App开发指南.md`](../guides/App开发指南.md) — 三层分工  
- [`云端AI代理.md`](../guides/云端AI代理.md) — Agent A/B、Vision  
- [`BLE协议.md`](../protocol/BLE协议.md) — GATT 细节  
- [`backend/README.md`](../backend/README.md) — 后端启动  
