# 赢筑AI 云端 API 接口

> 来源：`backend/app/main.py`（FastAPI）  
> 默认基址：`http://<主机>:8000`  
> OpenAPI：启动后访问 `/docs`、`/redoc`

**鉴权约定**

| 标记 | 含义 |
|------|------|
| 无 | 不需要 token |
| Bearer | `Authorization: Bearer <token>`，演示登录或巡查员登录均可 |
| 可选 Bearer | 可带可不带；注销等场景允许仅凭 `device_id` |

---

## 总览

| 分类 | 方法 | 路径 | 鉴权 |
|------|------|------|------|
| 健康 | GET | `/health` | 无 |
| 演示登录 | POST | `/auth/login` | 无 |
| 巡查绑定 | GET | `/auth/patrol/status` | 无 |
| 巡查绑定 | GET | `/auth/patrol/employee-id/new` | 无 |
| 巡查绑定 | POST | `/auth/patrol/step1/profile` | 无 |
| 巡查绑定 | POST | `/auth/patrol/step1/org` | 无 |
| 巡查绑定 | POST | `/auth/patrol/step2/sms/send` | 无 |
| 巡查绑定 | POST | `/auth/patrol/step2/sms/verify` | 无 |
| 巡查绑定 | POST | `/auth/patrol/register` | 无（需 verify_token） |
| 巡查绑定 | POST | `/auth/patrol/login` | 无（需 verify_token） |
| 巡查绑定 | POST | `/auth/patrol/face-only-login` | 无 |
| 巡查绑定 | POST | `/auth/patrol/offboard` | 可选 Bearer |
| 执法仪 | GET | `/v1/recorders/{device_id}` | 无 |
| 执法仪 | PATCH | `/v1/recorders/{device_id}/fault` | 无 |
| 大屏 | GET | `/v1/dashboard/overview` | 无 |
| 大屏 | GET | `/v1/dashboard/devices` | 无 |
| AI | POST | `/v1/chat` | Bearer |
| AI | POST | `/v1/vision` | Bearer |
| AI | POST | `/v1/video` | Bearer |
| 演示场景 | GET | `/v1/demo/scenarios` | 无 |
| 演示场景 | POST | `/v1/demo/scenario` | Bearer |
| 专家 | POST | `/v1/expert/session` | Bearer |
| WebRTC | POST | `/v1/webrtc/call/start` | 无 |
| WebRTC | GET | `/v1/webrtc/call/{call_id}` | 无 |
| WebRTC | POST | `/v1/webrtc/call/{call_id}/end` | 无 |
| WebRTC | POST | `/v1/webrtc/call/{call_id}/offer` | 无 |
| WebRTC | POST/GET | `/v1/webrtc/call/{call_id}/answer` | 无 |
| WebRTC | POST/GET | `/v1/webrtc/call/{call_id}/ice` | 无 |
| WebRTC | GET | `/v1/webrtc/device/{device_id}/poll` | 无 |
| WebRTC | POST/GET | `/v1/webrtc/call/{call_id}/frame` | 无 |
| WebRTC | POST | `/v1/webrtc/call/{call_id}/nal` | 无 |

---

## 1. 健康检查

### `GET /health`

探活与运行时配置摘要。

**响应示例**

```json
{
  "ok": true,
  "dashscope": true,
  "chat_model": "qwen-turbo",
  "vision_model": "agnes-2.0-flash",
  "officer_db": "mysql://...",
  "officer_db_ok": true,
  "officer_db_error": "",
  "face_engine": "...",
  "face_match_threshold": 0.6,
  "face_pipeline": "..."
}
```

---

## 2. 演示登录

### `POST /auth/login`

V1 演示账号登录（非巡查员流程）。

| 字段 | 类型 | 说明 |
|------|------|------|
| phone | string | 手机号 |
| password | string | 密码 |

演示账号：`13800000000` / `demo`

**响应**

```json
{ "token": "...", "phone": "13800000000" }
```

---

## 3. 巡查员绑定与认证

初次绑定典型顺序：

1. `GET /auth/patrol/employee-id/new`（可选，生成工号）
2. `POST /auth/patrol/step1/profile` → 得 `session_id`
3. `POST /auth/patrol/step1/org` → 补全组织信息
4. `POST /auth/patrol/step2/sms/send` → 发短信
5. `POST /auth/patrol/step2/sms/verify` → 得 `verify_token`
6. `POST /auth/patrol/register` → 人脸注册 + 得会话 `token`

已绑定设备日常登录：`POST /auth/patrol/face-only-login`。

### `GET /auth/patrol/status`

查询手机号 / 执法仪绑定状态。

| Query | 类型 | 说明 |
|-------|------|------|
| phone | string | 手机号（可空） |
| device_id | string | 设备编号 |

**响应要点**：`registered`、`device_bound`、`device_pending`、`device_available`、`bound_officer`、`phone_matches_device`、`recorder`（台账对象）。

