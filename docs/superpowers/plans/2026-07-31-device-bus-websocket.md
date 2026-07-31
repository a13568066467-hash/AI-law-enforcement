# DeviceBus WebSocket Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地执法仪业务 DeviceBus（`WS /v1/device-bus`）+ 统一信封/去重，绑定后与 AI Realtime 同生命周期，过渡期保留 poll/MQTT 双投。

**Architecture:** 后端单进程 `DeviceBusHub` 按 `device_id` 保连并下行推送；`task_room`/`command_call` 出站时包装信封（含 `delivery_id`）后 Bus+pending+MQTT。设备侧 `DeviceBusClient` + `DeviceBusRouter` 归一化信封/裸载荷，去重后交给现有 `SessionManager`/`CommandCall*`；媒体状态机不变。

**Tech Stack:** FastAPI WebSocket、Kotlin OkHttp WebSocket、现有 `session_token` 占用鉴权、JUnit / pytest。

**Spec:** [`docs/superpowers/specs/2026-07-31-device-bus-websocket-design.md`](../specs/2026-07-31-device-bus-websocket-design.md)（含 §13 缺陷修订）

## Global Constraints

- Bus URL：`/v1/device-bus`；鉴权失败 close **4401**
- 仅 JSON 文本帧；禁止 PCM/JPEG
- AI Realtime 保持独立 `/v1/realtime/voice`
- 绑定后建连，解绑拆除；指挥打断 AI 时 Bus 保持
- 新路径必填 `delivery_id`；遗留裸 `action` JSON 必须兼容
- 去重窗 **N=200**；内容键回退对齐 `TaskRoomSignalDeduper`
- 本期不依赖 sync 补推；poll 继续
- 多实例 sticky/pubsub 不做
- chat/vision/上传不迁 Bus

## File structure

| 路径 | 职责 |
|------|------|
| `backend/app/device_bus/__init__.py` | 包导出 |
| `backend/app/device_bus/envelope.py` | 信封包装 / `delivery_id` |
| `backend/app/device_bus/hub.py` | 连接表 register/unregister/send |
| `backend/app/device_bus/router.py` | `WS /v1/device-bus` |
| `backend/app/task_room/service.py` | `_queue_device_signal` 改信封并推 Bus |
| `backend/app/command_call/`（出站处） | 同样包装+推 Bus（若仍走 pending） |
| `backend/app/main.py` | include router；online 可选 OR Bus |
| `backend/tests/test_device_bus.py` | Hub + WS + 信封 |
| `android-app/.../platform/DeviceBusEnvelope.kt` | 解析/规范化 |
| `android-app/.../platform/DeviceBusClient.kt` | WS 客户端 |
| `android-app/.../platform/DeviceBusRouter.kt` | 去重+分发 |
| `android-app/.../commandcall/TaskRoomSignalDeduper.kt` | 升级为 N=200 窗 |
| `android-app/.../data/SessionManager.kt` | 绑定启停 Bus；ack 优先 Bus |
| `android-app/.../platform/MqttTopicRouter.kt` | 下行改走 DeviceBusRouter |
| `android-app/.../test/.../DeviceBus*Test.kt` | 单测 |

---

### Task 1: 后端信封工具 + 单测

**Files:**
- Create: `backend/app/device_bus/__init__.py`
- Create: `backend/app/device_bus/envelope.py`
- Create: `backend/tests/test_device_bus_envelope.py`

**Interfaces:**
- Produces: `wrap_downlink(payload: dict, *, delivery_id: str | None = None) -> dict`  
  `extract_business_payload(message: dict) -> tuple[str, dict, str]` → `(type, payload, delivery_id)`

- [ ] **Step 1: Write failing tests**

```python
# backend/tests/test_device_bus_envelope.py
from app.device_bus.envelope import wrap_downlink, extract_business_payload


def test_wrap_adds_delivery_id_and_type_from_action():
    env = wrap_downlink({"action": "task_room_join", "room_id": "r1"})
    assert env["v"] == 1
    assert env["type"] == "task_room_join"
    assert env["delivery_id"]
    assert env["payload"]["room_id"] == "r1"


def test_extract_legacy_bare_action():
    t, p, d = extract_business_payload({"action": "task_room_leave", "task_room_id": "t1"})
    assert t == "task_room_leave"
    assert p["task_room_id"] == "t1"
    assert d  # content-derived or empty per impl contract in test below
```

