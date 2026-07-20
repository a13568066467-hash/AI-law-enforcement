# Command Call Skeleton (Issue 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or subagent-driven-development) to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 端到端呼叫骨架：平台发起 → UserSig/房间签发 → MQTT 开始 → 设备经 CommandCallRoom(Fake) 自动进房 → 平台「连线中」→ 平台结束 → 双方清理.

**Status:** Implemented 2026-07-20 (inline execution). Backend pytest 7 passed; Android JUnit 48 passed.

**Architecture:** 新建后端 `command_call` 会话服务（与冻结的 `/v1/webrtc/*` 并行、不混用）。UserSig 仅后端签发；MQTT 经可注入发布器下发开始/结束；设备侧 `CommandCallController` 收信令后 `CommandCallRoom.join/leave`，不发 answer/busy/hangup。测试全程 Fake TRTC + Fake MQTT，不接真腾讯云。

**Tech Stack:** Python/FastAPI、Kotlin/JUnit、既有 MQTT topic 路由、Issue 1 `CommandCallRoom*`

## Global Constraints

- 设备不发送 answer / busy / hangup 上行控制消息。
- SecretKey / SDKAppID 仅后端环境变量；客户端只拿 UserSig、sdkAppId、roomId、userId。
- 测试用 FakeCommandCallRoomAdapter，不接真 TRTC SDK。
- 依赖 Issue 1 已合入的 `CommandCallRoomAdapter` / `CommandCallRoom` / `FakeCommandCallRoomAdapter`。
- 旧 `/v1/webrtc/*` 冻结不改语义；指挥连线走新路径。
- 领域词：指挥连线 / 连线信令 / 连线房间（见 CONTEXT.md）。

## File Structure

| Path | Responsibility |
|------|----------------|
| `backend/app/usersig.py` | UserSig 签发（env 配置；缺配置明确失败） |
| `backend/app/command_call_mqtt.py` | 连线信令 MQTT 发布接缝 + 内存 Fake |
| `backend/app/command_call_session.py` | 连线会话：建房、签发双方凭证、占用校验、poll/end |
| `backend/app/main.py` | HTTP：`/v1/command-call/*` + `/health` TRTC 标志 |
| `backend/.env.example` | `TRTC_SDK_APP_ID` / `TRTC_SECRET_KEY` |
| `backend/tests/test_command_call_skeleton.py` | 端到端契约测试（Fake MQTT） |
| `android-app/.../commandcall/CommandCallController.kt` | 收开始/结束 → join/leave；无上行应答 |
| `android-app/.../MqttTopicRouter.kt` | 订阅 `command_call/start|end` |
| `android-app/.../SessionManager.kt` | 委托控制器；HTTP poll 兜底 |
| `android-app/.../ApiClient.kt` | `pollCommandCallDevice` |
| `android-app/.../commandcall/*Test.kt` | 控制器 + 路由载荷解析单测 |

---

### Task 1: Backend UserSig issuer

**Files:**
- Create: `backend/app/usersig.py`
- Modify: `backend/.env.example`
- Test: `backend/tests/test_command_call_skeleton.py`（本任务先写 UserSig 相关用例）

**Interfaces:**
- Produces: `trtc_configured() -> bool`
- Produces: `issue_user_sig(user_id: str, *, expire_seconds: int = 86400) -> str`
- Produces: `sdk_app_id() -> int`
- Env: `TRTC_SDK_APP_ID`, `TRTC_SECRET_KEY`

- [ ] **Step 1: Write failing tests for config + issue**

```python
def test_trtc_configured_false_without_env(monkeypatch):
    monkeypatch.delenv("TRTC_SDK_APP_ID", raising=False)
    monkeypatch.delenv("TRTC_SECRET_KEY", raising=False)
    from app import usersig
    usersig.reload_config()
    assert usersig.trtc_configured() is False

def test_issue_user_sig_requires_config(monkeypatch):
    monkeypatch.delenv("TRTC_SDK_APP_ID", raising=False)
    monkeypatch.delenv("TRTC_SECRET_KEY", raising=False)
    from app import usersig
    usersig.reload_config()
    with pytest.raises(RuntimeError, match="TRTC"):
        usersig.issue_user_sig("platform-1")

def test_issue_user_sig_returns_nonempty(monkeypatch):
    monkeypatch.setenv("TRTC_SDK_APP_ID", "1600152450")
    monkeypatch.setenv("TRTC_SECRET_KEY", "test-secret-key-for-unit")
    from app import usersig
    usersig.reload_config()
    assert usersig.trtc_configured() is True
    sig = usersig.issue_user_sig("device-DSJ-1")
    assert isinstance(sig, str) and len(sig) > 10
    assert usersig.sdk_app_id() == 1600152450
```

- [ ] **Step 2: Run tests — expect FAIL (module missing)**

Run: `cd backend; python -m pytest tests/test_command_call_skeleton.py -k usersig -v`

