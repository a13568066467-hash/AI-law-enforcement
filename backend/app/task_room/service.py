"""公司任务房：多设备 + 多座席同 TRTC 房。"""
from __future__ import annotations

import logging
import secrets
import threading
import time
from typing import Any, Callable

from app.command_call import usersig
from app.command_call.mqtt import (
    CommandCallMqttPublisher,
    LoggingCommandCallMqttPublisher,
)
from app.device_bind import repository as device_bind_repo
from app.db.connection import _conn
from app.recorders import repository as recorder_db
from app.task_room import repository as repo

_log = logging.getLogger(__name__)

_lock = threading.Lock()
# device_id → pending one-shot signaling payload (join/leave)
_device_pending: dict[str, dict[str, Any]] = {}
# device_id → last issued device creds for task room (for poll redelivery)
_device_creds: dict[str, dict[str, Any]] = {}

_mqtt: CommandCallMqttPublisher = LoggingCommandCallMqttPublisher()
_online_checker: Callable[[str], bool] | None = None
_clock: Callable[[], float] = time.time

SEAT_HEARTBEAT_TIMEOUT_SECONDS = 45.0


def reset() -> None:
    global _mqtt, _online_checker, _clock
    with _lock:
        _device_pending.clear()
        _device_creds.clear()
    _mqtt = LoggingCommandCallMqttPublisher()
    _online_checker = None
    _clock = time.time


def use_mqtt(publisher: CommandCallMqttPublisher) -> None:
    global _mqtt
    _mqtt = publisher


def use_online_checker(checker: Callable[[str], bool]) -> None:
    global _online_checker
    _online_checker = checker


def use_clock(clock: Callable[[], float]) -> None:
    global _clock
    _clock = clock


def _sanitize_user_id(raw: str) -> str:
    out = []
    for ch in (raw or "").strip():
        if ch.isalnum() or ch in ("-", "_"):
            out.append(ch)
        else:
            out.append("_")
    return "".join(out)[:48] or "user"


def _device_company(device_id: str) -> str:
    return (recorder_db.get_recorder_company(device_id) or "").strip()


def _is_occupied(device_id: str) -> bool:
    with _conn() as conn:
        return device_bind_repo.get_occupancy_by_device(conn, device_id) is not None


def _is_online(device_id: str) -> bool:
    if _online_checker is None:
        return True
    try:
        return bool(_online_checker(device_id))
    except Exception:  # noqa: BLE001
        return False


def _queue_device_signal(device_id: str, payload: dict[str, Any]) -> None:
    with _lock:
        _device_pending[device_id] = dict(payload)
    action = str(payload.get("action") or "")
    try:
        if action == "task_room_leave":
            _mqtt.publish_end(device_id, payload)
        else:
            _mqtt.publish_start(device_id, payload)
    except Exception:  # noqa: BLE001
        _log.exception("task_room mqtt publish failed device=%s action=%s", device_id, action)


def _device_join_payload(
    *,
    device_id: str,
    room: dict[str, str],
    device_user_id: str,
    device_user_sig: str,
    sdk: int,
    push_video: bool,
) -> dict[str, Any]:
    return {
        "action": "task_room_join",
        "task_room_id": room["id"],
        "room_id": room["trtc_room_id"],
        "device_id": device_id,
        "push_video": push_video,
        "sdk_app_id": sdk,
        "user_id": device_user_id,
        "user_sig": device_user_sig,
        "device": {
            "sdk_app_id": sdk,
            "room_id": room["trtc_room_id"],
            "user_id": device_user_id,
            "user_sig": device_user_sig,
        },
    }


def create_room(
    *,
    company: str,
    title: str = "",
    created_by: str = "",
) -> dict[str, Any]:
    company_s = company.strip()
    if not company_s:
        raise ValueError("company required")
    if not usersig.trtc_configured():
        raise RuntimeError("TRTC not configured")
    room_pk = f"task-{secrets.token_hex(8)}"
    trtc_room_id = f"room-task-{secrets.token_hex(8)}"
    title_s = (title or "").strip() or f"任务房 {trtc_room_id[-6:]}"
    repo.insert_room(
        room_pk=room_pk,
        company=company_s,
        trtc_room_id=trtc_room_id,
        title=title_s,
        created_by=(created_by or "").strip(),
    )
    return _room_public(repo.get_room(room_pk) or {"id": room_pk})