### `GET /auth/patrol/employee-id/new`

生成唯一 6 位工号。

```json
{ "employee_id": "123456" }
```

### `POST /auth/patrol/step1/profile`

人员信息校验，开启验证会话。

| 字段 | 类型 | 约束 |
|------|------|------|
| name | string | ≥2 |
| gender | string | 默认「未知」 |
| employee_id | string | 恰好 6 位数字 |
| department | string | 可空 |
| device_id | string | ≥4 |
| id_card | string | 18 位 |
| company | string | 可空 |
| position | string | 可空 |

**响应**：`{ "step": 1, "passed": true, "session_id": "...", "message": "..." }`

### `POST /auth/patrol/step1/org`

补充公司 / 部门 / 职位。

| 字段 | 类型 |
|------|------|
| session_id | string |
| company | string |
| department | string |
| position | string |

### `POST /auth/patrol/step2/sms/send`

| 字段 | 类型 | 约束 |
|------|------|------|
| session_id | string | |
| phone | string | 11 位 |

**响应**：`{ "step": 2, "message": "...", "dev_code": "..." }`（`dev_code` 为开发态验证码）

### `POST /auth/patrol/step2/sms/verify`

| 字段 | 类型 | 约束 |
|------|------|------|
| session_id | string | |
| phone | string | 11 位 |
| code | string | 4–8 位 |

**响应**：`{ "step": 2, "passed": true, "verify_token": "...", "phone": "...", "message": "..." }`

### `POST /auth/patrol/register` / `POST /auth/patrol/login`

注册或（旧流程）短信通过后的人脸登录。

| 字段 | 类型 | 约束 |
|------|------|------|
| verify_token | string | ≥8 |
| device_id | string | ≥4，须与验证会话一致 |
| face_image_base64 | string | ≥64 |

**响应**

```json
{
  "step": 3,
  "passed": true,
  "token": "...",
  "phone": "...",
  "name": "...",
  "employee_id": "...",
  "department": "...",
  "device_id": "...",
  "message": "..."
}
```

### `POST /auth/patrol/face-only-login`

已注册设备：仅人脸比对登录。

| 字段 | 类型 |
|------|------|
| device_id | string |
| face_image_base64 | string |

响应字段同注册成功体，`step` 为 `"face_login"`。

### `POST /auth/patrol/offboard`

注销在岗巡查员：解绑设备、云端标记离职。可选 Bearer；无 token 时仅凭 `device_id`。

| 字段 | 类型 |
|------|------|
| device_id | string |

**响应**：`ok`、`status`、`status_label`、`employee_id`、`name`、`gender`、`message`。

---

## 4. 执法仪台账

### `GET /v1/recorders/{device_id}`

查询 / 自动建档执法仪。

**响应字段**

| 字段 | 说明 |
|------|------|
| device_id | 设备编号 |
| device_name | 名称 |
| model | 型号 |
| in_use | 是否占用 |
| employee_id | 绑定工号 |
| is_faulty | 是否故障 |
| fault_note | 故障说明 |
| binding_phase | 绑定阶段 |
| binding_phase_label | 阶段中文 |
| bound_at / unbound_at / last_seen_at | 时间戳 |
| remark | 备注 |

### `PATCH /v1/recorders/{device_id}/fault`

标记或解除故障（运维 / 管控台）。

| 字段 | 类型 | 默认 |
|------|------|------|
| is_faulty | bool | true |
| fault_note | string | `""` |

响应同台账对象。

---

## 5. 智慧控制大屏

### `GET /v1/dashboard/overview`

汇聚执法仪 + 巡查员：`devices`、`groups`、`alerts`、`workOrders`、`kpi`。

### `GET /v1/dashboard/devices`

```json
{ "devices": [ /* 含绑定人员的执法仪列表 */ ] }
```

---

## 6. AI 能力

均需 **Bearer**。

### `POST /v1/chat`

Agent A/B 路由；可能返回设备控制 `ble_cmds` 或演示场景 `demo`。

| 字段 | 类型 | 说明 |
|------|------|------|
| session_id | string | 会话 ID |
| device_id | string | 可选 |
| text | string | 用户话术 |
| state | object | 可选；可含 `ble_state` |

**响应**

```json
{
  "agent": "A",
  "intent": "start_recording|stop_recording|capture_and_explain|chat|demo_scenario|none",
  "reply": "...",
  "ble_cmds": [],
  "demo": {
    "scenario_id": "",
    "title": "",
    "voice_broadcast": "",
    "document": "",
    "highlights": [],
    "platform_sync": "",
    "alert_level": "info"
  }
}
```

`demo` 仅在演示场景命中时出现。

### `POST /v1/vision`

识图：单张 JPEG base64 → 说明，并写入会话「上次识图记忆」。

| 字段 | 类型 | 约束 |
|------|------|------|
| session_id | string | |
| image_base64 | string | ≥64 |