- [ ] **Step 3: Implement `usersig.py`**

使用腾讯云 TLSSigAPIv2 兼容算法（HMAC-SHA256 + zlib + base64），从 env 读配置。`reload_config()` 供测试重置。缺配置时 `issue_user_sig` / `sdk_app_id` 抛 `RuntimeError`，文案含 `TRTC`。

- [ ] **Step 4: Append to `.env.example`**

```
# 指挥连线 TRTC（SecretKey 仅后端；勿写入 App/浏览器）
# TRTC_SDK_APP_ID=1600152450
# TRTC_SECRET_KEY=
```

- [ ] **Step 5: Run tests — expect PASS**

---

### Task 2: Command-call session + Fake MQTT publisher

**Files:**
- Create: `backend/app/command_call_mqtt.py`
- Create: `backend/app/command_call_session.py`
- Test: `backend/tests/test_command_call_skeleton.py`

**Interfaces:**
- Produces MQTT: `CommandCallMqttPublisher.publish_start(device_id, payload: dict) / publish_end(device_id, payload: dict)`
- Produces Fake: `FakeCommandCallMqttPublisher` with `starts: list`, `ends: list`
- Produces session:
  - `reset()` / `use_mqtt(publisher)` / `use_occupancy_checker(fn)`
  - `start_command_call(device_id, caller="指挥中心") -> dict`
  - `end_command_call(call_id) -> None`
  - `get_call(call_id) -> dict`
  - `poll_device(device_id) -> dict | None`  # HTTP 兜底；含凭证；设备不回执
- Start payload (MQTT + poll): `action, call_id, caller, room_id, sdk_app_id, user_id, user_sig`
- End payload: `action=call_end, call_id`
- Status: `connecting` → 设备 poll 消费开始后 `in_call` → end 后 `ended`
- 占用：`occupancy_checker(device_id) -> bool`；默认查 `device_bind_store`；未占用抛 `ValueError("device not occupied")`
- 同设备已有未结束连线：结束旧会话再建新会话

- [ ] **Step 1: Write failing E2E skeleton test**

```python
def test_command_call_skeleton_end_to_end(monkeypatch):
    monkeypatch.setenv("TRTC_SDK_APP_ID", "1600152450")
    monkeypatch.setenv("TRTC_SECRET_KEY", "test-secret-key-for-unit")
    from app import usersig, command_call_session as ccs
    from app.command_call_mqtt import FakeCommandCallMqttPublisher

    usersig.reload_config()
    mqtt = FakeCommandCallMqttPublisher()
    ccs.reset()
    ccs.use_mqtt(mqtt)
    ccs.use_occupancy_checker(lambda _device_id: True)

    started = ccs.start_command_call("DSJ-E2E-001")
    assert started["status"] == "connecting"
    assert started["room_id"]
    assert started["platform"]["user_sig"]
    assert started["platform"]["sdk_app_id"] == 1600152450
    assert "secret" not in str(started).lower()
    assert len(mqtt.starts) == 1
    assert mqtt.starts[0]["device_id"] == "DSJ-E2E-001"
    assert mqtt.starts[0]["payload"]["user_sig"]
    assert mqtt.starts[0]["payload"]["action"] == "call_start"

    # 设备经 HTTP 兜底拉到开始（含设备侧凭证），自动「进房」语义由客户端测；
    # 此处 poll 消费后平台可见连线中。
    cmd = ccs.poll_device("DSJ-E2E-001")
    assert cmd["action"] == "call_start"
    assert cmd["user_sig"]
    assert ccs.get_call(started["call_id"])["status"] == "in_call"

    ccs.end_command_call(started["call_id"])
    assert len(mqtt.ends) == 1
    assert mqtt.ends[0]["payload"]["action"] == "call_end"
    assert ccs.get_call(started["call_id"])["status"] == "ended"
    end_cmd = ccs.poll_device("DSJ-E2E-001")
    assert end_cmd["action"] == "call_end"

def test_start_rejects_unoccupied_device(monkeypatch):
    # ... occupancy_checker → False → ValueError
```

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement mqtt + session modules**

`start_command_call` 签发平台与设备两套 UserSig（userId 分别为 `platform-{call_id}` / `device-{device_id}`），roomId = `room-{call_id}`。MQTT start 携带**设备**凭证。返回给平台的 dict 含平台凭证摘要与 `call_id`/`room_id`/`status`。

- [ ] **Step 4: Run — expect PASS**

---

### Task 3: HTTP routes + health TRTC flag

**Files:**
- Modify: `backend/app/main.py`
- Test: `backend/tests/test_command_call_skeleton.py`

**Interfaces:**
- `POST /v1/command-call/start` body `{device_id, caller?}` → session dict；400 未占用/缺参；503 TRTC 未配置
- `GET /v1/command-call/{call_id}` → status detail
- `POST /v1/command-call/{call_id}/end` → `{ok: true}`
- `GET /v1/command-call/device/{device_id}/poll` → `{command: ...|null}`
- `/health` 增加 `"trtc": usersig.trtc_configured()`