def list_rooms(company: str) -> list[dict[str, Any]]:
    company_s = company.strip()
    if not company_s:
        raise ValueError("company required")
    return [_room_public(r) for r in repo.list_open_rooms(company_s)]


def get_room(room_id: str) -> dict[str, Any]:
    room = repo.get_room(room_id.strip())
    if room is None or room.get("status") != "open":
        raise KeyError("task room not found")
    return _room_public(room)


def _room_public(room: dict[str, str]) -> dict[str, Any]:
    rid = room["id"]
    return {
        "id": rid,
        "company": room["company"],
        "trtc_room_id": room["trtc_room_id"],
        "title": room.get("title") or "",
        "status": room.get("status") or "open",
        "created_by": room.get("created_by") or "",
        "created_at": room.get("created_at") or "",
        "devices": repo.list_devices(rid),
        "seats": repo.list_seats(rid),
    }


def add_device(room_id: str, device_id: str, *, push_video: bool = True) -> dict[str, Any]:
    room = repo.get_room(room_id.strip())
    if room is None or room.get("status") != "open":
        raise KeyError("task room not found")
    did = device_id.strip()
    if not did:
        raise ValueError("device_id required")
    company = _device_company(did)
    if not company:
        raise ValueError("device has no company")
    if company != room["company"]:
        raise ValueError("device company mismatch")
    if not _is_occupied(did):
        raise ValueError("device not occupied")
    if not _is_online(did):
        raise ValueError("device offline")
    existing = repo.find_room_id_for_device(did)
    if existing and existing != room["id"]:
        raise ValueError("device already in another task room")
    if existing == room["id"]:
        return _room_public(room)

    if not usersig.trtc_configured():
        raise RuntimeError("TRTC not configured")
    sdk = usersig.sdk_app_id()
    device_user_id = f"device-{_sanitize_user_id(did)}"
    device_user_sig = usersig.issue_user_sig(device_user_id)
    repo.add_device(room["id"], did)
    payload = _device_join_payload(
        device_id=did,
        room=room,
        device_user_id=device_user_id,
        device_user_sig=device_user_sig,
        sdk=sdk,
        push_video=push_video,
    )
    with _lock:
        _device_creds[did] = dict(payload)
    _queue_device_signal(did, payload)
    return _room_public(repo.get_room(room["id"]) or room)


def remove_device(room_id: str, device_id: str) -> dict[str, Any]:
    room = repo.get_room(room_id.strip())
    if room is None:
        raise KeyError("task room not found")
    did = device_id.strip()
    if not repo.remove_device(room["id"], did):
        raise KeyError("device not in room")
    payload = {
        "action": "task_room_leave",
        "task_room_id": room["id"],
        "room_id": room["trtc_room_id"],
        "device_id": did,
    }
    with _lock:
        _device_creds.pop(did, None)
    _queue_device_signal(did, payload)
    return _room_public(repo.get_room(room["id"]) or room)


def remove_device_on_unbind(device_id: str) -> None:
    did = device_id.strip()
    room_id = repo.remove_device_from_any(did)
    if not room_id:
        return
    room = repo.get_room(room_id)
    trtc_room_id = (room or {}).get("trtc_room_id") or ""
    payload = {
        "action": "task_room_leave",
        "task_room_id": room_id,
        "room_id": trtc_room_id,
        "device_id": did,
    }
    with _lock:
        _device_creds.pop(did, None)
    _queue_device_signal(did, payload)
    # 无成员则关房
    if room and not repo.list_devices(room_id) and not repo.list_seats(room_id):
        repo.close_room(room_id)