- [ ] **Step 2: Run tests — expect FAIL (module missing)**

```bash
cd backend && python -m pytest tests/test_device_bus_envelope.py -v
```

- [ ] **Step 3: Implement `envelope.py`**

```python
# backend/app/device_bus/envelope.py
from __future__ import annotations
import time
import uuid
from typing import Any


def wrap_downlink(
    payload: dict[str, Any],
    *,
    delivery_id: str | None = None,
) -> dict[str, Any]:
    body = dict(payload)
    action = str(body.get("action") or body.get("type") or "").strip()
    did = (delivery_id or body.get("delivery_id") or "").strip() or str(uuid.uuid4())
    return {
        "v": 1,
        "type": action,
        "request_id": None,
        "delivery_id": did,
        "ts": int(time.time() * 1000),
        "payload": body,
    }


def extract_business_payload(message: dict[str, Any]) -> tuple[str, dict[str, Any], str]:
    if isinstance(message.get("payload"), dict) and message.get("type"):
        t = str(message["type"]).strip()
        p = dict(message["payload"])
        d = str(message.get("delivery_id") or "").strip()
        return t, p, d
    t = str(message.get("action") or message.get("type") or "").strip()
    return t, dict(message), str(message.get("delivery_id") or "").strip()
```

- [ ] **Step 4: Re-run tests — PASS**

- [ ] **Step 5: Commit**

```bash
git add backend/app/device_bus backend/tests/test_device_bus_envelope.py
git commit -m "feat(backend): DeviceBus 下行信封 wrap/extract"
```

---

### Task 2: DeviceBusHub + WebSocket 路由

**Files:**
- Create: `backend/app/device_bus/hub.py`
- Create: `backend/app/device_bus/router.py`
- Modify: `backend/app/main.py`（`include_router`）
- Create: `backend/tests/test_device_bus_ws.py`

**Interfaces:**
- Produces: `DeviceBusHub.register(device_id, ws)` / `unregister` / `send_json(device_id, message) -> bool` / `is_connected(device_id) -> bool`
- Consumes: `require_auth_token`；`device_bind_repo.get_occupancy_by_session_token` 解析 `device_id`

- [ ] **Step 1: Failing test — hub send only to registered device**

```python
import asyncio
from app.device_bus.hub import DeviceBusHub

class FakeWs:
    def __init__(self):
        self.sent = []
    async def send_json(self, data):
        self.sent.append(data)

def test_hub_send_to_registered():
    hub = DeviceBusHub()
    ws = FakeWs()
    asyncio.get_event_loop().run_until_complete(hub.register("DSJ-1", ws))
    ok = asyncio.get_event_loop().run_until_complete(
        hub.send_json("DSJ-1", {"type": "task_room_leave", "v": 1})
    )
    assert ok is True
    assert ws.sent[0]["type"] == "task_room_leave"
```

（若项目已有 pytest-asyncio，改用 `@pytest.mark.asyncio`。）

- [ ] **Step 2: Implement hub**

```python
# backend/app/device_bus/hub.py — 单例或模块级 hub
# dict[str, WebSocket]; asyncio.Lock; register 替换旧连并 close 旧连
```

- [ ] **Step 3: Implement router**

```python
# backend/app/device_bus/router.py
@router.websocket("/v1/device-bus")
async def device_bus(websocket: WebSocket):
    try:
        token = require_auth_token(websocket.headers.get("authorization"))
    except HTTPException:
        await websocket.close(code=4401, reason="invalid token")
        return
    # resolve device_id from occupancy by session_token; else wait hello
    await websocket.accept()
    # loop: receive text JSON; on hello register; on ack forward to task_room.ack_device
    # finally unregister
```

解析占用：

```python
with get_db() as conn:  # 使用项目既有 conn 助手
    occ = device_bind_repo.get_occupancy_by_session_token(conn, token)
device_id = occ["device_id"] if occ else ""
```

若 accept 前无 device_id：accept 后首条必须为 `hello` 且 `payload.device_id` 与占用一致，否则 close 4401。

- [ ] **Step 4: Wire `main.py`**

```python
from app.device_bus.router import router as device_bus_router
app.include_router(device_bus_router)
```

- [ ] **Step 5: pytest PASS + commit**

```bash
git commit -m "feat(backend): DeviceBus WebSocket hub 与 /v1/device-bus"
```

---

### Task 3: task_room / command_call 出站双投信封

