# 云端 AI 方案（路线 1 · 双 Agent A+B）

> 完整项目思路见 [**项目总览.md**](../架构与数据/协议/项目总览.md)。  
> 部署：App 零配置，密钥仅在**你们后端**。工人不填 API。  
> 文本模型：**`qwen-turbo`**（百炼 · 中国内地）。拍图解释：**`qwen3-vl-8b-instruct`**（与 turbo 分开计费/额度）。

## 1. 为何只用 A+B（不用 Agent-C）

| Agent | 职责 | 说明 |
|-------|------|------|
| **A** | 控制 + 短答 | 录像/拍照意图、唤醒确认、简短闲聊 |
| **B** | 追问上一张图 | 基于上次识图结果问答，不重复拍 |
| ~~C~~ | ~~长答/现场记录~~ | **V1 不采用**；长说明由 A 限制字数或 B 扩展一句，避免第三套 prompt |

## 2. 模型与能力映射

| 能力 | 模型 | 调用方 |
|------|------|--------|
| PTT 全双工语音 | `qwen3.5-omni-flash-realtime` | 后端 `/v1/realtime/voice` WebSocket 代理 |
| Agent-A / Agent-B 对话 | `qwen-turbo` | 后端 `POST /v1/chat` |
| 单击拍照识图解释 | `qwen3-vl-8b-instruct` | 后端 `POST /v1/vision`（拍后一次，结果写入会话缓存） |
| 文字→语音 | CosyVoice 等 TTS | 后端 → App → **机身扬声器**（P2） |

### 2.1 PTT 全双工实时语音

- App 只连接本项目后端，百炼 Key 与 Workspace ID 不进入 APK。
- 输入为 16kHz、单声道、PCM16；模型输出为 24kHz、单声道、PCM16。
- 物理 PTT 达到 500ms 后开始流式发送，松手发送
  `input_audio_buffer.commit` 与 `response.create`。
- 模型回答期间再次按下 PTT，App 发送 `response.cancel`、清空播放队列并开始新一轮采音。
- 默认不抓拍；模型仅在需要观察现场时调用 `capture_and_explain`。录像控制和抓拍均经过
  App 内白名单校验，未知或重复工具调用不会执行。
- 录像期间 PCM 从伴随音采集线程实时旁路，不能启动第二路 `AudioRecord` 抢占麦克风。

后端环境变量：

```env
DASHSCOPE_API_KEY=sk-...
DASHSCOPE_WORKSPACE_ID=ws-...
DASHSCOPE_REALTIME_REGION=cn-beijing
REALTIME_MODEL=qwen3.5-omni-flash-realtime
REALTIME_VOICE=Tina
```

### 2.2 Vision 模型：`qwen3-vl-8b-instruct`

| 项 | 说明 |
|----|------|
| **Model ID** | `qwen3-vl-8b-instruct`（百炼 OpenAI 兼容接口 `model` 字段） |
| **模式** | **Instruct**（直接输出说明，无 Thinking 推理链），相对 32B 更省算力、**响应更快** |
| **后端环境变量** | 建议 `VISION_MODEL_ID=qwen3-vl-8b-instruct` |
| **TTS 播报** | 使用 `message.content` 全文播报即可 |
| **会话缓存** | `last_explanation` 存 VL 返回的说明全文 |

**调用示例（OpenAI 兼容，华北2 北京）：**

```python
completion = client.chat.completions.create(
    model=os.environ.get("VISION_MODEL_ID", "qwen3-vl-8b-instruct"),
    messages=[{
        "role": "user",
        "content": [
            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
            {"type": "text", "text": "工地现场相机助手：用口语说明图中设备/铭牌/仪表，不超过3句，禁止编造读数。"},
        ],
    }],
)
explanation = completion.choices[0].message.content
```

**产品注意：** 复杂铭牌/仪表若识别不足，Agent-B 可带原图二次调同一 VL。Agent-A/B 仍用 `qwen-turbo`，**不要**换成 VL 模型。

## 3. 调度器（Router）

```
用户 utterance（ASR 文本）
        │
        ▼
┌───────────────┐
│ 有 last_context │──是──► Agent-B（追问）
│ 且像追问句？    │
└───────┬───────┘
        │否
        ▼
    Agent-A（默认：控制 + 短答）
```

**追问判定（V1 规则即可，不必再上模型）：**

- 会话存在 `last_explanation`（或 `last_vision_at` 在 N 分钟内）
- 且用户话匹配任一模式：  
  `刚才|上一张|那个|读一下|多少|型号|参数|什么意思`

