"""指挥连线 / 画面监看会话：建房、签发 UserSig、下发信令、HTTP 兜底 poll、超时/心跳清理。"""
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
# device_id → 待通知的 end（一次性）
_end_notify: dict[str, str] = {}
# device_id → 待通知的 upgrade（一次性）
_upgrade_notify: dict[str, str] = {}
# call_id → session
_calls: dict[str, "CommandCallSession"] = {}

_mqtt: CommandCallMqttPublisher = LoggingCommandCallMqttPublisher()
_occupancy_checker: Callable[[str], bool] | None = None
_online_checker: Callable[[str], bool] | None = None
_clock: Callable[[], float] = time.time

# 设备未经 MQTT/HTTP 消费 start 的等待上限（秒）
RING_TIMEOUT_SECONDS = 45.0
# 画面监看 Web 心跳超时（秒）
WATCH_HEARTBEAT_TIMEOUT_SECONDS = 30.0


@dataclass
class CommandCallSession:
    call_id: str
    device_id: str
    room_id: str
    caller: str = "指挥中心"
    # watch | call
    kind: str = "call"
    # connecting | watching | in_call | failed | ended
    status: str = "connecting"
    failure_reason: str = ""
    platform_user_id: str = ""
    platform_user_sig: str = ""
    device_user_id: str = ""
    device_user_sig: str = ""
    sdk_app_id: int = 0
    start_delivered: bool = False
    # 结束时用的 MQTT/poll action（watch_end | call_end）
    end_action: str = "call_end"
    last_heartbeat_at: float = 0.0
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)

    def touch(self) -> None:
        self.updated_at = _clock()


def reset() -> None:
    global _mqtt, _occupancy_checker, _online_checker, _clock
    with _lock:
        _active_by_device.clear()
        _end_notify.clear()
        _upgrade_notify.clear()
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
        "kind": session.kind,
        "status": session.status,
        "start_delivered": session.start_delivered,
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
    except Exception as exc:  # noqa: BLE001
        _log.warning("command_call MQTT start failed device=%s: %s", device_id, exc)


def _safe_mqtt_end(device_id: str, payload: dict[str, Any]) -> None:
    try:
        _mqtt.publish_end(device_id, payload)
    except Exception as exc:  # noqa: BLE001
        _log.warning("command_call MQTT end failed device=%s: %s", device_id, exc)


def _device_start_payload(session: CommandCallSession, action: str) -> dict[str, Any]:
    return {
        "action": action,
        "call_id": session.call_id,
        "caller": session.caller,
        "room_id": session.room_id,
        "sdk_app_id": session.sdk_app_id,
        "user_id": session.device_user_id,
        "user_sig": session.device_user_sig,
        "kind": session.kind,
    }


def _assert_device_eligible(device_id: str) -> str:
    device_id = (device_id or "").strip()
    if not device_id:
        raise ValueError("device_id required")
    if not _is_occupied(device_id):
        raise ValueError("device not occupied")
    if not _is_online(device_id):
        raise ValueError("device offline")
    if not usersig.trtc_configured():
        raise RuntimeError("TRTC not configured: set TRTC_SDK_APP_ID and TRTC_SECRET_KEY")
    return device_id


def _assert_device_free_locked(device_id: str) -> None:
    old_id = _active_by_device.get(device_id)
    if not old_id:
        return
    old = _calls.get(old_id)
    if old is not None and old.status not in ("ended", "failed"):
        raise ValueError("device busy")


def _create_session_locked(
    device_id: str,
    caller: str,
    kind: str,
    start_action: str,
) -> CommandCallSession:
    app_id = usersig.sdk_app_id()
    now = _clock()
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
        kind=kind,
        status="connecting",
        platform_user_id=platform_user_id,
        platform_user_sig=platform_sig,
        device_user_id=device_user_id,
        device_user_sig=device_sig,
        sdk_app_id=app_id,
        end_action="watch_end" if kind == "watch" else "call_end",
        last_heartbeat_at=now if kind == "watch" else 0.0,
        created_at=now,
        updated_at=now,
    )
    _calls[call_id] = session
    _active_by_device[device_id] = call_id
    _safe_mqtt_start(device_id, _device_start_payload(session, start_action))
    return session


def _finalize_end_locked(session: CommandCallSession, reason: str = "") -> None:
    if session.status in ("ended", "failed"):
        return
    session.status = "failed" if reason and reason == "timeout" else "ended"
    if reason == "timeout":
        session.status = "failed"
    session.failure_reason = reason
    session.touch()
    device_id = session.device_id
    if _active_by_device.get(device_id) == session.call_id:
        _active_by_device.pop(device_id, None)
    _upgrade_notify.pop(device_id, None)
    _end_notify[device_id] = session.call_id
    _safe_mqtt_end(
        device_id,
        {"action": session.end_action, "call_id": session.call_id},
    )