**Files:**
- Modify: `backend/app/task_room/service.py`（`_queue_device_signal`）
- Modify: `backend/app/command_call/service.py`（设备 pending/MQTT 出站处，保持同构）
- Modify: `backend/tests/test_task_room.py`（断言 pending 含信封或至少 `delivery_id`）

**Interfaces:**
- Consumes: `wrap_downlink`、`DeviceBusHub.send_json`
- Produces: pending 中存**完整信封**；poll 返回信封（设备 Router 可拆）；MQTT 发布信封 JSON 字符串

- [ ] **Step 1: 扩展/更新测试**

```python
def test_queue_signal_includes_delivery_id(monkeypatch):
    # add_device / 触发 join 后
    pending = task_room_session.poll_device(device_id)
    assert pending is not None
    # 允许裸或信封：若信封
    if "delivery_id" in pending:
        assert pending["type"] == "task_room_join"
        assert "payload" in pending
    else:
        assert pending.get("action") == "task_room_join"
        assert pending.get("delivery_id")  # 若选择顶层打标方案
```

**推荐存储形态（与规格一致）：** pending 存信封；`poll_device` 原样返回 `command: envelope`。MQTT `publish_*` 发送 `json.dumps(envelope)`。

- [ ] **Step 2: 改 `_queue_device_signal`**

```python
from app.device_bus.envelope import wrap_downlink
from app.device_bus.hub import hub

def _queue_device_signal(device_id: str, payload: dict[str, Any]) -> None:
    envelope = wrap_downlink(payload)
    with _lock:
        _device_pending[device_id] = dict(envelope)
    try:
        # fire-and-forget: schedule hub.send_json if running loop
        asyncio.get_event_loop().create_task(hub.send_json(device_id, envelope))
    except RuntimeError:
        pass  # 无 loop 时仅 pending+mqtt
    # 既有 mqtt publish：改发 envelope
```

注意：FastAPI 同步路由里推 async 应用 `asyncio.create_task` 或 hub 提供线程安全 `send_json_threadsafe`。实现时选一种并单测覆盖「无 WS 时 pending 仍在」。

- [ ] **Step 3: poll 响应兼容** — `command` 字段改为信封；Android Task 5 已兼容裸/信封。

- [ ] **Step 4: pytest task_room + device_bus PASS；commit**

```bash
git commit -m "feat(backend): 任务房信令信封化并双投 DeviceBus"
```

---

### Task 4: Android 去重窗 N=200 + 信封解析

**Files:**
- Create: `android-app/app/src/main/kotlin/com/aifieldcam/app/platform/DeviceBusEnvelope.kt`
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/platform/commandcall/TaskRoomSignalDeduper.kt`
- Create: `android-app/app/src/test/kotlin/com/aifieldcam/app/platform/DeviceBusEnvelopeTest.kt`
- Create/Modify: `.../commandcall/TaskRoomSignalDeduperTest.kt`（若无则建）

**Interfaces:**
- Produces: `DeviceBusEnvelope.parse(json): Parsed?` data class `Parsed(type, payload, deliveryId, dedupeKey)`
- Produces: `TaskRoomSignalDeduper.note(key): Boolean` 保留 API；内部改为 LinkedHashSet 容量 200

- [ ] **Step 1: Failing tests**

```kotlin
@Test
fun parseEnvelope() {
    val raw = JSONObject("""{"v":1,"type":"task_room_join","delivery_id":"d1","payload":{"action":"task_room_join","room_id":"r"}}""")
    val p = DeviceBusEnvelope.parse(raw)!!
    assertEquals("task_room_join", p.type)
    assertEquals("d1", p.deliveryId)
    assertEquals("d1", p.dedupeKey)
}

@Test
fun parseLegacyBareUsesContentKey() {
    val raw = JSONObject("""{"action":"task_room_leave","task_room_id":"t1","room_id":"r1"}""")
    val p = DeviceBusEnvelope.parse(raw)!!
    assertEquals("task_room_leave", p.type)
    assertTrue(p.dedupeKey.contains("t1"))
}

