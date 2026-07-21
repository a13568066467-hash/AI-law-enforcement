"""指挥连线会话：建房、签发 UserSig、下发连线信令、HTTP 兜底 poll、超时/离线失败。"""
from __future__ import annotations

import logging
import secrets
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable

from . import usersig
from .command_call_mqtt import (
    CommandCallMqttPublisher,
    LoggingCommandCallMqttPublisher,
)

_log = logging.getLogger(__name__)

_lock = threading.Lock()

# device_id → active call_id
_active_by_device: dict[str, str] = {}
# device_id → 待通知的 call_end（一次性）
_end_notify: dict[str, str] = {}
# call_id → session
_calls: dict[str, "CommandCallSession"] = {}

_mqtt: CommandCallMqttPublisher = LoggingCommandCallMqttPublisher()
_occupancy_checker: Callable[[str], bool] | None = None
_online_checker: Callable[[str], bool] | None = None
_clock: Callable[[], float] = time.time

# 设备未经 MQTT/HTTP 消费 start 的等待上限（秒）
RING_TIMEOUT_SECONDS = 45.0


@dataclass
class CommandCallSession:
    call_id: str
    device_id: str
    room_id: str
    caller: str = "指挥中心"
    # connecting | in_call | failed | ended
    status: str = "connecting"
    failure_reason: str = ""
    platform_user_id: str = ""
    platform_user_sig: str = ""
    device_user_id: str = ""
    device_user_sig: str = ""
    sdk_app_id: int = 0
    start_delivered: bool = False
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)

    def touch(self) -> None:
        self.updated_at = _clock()


def reset() -> None:
    global _mqtt, _occupancy_checker, _online_checker, _clock
    with _lock:
        _active_by_device.clear()
        _end_notify.clear()
        _calls.clear()
    _mqtt = LoggingCommandCallMqttPublisher()
    _occupancy_checker = None
    _online_checker = None
    _clock = time.time


def use_mqtt(publisher: CommandCallMqttPublisher) -> None:
    global _mqtt
    _mqtt = publisher


def use_occupancy_checker(checker: Callable[[str], bool]) -> None:
    global _occupancy_checker
    _occupancy_checker = checker


def use_online_checker(checker: Callable[[str], bool]) -> None:
    global _online_checker
    _online_checker = checker


def use_clock(clock: Callable[[], float]) -> None:
    global _clock
    _clock = clock


def _new_call_id() -> str:
    return f"cc-{secrets.token_hex(8)}"


def _default_is_occupied(device_id: str) -> bool:
    from . import device_bind_store, officer_db

    with officer_db._conn() as conn:
        return device_bind_store._get_occupancy_by_device(conn, device_id) is not None


def _is_occupied(device_id: str) -> bool:
    checker = _occupancy_checker if _occupancy_checker is not None else _default_is_occupied
    return bool(checker(device_id))


def _is_online(device_id: str) -> bool:
    # 未注入时默认在线（骨架/单测）；生产可 use_online_checker 接 last_seen
    if _online_checker is None:
        return True
    return bool(_online_checker(device_id))


def _creds_dict(user_id: str, user_sig: str, room_id: str, sdk_app_id: int) -> dict[str, Any]:
    return {
        "sdk_app_id": sdk_app_id,
        "room_id": room_id,
        "user_id": user_id,
        "user_sig": user_sig,
    }


def call_to_dict(session: CommandCallSession) -> dict[str, Any]:
    out: dict[str, Any] = {
        "call_id": session.call_id,
        "device_id": session.device_id,
        "room_id": session.room_id,
        "caller": session.caller,
        "status": session.status,
        "platform": _creds_dict(
            session.platform_user_id,
            session.platform_user_sig,
            session.room_id,
            session.sdk_app_id,
        ),
    }
    if session.failure_reason:
        out["failure_reason"] = session.failure_reason
    return out


def _safe_mqtt_start(device_id: str, payload: dict[str, Any]) -> None:
    try:
        _mqtt.publish_start(device_id, payload)
    except Exception as exc:  # noqa: BLE001 — 信令降级到 HTTP poll
        _log.warning("command_call MQTT start failed device=%s: %s", device_id, exc)


def _safe_mqtt_end(device_id: str, payload: dict[str, Any]) -> None:
    try:
        _mqtt.publish_end(device_id, payload)
    except Exception as exc:  # noqa: BLE001
        _log.warning("command_call MQTT end failed device=%s: %s", device_id, exc)


def _finalize_failed_locked(session: CommandCallSession, reason: str) -> None:
    """持锁：标记失败并排队 end 通知（半连接清理）。"""
    if session.status in ("ended", "failed"):
        return
    session.status = "failed"
    session.failure_reason = reason
    session.touch()
    device_id = session.device_id
    if _active_by_device.get(device_id) == session.call_id:
        _active_by_device.pop(device_id, None)
    _end_notify[device_id] = session.call_id
    _safe_mqtt_end(
        device_id,
        {"action": "call_end", "call_id": session.call_id},
    )