def _finalize_failed_locked(session: CommandCallSession, reason: str) -> None:
    _finalize_end_locked(session, reason=reason)


def start_watch(device_id: str, caller: str = "指挥中心") -> dict[str, Any]:
    device_id = _assert_device_eligible(device_id)
    with _lock:
        _assert_device_free_locked(device_id)
        session = _create_session_locked(device_id, caller, "watch", "watch_start")
        return call_to_dict(session)


def end_watch(call_id: str) -> None:
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            return
        if session.kind == "call":
            return
        _finalize_end_locked(session)


def touch_watch_heartbeat(call_id: str) -> dict[str, Any]:
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            raise KeyError("call not found")
        if session.kind != "watch" or session.status in ("ended", "failed"):
            raise ValueError("not an active watch")
        session.last_heartbeat_at = _clock()
        session.touch()
        return call_to_dict(session)


def upgrade_watch_to_call(call_id: str) -> dict[str, Any]:
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            raise KeyError("call not found")
        if session.kind != "watch" or session.status in ("ended", "failed"):
            raise ValueError("not an active watch")
        session.kind = "call"
        session.end_action = "call_end"
        session.status = "in_call" if session.start_delivered else "connecting"
        session.touch()
        payload = _device_start_payload(session, "call_upgrade")
        _safe_mqtt_start(session.device_id, payload)
        if session.start_delivered:
            _upgrade_notify[session.device_id] = session.call_id
        return call_to_dict(session)


def start_command_call(device_id: str, caller: str = "指挥中心") -> dict[str, Any]:
    device_id = _assert_device_eligible(device_id)
    with _lock:
        _assert_device_free_locked(device_id)
        session = _create_session_locked(device_id, caller, "call", "call_start")
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
        _finalize_end_locked(session)


def get_active_for_device(device_id: str) -> dict[str, Any] | None:
    device_id = (device_id or "").strip()
    with _lock:
        call_id = _active_by_device.get(device_id)
        if not call_id:
            return None
        session = _calls.get(call_id)
        if session is None or session.status in ("ended", "failed"):
            return None
        return call_to_dict(session)


def force_end_device(device_id: str) -> str | None:
    """结束设备上活跃会话；返回 ended call_id 或 None。"""
    device_id = (device_id or "").strip()
    with _lock:
        call_id = _active_by_device.get(device_id)
        if not call_id:
            return None
        session = _calls.get(call_id)
        if session is None:
            _active_by_device.pop(device_id, None)
            return call_id
        if session.status in ("ended", "failed"):
            _active_by_device.pop(device_id, None)
            return call_id
        _finalize_end_locked(session)
        return call_id


def sweep_timeouts(now: float | None = None) -> list[str]:
    """
    扫尾：
    - connecting 且超过 RING_TIMEOUT 仍未 start_delivered → failed(timeout)
    - watch 活跃且心跳超时 → ended（watch_end）
    """
    ts = _clock() if now is None else now
    failed_ids: list[str] = []
    with _lock:
        for call_id, session in list(_calls.items()):
            if session.status == "connecting" and not session.start_delivered:
                if ts - session.created_at >= RING_TIMEOUT_SECONDS:
                    _finalize_failed_locked(session, "timeout")
                    failed_ids.append(call_id)
                    continue
            if (
                session.kind == "watch"
                and session.status in ("connecting", "watching")
                and session.last_heartbeat_at > 0
                and ts - session.last_heartbeat_at >= WATCH_HEARTBEAT_TIMEOUT_SECONDS
            ):
                _finalize_end_locked(session, reason="heartbeat_timeout")
                failed_ids.append(call_id)
    return failed_ids


def poll_device(device_id: str) -> dict[str, Any] | None:
    """HTTP 兜底：设备拉取待处理监看/连线信令。"""
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
        if end_id and needs_start and end_id != call_id:
            _end_notify.pop(device_id, None)
            end_id = None
        if end_id:
            _end_notify.pop(device_id, None)
            ended = _calls.get(end_id)
            action = ended.end_action if ended else "call_end"
            return {"action": action, "call_id": end_id}

        upgrade_id = _upgrade_notify.get(device_id)
        if upgrade_id and session and upgrade_id == session.call_id:
            _upgrade_notify.pop(device_id, None)
            return _device_start_payload(session, "call_upgrade")

        if not call_id:
            return None
        if session is None or session.status in ("ended", "failed"):
            _active_by_device.pop(device_id, None)
            return None
        if session.start_delivered:
            return None

        session.start_delivered = True
        if session.kind == "watch":
            session.status = "watching"
            start_action = "watch_start"
        else:
            session.status = "in_call"
            start_action = "call_start"
        session.touch()
        return _device_start_payload(session, start_action)