@Test
fun deduperWindowKeeps200() {
    TaskRoomSignalDeduper.resetForTests()
    repeat(200) { assertTrue(TaskRoomSignalDeduper.note("k$it")) }
    assertFalse(TaskRoomSignalDeduper.note("k0")) // still in window
    assertTrue(TaskRoomSignalDeduper.note("k200")) // evicts oldest
    assertTrue(TaskRoomSignalDeduper.note("k0")) // k0 evicted, accept again
}
```

- [ ] **Step 2: Implement envelope + deduper**

`dedupeKey = deliveryId.ifBlank { contentKeyFrom(payload) }`  
`contentKeyFrom` 调用现有 `keyForStart`/`keyForLeave`（先 `CommandCallSignalParser.parseStart`）。

- [ ] **Step 3: Run unit tests**

```powershell
cd android-app; .\scripts\run-unit-tests.ps1
# 或: .\gradlew.bat :app:testDebugUnitTest --tests "com.aifieldcam.app.platform.*"
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(android): DeviceBus 信封解析与去重窗 N=200"
```

---

### Task 5: DeviceBusRouter 接入 poll / MQTT

**Files:**
- Create: `android-app/.../platform/DeviceBusRouter.kt`
- Modify: `SessionManager.kt`（`pollCommandCallCommands`、`handleCommandCallStartPayload`、leave 入口改经 Router）
- Modify: `MqttTopicRouter.kt`（command_call/task_room 分支调用 Router）

**Interfaces:**
- Produces: `DeviceBusRouter.dispatch(session: SessionManager, json: JSONObject, source: String)`
- Consumes: `DeviceBusEnvelope.parse`、`TaskRoomSignalDeduper.note`、现有 `dispatchCommandCallStartSignal` / `dispatchTaskRoomLeave`

- [ ] **Step 1: Router 单测（mock 回调或包内 test seams）**

```kotlin
@Test
fun duplicateDeliveryIdSkipped() {
    // 两次相同 delivery_id，第二次不应调用 handler
}
```

- [ ] **Step 2: 实现 Router**

```kotlin
object DeviceBusRouter {
    fun dispatch(session: SessionManager, json: JSONObject, source: String) {
        val parsed = DeviceBusEnvelope.parse(json) ?: return
        if (!TaskRoomSignalDeduper.note(parsed.dedupeKey)) {
            Log.d(TAG, "dedup skip source=$source key=${parsed.dedupeKey}")
            return
        }
        when (parsed.type) {
            "task_room_join", "watch_start", "call_start", "occupy_room", /*…*/ ->
                session.handleCommandCallStartPayload(parsed.payload)
            "task_room_leave", "watch_end", "call_end" ->
                session.handleTaskRoomOrCallLeave(parsed.payload)
            else -> Log.d(TAG, "ignored type=${parsed.type} source=$source")
        }
    }
}
```

注意：`handleCommandCallStartPayload` 内**去掉**二次 `TaskRoomSignalDeduper.note`（避免双去重）；leave 同理。把去重唯一放到 Router。

- [ ] **Step 3: poll / MQTT 改为 `DeviceBusRouter.dispatch(...)`**

- [ ] **Step 4: 单测 PASS；commit**

```bash
git commit -m "feat(android): DeviceBusRouter 统一 poll/MQTT 下行"
```

---

### Task 6: DeviceBusClient + SessionManager 生命周期

**Files:**
- Create: `android-app/.../platform/DeviceBusClient.kt`（镜像 `RealtimeVoiceClient`：OkHttp、pingInterval 15s、退避 5×3^n 封顶 60s、generation）
- Modify: `SessionManager.kt`：绑定成功 `connectBus()`；解绑 `disconnectBus()`；指挥打断 AI **不**断 Bus
- Modify: ack：Bus 已连则 `send ack` 信封，否则既有 HTTP

**Interfaces:**
- Produces: `DeviceBusClient.connect(Config)` / `disconnect()` / `send(text): Boolean` / `isConnected(): Boolean`
- Config: `baseUrl, token, deviceId, sessionId`

- [ ] **Step 1: Client 连接 URL / Header 单测或小测试**（可测纯函数 `wsUrl(base)`）

```kotlin
fun wsUrl(base: String) = base.trimEnd('/')
    .replaceFirst("https://", "wss://")
    .replaceFirst("http://", "ws://") + "/v1/device-bus"