- [ ] **Step 1: Write route-level tests via direct handler or TestClient**

优先与现有风格一致：直接调 `command_call_session` 已在 Task 2 覆盖；本任务补 `health` 含 `trtc`，并用 FastAPI `TestClient` 打一条 start→poll→end（若引入 TestClient 成本高，则只测 health 字段 + 保持 session 单测）。

最小要求：

```python
def test_health_includes_trtc(monkeypatch):
    monkeypatch.setenv("TRTC_SDK_APP_ID", "1")
    monkeypatch.setenv("TRTC_SECRET_KEY", "k")
    from app import usersig
    usersig.reload_config()
    from app.main import health
    body = health()
    assert "trtc" in body
    assert body["trtc"] is True
```

- [ ] **Step 2: Wire routes in `main.py`；health 加 trtc**

- [ ] **Step 3: pytest PASS**

---

### Task 4: Android CommandCallController

**Files:**
- Create: `android-app/app/src/main/kotlin/com/aifieldcam/app/platform/commandcall/CommandCallController.kt`
- Create: `android-app/app/src/test/kotlin/com/aifieldcam/app/platform/commandcall/CommandCallControllerTest.kt`
- Modify: `android-app/app/build.gradle.kts`（`runUnitTestsInline` args 增加本测试类）

**Interfaces:**
- Produces: `object CommandCallController`
  - `resetForTests()`
  - `activeCallId(): String`
  - `isInCall(): Boolean`  // CommandCallRoom.isInRoom()
  - `onCallStart(callId, credentials: CommandCallCredentials): Boolean`  // join；已在通话则忽略/返回 false
  - `onCallEnd(callId: String = "")`  // leave；清理 activeCallId
- 不发布任何 MQTT/HTTP 应答。

- [ ] **Step 1: Failing test — start joins Fake room; end leaves; no uplink**

```kotlin
@Test
fun startJoinsFakeRoomAndEndLeaves() {
    CommandCallRoom.resetToFake()
    CommandCallController.resetForTests()
    val creds = CommandCallCredentials(1600152450, "room-1", "device-1", "sig")
    assertTrue(CommandCallController.onCallStart("call-1", creds))
    assertTrue(CommandCallController.isInCall())
    assertEquals("call-1", CommandCallController.activeCallId())
    assertTrue(CommandCallRoom.current().isInRoom())
    CommandCallController.onCallEnd("call-1")
    assertFalse(CommandCallController.isInCall())
    assertEquals("", CommandCallController.activeCallId())
    assertFalse(CommandCallRoom.current().isInRoom())
}
```

- [ ] **Step 2: RED → implement → GREEN**

---

### Task 5: MQTT topic dispatch + SessionManager + HTTP poll

**Files:**
- Modify: `MqttTopicRouter.kt` — 订阅并分发
  - `/thing/service/command_call/start`
  - `/thing/service/command_call/end`
- Modify: `SessionManager.kt` — `onCommandCallStart` / `onCommandCallEnd`；poll 兜底
- Modify: `ApiClient.kt` — `pollCommandCallDevice`
- Create: `CommandCallSignalParser.kt` + 单测（纯 JSON → credentials，便于测载荷）

**MQTT start JSON:**
`call_id|callId, caller, room_id|roomId, sdk_app_id|sdkAppId, user_id|userId, user_sig|userSig`

**行为:**
- start → TTS 短提示（可复用既有 TtsSpeaker）→ `CommandCallController.onCallStart`（**不**走 WebRtcPeer，**不** publish offer）
- end → `CommandCallController.onCallEnd`
- HTTP poll：`/v1/command-call/device/{id}/poll`，与 webrtc poll 并行调度（可共用 5s tick 或同 runnable 内再请求）

- [ ] **Step 1: Parser unit tests**

- [ ] **Step 2: Wire router + SessionManager + ApiClient**

- [ ] **Step 3: Run Android unit tests inline — PASS**

---

### Task 6: Verify full skeleton acceptance

**Acceptance script (automated):**

1. Backend: `pytest tests/test_command_call_skeleton.py -v` → all green
2. Android: `.\gradlew.bat :app:runUnitTestsInline` includes CommandCallController + Fake + parser tests → green
3. Manual mental checklist:
   - 平台 start 返回 UserSig，响应中无 SecretKey
   - Fake MQTT 收到 start
   - 设备 poll/路由 → Fake 进房
   - status `in_call`
   - end → MQTT end + Fake leave + status `ended`

- [ ] **Step 1: Run both suites**
- [ ] **Step 2: Fix any failures**
- [ ] **Step 3: Mark plan tasks complete in summary to user**

**Out of this Issue:** 真 TRTC SDK、连线共摄帧、连线对讲 PTT、Web 控制台 UI、打断 AI（可留 hook 注释，不强制实现）。