**响应**：`explanation`、`last_explanation`、`model`

### `POST /v1/video`

视频抽帧分析：客户端抽帧后上传 JPEG 列表（最多 24 张），走同一看图模型。

| 字段 | 类型 | 约束 |
|------|------|------|
| session_id | string | |
| images_base64 | string[] | 1–24 |
| frame_count | int | ≥0；0 时用列表长度 |

**响应**：`explanation`、`last_explanation`、`model`、`frame_count`

---

## 7. 演示场景

### `GET /v1/demo/scenarios`

```json
{ "scenarios": [ /* 九大核心业务场景列表 */ ] }
```

### `POST /v1/demo/scenario`（Bearer）

| 字段 | 类型 |
|------|------|
| scenario_id | string |
| device_id | string | 可选 |

返回场景播报、文档与平台同步状态（结构与 chat 的 `demo` 类似）。

---

## 8. AI 技术专家

### `POST /v1/expert/session`（Bearer）

可选带图，支持同会话多轮。

| 字段 | 类型 | 说明 |
|------|------|------|
| session_id | string | |
| device_id | string | 可选 |
| text | string | 咨询内容 |
| image_base64 | string | 可选现场图 |

**响应要点**：`record_id`、`title`、`reply`、`voice_broadcast`、`document`、`highlights`、`platform_sync`、`scene_context` 等。

---

## 9. WebRTC 视频连线（HTTP 信令中继）

供 AI-screen Web 与设备侧轮询 / 推送 SDP、ICE、预览帧。当前接口**无 Bearer**。

### 呼叫生命周期

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/v1/webrtc/call/start` | 发起呼叫 |
| GET | `/v1/webrtc/call/{call_id}` | 呼叫详情 |
| POST | `/v1/webrtc/call/{call_id}/end` | 结束呼叫 |
| GET | `/v1/webrtc/device/{device_id}/poll` | 设备侧拉取指令 |

**`POST .../start` 请求**

```json
{ "device_id": "DSJ-...", "caller": "指挥中心" }
```

**呼叫摘要响应**

```json
{
  "call_id": "...",
  "device_id": "...",
  "caller": "指挥中心",
  "status": "...",
  "has_offer": false,
  "has_answer": false,
  "ice_count": 0,
  "has_frame": false,
  "frame_age_ms": null,
  "updated_at": 0
}
```

`GET .../call/{call_id}` 额外含 `offer_sdp`、`answer_sdp`、`ice`。

设备 poll：`{ "command": ... }`（无待办时为 null/空）。

### SDP / ICE

| 方法 | 路径 | Body / Query |
|------|------|----------------|
| POST | `.../offer` | `{ "sdp": "..." }` |
| POST | `.../answer` | `{ "sdp": "..." }` |
| GET | `.../answer` | → `{ "sdp": "..." }` |
| POST | `.../ice` | `{ "role": "web", "candidate": "...", "sdpMid": "", "sdpMLineIndex": 0 }` |
| GET | `.../ice` | Query `since`（默认 0）→ `{ "ice": [...] }` |

### 预览帧 / NAL

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `.../frame` | `{ "frame_b64": "..." }` |
| GET | `.../frame` | `{ "frame_b64": "", "has_frame": false }` |
| POST | `.../nal` | `{ "nal_b64": "..." }`（可选 H264 扩展） |

---

## 错误约定

| HTTP | 典型场景 |
|------|----------|
| 400 | 注册步骤校验失败、WebRTC 参数错误 |
| 401 | 缺少 / 无效 Bearer |
| 403 | 人脸/绑定校验失败、注销拒绝 |
| 404 | 执法仪不存在、未知演示场景、呼叫不存在 |
| 500 | chat / expert / dashboard 内部错误 |
| 503 | 工号生成失败、专家服务不可用 |

FastAPI 校验失败一般为 `422`。业务错误体多为 `{ "detail": "..." }`。

---

## App 调用对照（Android）

`ApiClient` 主要映射：

| App 方法 | 接口 |
|----------|------|
| `login` | `POST /auth/login` |
| `verifyStep1Profile` | `POST /auth/patrol/step1/profile` |
| SMS 相关 | `.../step2/sms/send`、`.../verify` |
| `patrolAuthenticate` | `register` / `login` |
| `patrolFaceOnlyLogin` | `face-only-login` |
| `offboardPatrolOfficer` | `offboard` |
| chat / vision / video | `/v1/chat`、`/v1/vision`、`/v1/video` |

真机须配置局域网可达的 `API_BASE_URL`，勿使用 `127.0.0.1`。

---

## 相关文档

- [backend/README.md](../../backend/README.md) — 启动与环境变量
- [云端AI代理.md](云端AI代理.md) — Agent A/B 与 Vision 行为
- [V2 视频通信协议](../prd/v2-video-communication.md) — 视频连线产品设计