def start_command_call(device_id: str, caller: str = "指挥中心") -> dict[str, Any]:
    device_id = (device_id or "").strip()
    if not device_id:
        raise ValueError("device_id required")
    if not _is_occupied(device_id):
        raise ValueError("device not occupied")
    if not _is_online(device_id):
        raise ValueError("device offline")
    if not usersig.trtc_configured():
        raise RuntimeError("TRTC not configured: set TRTC_SDK_APP_ID and TRTC_SECRET_KEY")

    app_id = usersig.sdk_app_id()
    now = _clock()
    with _lock:
        old_id = _active_by_device.get(device_id)
        if old_id and old_id in _calls:
            old = _calls[old_id]
            if old.status not in ("ended", "failed"):
                old.status = "ended"
                old.touch()
                # 旧通话若从未下发过 start，勿占 end_notify，否则会拖慢新 call_start 一轮 poll
                if old.start_delivered:
                    _end_notify[device_id] = old_id
                    _safe_mqtt_end(
                        device_id,
                        {"action": "call_end", "call_id": old_id},
                    )

        call_id = _new_call_id()
        room_id = f"room-{call_id}"
        platform_user_id = f"platform-{call_id}"
        safe_device = "".join(ch if ch.isalnum() or ch in "-_" else "-" for ch in device_id)
        device_user_id = f"device-{safe_device}"
        platform_sig = usersig.issue_user_sig(platform_user_id)
        device_sig = usersig.issue_user_sig(device_user_id)

        session = CommandCallSession(
            call_id=call_id,
            device_id=device_id,
            room_id=room_id,
            caller=(caller or "指挥中心").strip() or "指挥中心",
            status="connecting",
            platform_user_id=platform_user_id,
            platform_user_sig=platform_sig,
            device_user_id=device_user_id,
            device_user_sig=device_sig,
            sdk_app_id=app_id,
            created_at=now,
            updated_at=now,
        )
        _calls[call_id] = session
        _active_by_device[device_id] = call_id

        start_payload = {
            "action": "call_start",
            "call_id": call_id,
            "caller": session.caller,
            "room_id": room_id,
            "sdk_app_id": app_id,
            "user_id": device_user_id,
            "user_sig": device_sig,
        }
        _safe_mqtt_start(device_id, start_payload)
        return call_to_dict(session)


def get_call(call_id: str) -> dict[str, Any]:
    sweep_timeouts()
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            raise KeyError("call not found")
        return call_to_dict(session)


def end_command_call(call_id: str) -> None:
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            return
        if session.status in ("ended", "failed"):
            return
        session.status = "ended"
        session.failure_reason = ""
        session.touch()
        device_id = session.device_id
        if _active_by_device.get(device_id) == call_id:
            _active_by_device.pop(device_id, None)
        _end_notify[device_id] = call_id
        _safe_mqtt_end(
            device_id,
            {"action": "call_end", "call_id": call_id},
        )


def sweep_timeouts(now: float | None = None) -> list[str]:
    """
    扫尾：connecting 且超过 RING_TIMEOUT 仍未 start_delivered → failed(timeout)。
    返回本次失败的 call_id 列表。
    """
    ts = _clock() if now is None else now
    failed_ids: list[str] = []
    with _lock:
        for call_id, session in list(_calls.items()):
            if session.status != "connecting":
                continue
            if session.start_delivered:
                continue
            if ts - session.created_at < RING_TIMEOUT_SECONDS:
                continue
            _finalize_failed_locked(session, "timeout")
            failed_ids.append(call_id)
    return failed_ids


def poll_device(device_id: str) -> dict[str, Any] | None:
    """HTTP 兜底：设备拉取待处理连线信令。消费开始后状态变为 in_call。"""
    device_id = (device_id or "").strip()
    with _lock:
        call_id = _active_by_device.get(device_id)
        session = _calls.get(call_id) if call_id else None
        needs_start = (
            session is not None
            and session.status not in ("ended", "failed")
            and not session.start_delivered
        )

        end_id = _end_notify.get(device_id)
        # 有待下发的新 start 时，丢弃「别的通话」的残留 end，避免多等一轮 15s
        if end_id and needs_start and end_id != call_id:
            _end_notify.pop(device_id, None)
            end_id = None
        if end_id:
            _end_notify.pop(device_id, None)
            return {"action": "call_end", "call_id": end_id}

        if not call_id:
            return None
        if session is None or session.status in ("ended", "failed"):
            _active_by_device.pop(device_id, None)
            return None
        if session.start_delivered:
            return None

        session.start_delivered = True
        session.status = "in_call"
        session.touch()
        return {
            "action": "call_start",
            "call_id": session.call_id,
            "caller": session.caller,
            "room_id": session.room_id,
            "sdk_app_id": session.sdk_app_id,
            "user_id": session.device_user_id,
            "user_sig": session.device_user_sig,
        }
