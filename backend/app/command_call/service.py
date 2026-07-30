"""指挥连线 / 画面监看会话：占用持房、签发 UserSig、业务会话信令、HTTP 兜底 poll、超时/心跳清理。"""
from __future__ import annotations

import logging
import secrets
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable

from app.command_call import usersig
from app.command_call.mqtt import (
    CommandCallMqttPublisher,
    LoggingCommandCallMqttPublisher,
)

_log = logging.getLogger(__name__)

_lock = threading.Lock()

# device_id → active call_id（监看/连线业务会话）
_active_by_device: dict[str, str] = {}
# device_id → 待通知的 end（一次性）
_end_notify: dict[str, str] = {}
# device_id → 待通知的 upgrade（一次性）
_upgrade_notify: dict[str, str] = {}
# device_id → 待通知的占用房释放（一次性）
_room_release_notify: dict[str, str] = {}
# call_id → session
_calls: dict[str, "CommandCallSession"] = {}
# device_id → 占用侧连线房间
_rooms: dict[str, "OccupancyRoom"] = {}

_mqtt: CommandCallMqttPublisher = LoggingCommandCallMqttPublisher()
_occupancy_checker: Callable[[str], bool] | None = None
_online_checker: Callable[[str], bool] | None = None
_clock: Callable[[], float] = time.time

# 设备未经 MQTT/HTTP 消费 start 的等待上限（秒）
RING_TIMEOUT_SECONDS = 45.0
# 画面监看 Web 心跳超时（秒）
WATCH_HEARTBEAT_TIMEOUT_SECONDS = 30.0


@dataclass
class OccupancyRoom:
    device_id: str
    room_id: str
    device_user_id: str
    device_user_sig: str
    sdk_app_id: int
    ready: bool = False
    join_delivered: bool = False
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)

    def touch(self) -> None:
        self.updated_at = _clock()


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
        _room_release_notify.clear()
        _calls.clear()
        _rooms.clear()
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
    from app.device_bind import repository as device_bind_repo
    from app.officers import repository as officer_db

    with officer_db._conn() as conn:
        return device_bind_repo.get_occupancy_by_device(conn, device_id) is not None


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


def _new_room_id() -> str:
    return f"room-occ-{secrets.token_hex(8)}"


def _device_user_id_for(device_id: str) -> str:
    safe_device = "".join(ch if ch.isalnum() or ch in "-_" else "-" for ch in device_id)
    return f"device-{safe_device}"


def _occupancy_room_to_dict(room: OccupancyRoom) -> dict[str, Any]:
    return {
        "device_id": room.device_id,
        "room_id": room.room_id,
        "room_ready": room.ready,
        "device": _creds_dict(
            room.device_user_id,
            room.device_user_sig,
            room.room_id,
            room.sdk_app_id,
        ),
    }


def _occupy_room_payload(room: OccupancyRoom) -> dict[str, Any]:
    return {
        "action": "occupy_room",
        "room_id": room.room_id,
        "sdk_app_id": room.sdk_app_id,
        "user_id": room.device_user_id,
        "user_sig": room.device_user_sig,
    }


def _assert_device_free_locked(device_id: str) -> None:
    old_id = _active_by_device.get(device_id)
    if not old_id:
        return
    old = _calls.get(old_id)
    if old is not None and old.status not in ("ended", "failed"):
        raise ValueError("device busy")


def _require_ready_room_locked(device_id: str) -> OccupancyRoom:
    room = _rooms.get(device_id)
    if room is None or not room.ready:
        raise ValueError("room not ready")
    return room


def _create_session_on_room_locked(
    device_id: str,
    caller: str,
    kind: str,
    start_action: str,
    room: OccupancyRoom,
) -> CommandCallSession:
    now = _clock()
    call_id = _new_call_id()
    platform_user_id = f"platform-{call_id}"
    platform_sig = usersig.issue_user_sig(platform_user_id)

    session = CommandCallSession(
        call_id=call_id,
        device_id=device_id,
        room_id=room.room_id,
        caller=(caller or "指挥中心").strip() or "指挥中心",
        kind=kind,
        status="connecting",
        platform_user_id=platform_user_id,
        platform_user_sig=platform_sig,
        device_user_id=room.device_user_id,
        device_user_sig=room.device_user_sig,
        sdk_app_id=room.sdk_app_id,
        end_action="watch_end" if kind == "watch" else "call_end",
        last_heartbeat_at=now if kind == "watch" else 0.0,
        created_at=now,
        updated_at=now,
    )
    _calls[call_id] = session
    _active_by_device[device_id] = call_id
    _safe_mqtt_start(device_id, _device_start_payload(session, start_action))
    return session


