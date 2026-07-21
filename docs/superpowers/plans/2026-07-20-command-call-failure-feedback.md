# Command Call Failure Feedback (Issue 6) Implementation Plan

> **For agentic workers:** executing-plans / TDD. Checkboxes track progress.

**Goal:** 连线失败反馈——超时/离线明确失败；MQTT 不可达时 HTTP 兜底仍可用；半连接清理。

**Status:** Implemented 2026-07-20. Backend failures+skeleton pytest green; Android **73** unit tests OK.

**Architecture:** 会话增加 `connecting → in_call | failed | ended`；可注入 online/clock；MQTT 发布失败不阻断 start；`sweep_timeouts` 清理未送达；Android join 失败走统一 `failAndCleanup`。

**Tech Stack:** Python/FastAPI session、Kotlin CommandCallController/Fake、JUnit/pytest

## Global Constraints

- 设备仍不发送 answer/busy/hangup；送达以 HTTP poll 消费（`start_delivered`）为准。
- 依赖 Issue 2 骨架；不接真 TRTC/broker。
- 明确失败：`status=failed` + `failure_reason`（`timeout` | `offline` 在拒呼时不建会话）。

---

### Task 1: Backend — connecting/failed + offline + timeout sweep + MQTT resilient

**Files:** `command_call_session.py`, `command_call_mqtt.py`, `test_command_call_failures.py`, update skeleton test status expectations.

- start → `connecting`；poll 消费 start → `in_call`
- `use_online_checker`；离线 → `ValueError("device offline")`，不建房
- `use_clock` + `RING_TIMEOUT_SECONDS` + `sweep_timeouts()`：超时未 delivered → `failed`/`timeout` + end notify
- MQTT publish try/except；失败仍返回 session；poll 仍可取 start
- `call_to_dict` 含 `failure_reason`（可空）

### Task 2: Android — join fail cleanup

**Files:** Fake (`failNextJoin`), Controller (`failAndCleanup`, `lastFailureReason`), `CommandCallFailureCleanupTest.kt`, build.gradle

- join 失败 → Intercom.forceStop + CoCapture.unbind + leave + 清 activeCallId + lastFailure
- Fake: failNextJoin → state FAILED then cleanup → IDLE

### Task 3: Verify suites