```

- [ ] **Step 2: 实现 Client** — `onOpen` 发送：

```json
{"v":1,"type":"hello","delivery_id":"","ts":0,"payload":{"device_id":"...","session_id":"..."}}
```

`onMessage` → main thread → `DeviceBusRouter.dispatch(session, json, "bus")`  
`onFailure` / close 4401 → 回调 `onAuthInvalid` / 重连（4401 不重连，走占用失效）

- [ ] **Step 3: SessionManager 挂钩**

在现有「绑定成功预热 AI」同一路径调用 `deviceBus.connect(...)`；  
在 `unbind` / 占用失效 teardown 调 `deviceBus.disconnect()`；  
确认 `IN_CALL` 打断 AI 的代码路径**不**调用 `deviceBus.disconnect()`。

- [ ] **Step 4: ackTaskRoomJoin**

```kotlin
if (deviceBus.isConnected()) {
    deviceBus.send(DeviceBusEnvelope.ack(deliveryId, "task_room_join", taskRoomId))
} else {
    ApiClient.ackTaskRoomDevice(...)
}
```

需在 Router/join 路径把 `deliveryId` 传到 ack（可存 `lastJoinDeliveryId`）。

- [ ] **Step 5: 单测 + 手工联调清单勾选规格 §10 的 1/5/6；commit**

```bash
git commit -m "feat(android): DeviceBusClient 绑定生命周期与优先 Bus ack"
```

---

### Task 7: 在线判定（可选增强）与文档

**Files:**
- Modify: `backend/app/main.py` — `_command_call_device_online`：`last_seen` **或** `hub.is_connected(device_id)`
- Modify: `docs/superpowers/specs/2026-07-31-device-task-room-design.md` §6：主路径 Bus，MQTT 迁移可选，去重 `delivery_id`
- Modify: `docs/开发指南/四端连接总览.md` 通道表增加 Bus
- Modify: `docs/README.md` 链到本计划/规格（若有索引习惯）

- [ ] **Step 1: online OR bus**

```python
def _command_call_device_online(device_id: str) -> bool:
    from app.device_bus.hub import hub
    if hub.is_connected(device_id):
        return True
    # existing last_seen logic
```

- [ ] **Step 2: 文档修订（按规格 §9.2）**

- [ ] **Step 3: Commit**

```bash
git commit -m "docs: DeviceBus 通道写入任务房与四端总览"
```

---

### Task 8: 回归验收

- [ ] **Step 1: 后端**

```bash
cd backend && python -m pytest tests/test_device_bus_envelope.py tests/test_device_bus_ws.py tests/test_task_room.py -v
```

Expected: PASS

- [ ] **Step 2: Android unit**

```powershell
cd android-app; .\gradlew.bat :app:testDebugUnitTest --tests "*DeviceBus*" --tests "*TaskRoomSignalDeduper*" --tests "*CommandCall*"
```

Expected: PASS

- [ ] **Step 3: 手工联调（规格 §10）**

| # | 场景 | 结果 |
|---|------|------|
| 1 | 绑定后 Bus+AI 均连；解绑均断 | ☐ |
| 2 | 仅 Bus join/leave | ☐ |
| 3 | 仅 poll（断 Bus）join/leave | ☐ |
| 4 | 双投同 `delivery_id` 不双进房 | ☐ |
| 5 | 监看不打断 AI；指挥打断 AI、Bus 仍连 | ☐ |
| 6 | 指挥挂断 AI 再预热 | ☐ |
| 7 | 换 room_id 先 leave 再 enter | ☐ |
| 8 | token 失效 4401 清占用 | ☐ |

- [ ] **Step 4: 最终 commit（若有修复）**

```bash
git commit -m "test: DeviceBus 回归与联调问题修复"
```

---

## Spec coverage (self-review)

| 规格要点 | Task |
|----------|------|
| `/v1/device-bus` + 4401 | T2 |
| 信封 + delivery_id + 裸载荷兼容 | T1, T3, T4 |
| Hub 定点推送 + pending/MQTT 双投 | T3 |
| 去重 N=200 | T4 |
| Router 通道无关 | T5 |
| 绑定生命周期 / 指挥保 Bus | T6 |
| hello；ack 优先 Bus | T6 |
| poll 继续 | T3, T5（不删 poll） |
| AI 独立 | 不改 Realtime 协议（T6 仅生命周期并列） |
| 文档 §9.2 | T7 |
| 验收 §10 | T8 |
| 多实例 / chat/vision / 上传 | 非范围，无任务 |

## Placeholder scan

无 TBD/TODO 步骤；命令与路径已写明。

## Type consistency

- 信封字段名：`v/type/request_id/delivery_id/ts/payload` 前后端一致  
- 去重：统一 `TaskRoomSignalDeduper.note(dedupeKey)`，Router 唯一调用点  
- Hub API：`send_json(device_id, message)`