def ensure_occupancy_room(device_id: str) -> dict[str, Any]:
    """占用侧持房：已占用设备签发/返回连线房间（未就绪亦可返回）。"""
    device_id = (device_id or "").strip()
    if not device_id:
        raise ValueError("device_id required")
    if not _is_occupied(device_id):
        raise ValueError("device not occupied")
    if not usersig.trtc_configured():
        raise RuntimeError("TRTC not configured: set TRTC_SDK_APP_ID and TRTC_SECRET_KEY")
    with _lock:
        existing = _rooms.get(device_id)
        if existing is not None:
            return _occupancy_room_to_dict(existing)
        now = _clock()
        room_id = _new_room_id()
        device_user_id = _device_user_id_for(device_id)
        room = OccupancyRoom(
            device_id=device_id,
            room_id=room_id,
            device_user_id=device_user_id,
            device_user_sig=usersig.issue_user_sig(device_user_id),
            sdk_app_id=usersig.sdk_app_id(),
            ready=False,
            join_delivered=False,
            created_at=now,
            updated_at=now,
        )
        _rooms[device_id] = room
        _safe_mqtt_start(device_id, _occupy_room_payload(room))
        return _occupancy_room_to_dict(room)


def get_occupancy_room(device_id: str) -> dict[str, Any] | None:
    device_id = (device_id or "").strip()
    with _lock:
        room = _rooms.get(device_id)
        if room is None:
            return None
        return _occupancy_room_to_dict(room)


def mark_occupancy_room_ready(device_id: str) -> dict[str, Any]:
    device_id = (device_id or "").strip()
    with _lock:
        room = _rooms.get(device_id)
        if room is None:
            raise KeyError("occupancy room not found")
        room.ready = True
        room.join_delivered = True
        room.touch()
        return _occupancy_room_to_dict(room)


def release_occupancy_room(device_id: str) -> None:
    """解绑/占用结束：结束业务会话并作废占用房间。"""
    device_id = (device_id or "").strip()
    with _lock:
        call_id = _active_by_device.get(device_id)
        if call_id:
            session = _calls.get(call_id)
            if session is not None and session.status not in ("ended", "failed"):
                _finalize_end_locked(session)
        room = _rooms.pop(device_id, None)
        if room is not None:
            _room_release_notify[device_id] = room.room_id
            _safe_mqtt_end(
                device_id,
                {"action": "occupy_room_end", "room_id": room.room_id},
            )


def _finalize_end_locked(session: CommandCallSession, reason: str = "") -> None:
    if session.status in ("ended", "failed"):
        return
    session.status = "ended"
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
        room = _require_ready_room_locked(device_id)
        _assert_device_free_locked(device_id)
        session = _create_session_on_room_locked(
            device_id, caller, "watch", "watch_start", room
        )
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
        room = _require_ready_room_locked(device_id)
        _assert_device_free_locked(device_id)
        session = _create_session_on_room_locked(
            device_id, caller, "call", "call_start", room
        )
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


def ack_device_start(call_id: str, device_id: str) -> dict[str, Any]:
    """设备经 MQTT（或本地处理后）确认已消费 start，置 start_delivered。"""
    call_id = (call_id or "").strip()
    device_id = (device_id or "").strip()
    if not call_id or not device_id:
        raise ValueError("call_id and device_id required")
    with _lock:
        session = _calls.get(call_id)
        if session is None or session.status in ("ended", "failed"):
            raise KeyError(call_id)
        if session.device_id != device_id:
            raise ValueError("device_id mismatch")
        if not session.start_delivered:
            session.start_delivered = True
            if session.kind == "watch":
                session.status = "watching"
            else:
                session.status = "in_call"
            session.touch()
        return call_to_dict(session)


def ack_occupancy_join(device_id: str) -> dict[str, Any]:
    """设备确认已消费 occupy_room 并进房。"""
    device_id = (device_id or "").strip()
    if not device_id:
        raise ValueError("device_id required")
    with _lock:
        room = _rooms.get(device_id)
        if room is None:
            raise KeyError(device_id)
        if not room.join_delivered:
            room.join_delivered = True
            room.touch()
        return _occupancy_room_to_dict(room)


def poll_device(device_id: str) -> dict[str, Any] | None:
    """HTTP 兜底：设备拉取占用进房 / 监看推流 / 连线信令。"""
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

        release_room_id = _room_release_notify.get(device_id)
        if release_room_id and not needs_start:
            _room_release_notify.pop(device_id, None)
            return {"action": "occupy_room_end", "room_id": release_room_id}

        upgrade_id = _upgrade_notify.get(device_id)
        if upgrade_id and session and upgrade_id == session.call_id:
            _upgrade_notify.pop(device_id, None)
            return _device_start_payload(session, "call_upgrade")

        if call_id and session is not None and session.status not in ("ended", "failed"):
            if not session.start_delivered:
                session.start_delivered = True
                if session.kind == "watch":
                    session.status = "watching"
                    start_action = "watch_start"
                else:
                    session.status = "in_call"
                    start_action = "call_start"
                session.touch()
                return _device_start_payload(session, start_action)
            return None

        if call_id and (session is None or session.status in ("ended", "failed")):
            _active_by_device.pop(device_id, None)

        room = _rooms.get(device_id)
        if room is not None and not room.join_delivered:
            room.join_delivered = True
            room.touch()
            return _occupy_room_payload(room)
        return None