def join_seat(
    room_id: str,
    *,
    display_name: str = "指挥座席",
    employee_id: str = "",
    kind: str = "watch",
) -> dict[str, Any]:
    room = repo.get_room(room_id.strip())
    if room is None or room.get("status") != "open":
        raise KeyError("task room not found")
    if not usersig.trtc_configured():
        raise RuntimeError("TRTC not configured")
    sdk = usersig.sdk_app_id()
    seat_session_id = f"seat-{secrets.token_hex(8)}"
    emp = _sanitize_user_id(employee_id) or secrets.token_hex(4)
    seat_user_id = f"seat-{emp}-{seat_session_id[-6:]}"
    seat_user_sig = usersig.issue_user_sig(seat_user_id)
    name = (display_name or "").strip() or "指挥座席"
    repo.add_seat(
        room_pk=room["id"],
        seat_session_id=seat_session_id,
        employee_id=employee_id.strip(),
        display_name=name,
        role="seat",
    )
    # 有座席进房时，提醒房内设备推流
    for did in repo.list_devices(room["id"]):
        with _lock:
            cred = _device_creds.get(did)
        if cred:
            push_payload = dict(cred)
            push_payload["push_video"] = True
            _queue_device_signal(did, push_payload)

    kind_s = "call" if kind.strip() == "call" else "watch"
    return {
        "ok": True,
        "task_room_id": room["id"],
        "call_id": seat_session_id,
        "device_id": "",
        "room_id": room["trtc_room_id"],
        "caller": name,
        "kind": kind_s,
        "status": "watching" if kind_s == "watch" else "in_call",
        "platform": {
            "sdk_app_id": sdk,
            "room_id": room["trtc_room_id"],
            "user_id": seat_user_id,
            "user_sig": seat_user_sig,
        },
        "seat_session_id": seat_session_id,
        "devices": repo.list_devices(room["id"]),
    }


def leave_seat(seat_session_id: str) -> dict[str, Any]:
    sid = seat_session_id.strip()
    room_id = repo.remove_seat(sid)
    if not room_id:
        raise KeyError("seat not found")
    room = repo.get_room(room_id)
    if room and not repo.list_devices(room_id) and not repo.list_seats(room_id):
        repo.close_room(room_id)
        return {"ok": True, "closed": True, "task_room_id": room_id}
    return {"ok": True, "closed": False, "task_room_id": room_id}


def heartbeat_seat(seat_session_id: str) -> dict[str, Any]:
    sid = seat_session_id.strip()
    if not repo.heartbeat_seat(sid):
        raise KeyError("seat not found")
    seat = repo.get_seat(sid)
    assert seat is not None
    room = repo.get_room(seat["room_id"])
    if room is None or room.get("status") != "open":
        raise KeyError("task room not found")
    return {
        "ok": True,
        "task_room_id": room["id"],
        "call_id": sid,
        "room_id": room["trtc_room_id"],
        "devices": repo.list_devices(room["id"]),
        "seats": repo.list_seats(room["id"]),
    }


def close_room(room_id: str) -> dict[str, Any]:
    room = repo.get_room(room_id.strip())
    if room is None:
        raise KeyError("task room not found")
    for did in list(repo.list_devices(room["id"])):
        try:
            remove_device(room["id"], did)
        except Exception:  # noqa: BLE001
            _log.exception("close_room remove device %s", did)
    repo.close_room(room["id"])
    return {"ok": True, "task_room_id": room["id"]}


def poll_device(device_id: str) -> dict[str, Any] | None:
    """HTTP 兜底：取出待下发的 task_room 信令。"""
    did = device_id.strip()
    with _lock:
        payload = _device_pending.pop(did, None)
    return payload


def ack_device(device_id: str, action: str = "") -> dict[str, Any]:
    did = device_id.strip()
    with _lock:
        pending = _device_pending.get(did)
        if pending and (not action or pending.get("action") == action):
            _device_pending.pop(did, None)
    return {"ok": True}


def watch_device_via_task_room(
    device_id: str,
    *,
    caller: str = "指挥中心",
    company: str = "",
    kind: str = "watch",
) -> dict[str, Any]:
    """
    单设备点选监看/连线：自动建临时任务房（若设备已在房则加入该房座席）。
    返回形态兼容旧 CallSession（含 platform / call_id）。
    """
    did = device_id.strip()
    if not did:
        raise ValueError("device_id required")
    device_company = _device_company(did)
    if not device_company:
        raise ValueError("device has no company")
    company_s = (company or "").strip() or device_company
    if company_s != device_company:
        raise ValueError("company mismatch")

    existing_room_id = repo.find_room_id_for_device(did)
    if existing_room_id:
        room = repo.get_room(existing_room_id)
        if room is None or room.get("status") != "open":
            raise ValueError("device task room unavailable")
    else:
        created = create_room(
            company=company_s,
            title=f"监看 {did[-8:]}",
            created_by=caller,
        )
        add_device(created["id"], did, push_video=True)
        existing_room_id = created["id"]

    joined = join_seat(
        existing_room_id,
        display_name=caller,
        employee_id="",
        kind=kind,
    )
    joined["device_id"] = did
    return joined