**拍后识图（走 Vision，不经过 A/B 选路）：**

```
快门 / 「识别一下」/ intent `capture_and_explain`
  → 设备 JPEG → App → POST /v1/vision (qwen3-vl-8b-instruct)
  → 写入 session.last_explanation + last_image_id
  → TTS 播报 → 可选：用户接着说话 → Router → B
```

## 4. Agent-A：控制 + 短答

**模型：** `qwen-turbo` · `max_tokens` 建议 128～256  

**System 要点：**

- 工地实地相机语音助手；回复口语化、**不超过 2 句**（除 intent 外）。
- 控制类**只输出 JSON**，不要夹长文。

**Intent JSON（App 本机执行，字段兼容历史命名）：**

```json
{
  "intent": "start_recording | stop_recording | capture_and_explain | chat | none",
  "reply": "给用户听的短句，可选",
  "confidence": 0.0
}
```

| intent | App 动作 |
|--------|----------|
| `start_recording` | `DeviceCmd.CMD_START_RECORD` → Camera2 开录 |
| `stop_recording` | `DeviceCmd.CMD_STOP_RECORD` |
| `capture_and_explain` | `DeviceCmd.CMD_CAPTURE` → `/v1/vision` |
| `chat` | 仅播 `reply` |
| `none` | 仅 TTS 或提示重说 |

**禁止：** Agent-A 在长文中夹杂 intent；B 会话中**不得**输出 `start_recording` 等控制 intent。

## 5. Agent-B：追问上一张图

**模型：** `qwen-turbo` · `max_tokens` 建议 512  

**输入上下文（后端 session）：**

```json
{
  "last_explanation": "上次 qwen3-vl-8b-instruct 的完整说明",
  "last_image_captured_at": "ISO8601",
  "user_question": "用户本轮 ASR 文本"
}
```

**System 要点：**

- **仅根据** `last_explanation` 回答；无依据时明确说「上次没看清 / 请再拍一张」。
- **禁止编造**仪表读数、型号、规格。
- 需要更清晰且缓存里有 `image_id` 时，后端可**再调一次** `qwen3-vl-8b-instruct`（带原图 + 用户问题），将结果仍记为 B 的回答，不更新 intent 路由。

**输出：**

```json
{
  "intent": "chat",
  "reply": "播报给工人的内容"
}
```

## 6. 会话缓存（后端）

| 字段 | 写入时机 | 用途 |
|------|----------|------|
| `last_explanation` | `/v1/vision` 成功 | Agent-B |
| `last_image_id` | 拍照完成 | B 需要时可二次 VL |
| `chat_history` | 每轮 A/B | 可选，V1 可只保留最近 3 轮 |

新一次 `capture_and_explain` **覆盖** `last_explanation`。

## 7. API 约定（App 只调一个聊天口）

```
POST /v1/chat
Authorization: Bearer <worker_token>
{
  "session_id": "...",
  "device_id": "...",
  "text": "用户 ASR 结果",
  "state": { "recording": true|false }   // 可选，辅助 A 判断录像中
}

Response:
{
  "agent": "A" | "B",
  "intent": "...",
  "reply": "...",
  "ble_cmds": [ { "cmd": 1 } ]     // 历史字段名；App 经 SessionManager 本机执行
}
```

拍图解释单独（内部固定 `qwen3-vl-8b-instruct`）：

```
POST /v1/vision
{ "session_id", "image_base64" }
→ { "explanation": "...", "last_explanation": "...", "model": "qwen3-vl-8b-instruct" }
```

## 8. 与设备输出

| 结果 | 路径 |
|------|------|
| TTS | 云 → App → **扬声器**（P2） |
| 录像/拍照 | 后端 `ble_cmds` → App → **SessionManager** → Camera2 / 快门 |

## 9. 额度与配置

- 控制台开启 **免费额度用完即停**（`qwen-turbo`、`qwen3-vl-8b-instruct` 分别开）。
- 地域：**中国内地（北京）**。
- Key 仅在后端环境变量；App 内置你们 API 域名 + 登录 token。

## 10. 相关文档

- [ZE69-驱动控制接口.txt](../DSJ-ZECN6A1%20执法仪/ZE69-驱动控制接口.txt) — 灯控 / 夜视 sysfs
- [项目总览.md](../架构与数据/协议/项目总览.md) — 三层分工与数据流
- [产品需求.md](../产品需求与交/产品需求.md) — 功能与验收
