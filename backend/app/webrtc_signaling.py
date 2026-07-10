"""V2 视频连线 — HTTP 信令中继（Web ↔ 设备，无需 Web 端直连 MQTT）。"""
from __future__ import annotations

import secrets
import threading
import time
from dataclasses import dataclass, field
from typing import Any

_lock = threading.Lock()

# device_id → 待处理呼叫
_pending_by_device: dict[str, str] = {}

# device_id → 待通知的 call_end（一次性）
_end_notify: dict[str, str] = {}

# call_id → CallSession
_calls: dict[str, "CallSession"] = {}


@dataclass
class CallSession:
    call_id: str
    device_id: str
    caller: str = "指挥中心"
    status: str = "pending"  # pending | signaling | connected | ended
    offer_sdp: str = ""
    answer_sdp: str = ""
    ice: list[dict[str, Any]] = field(default_factory=list)
    latest_frame_b64: str = ""
    latest_frame_at: float = 0.0
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)

    def touch(self) -> None:
        self.updated_at = time.time()


def _new_call_id() -> str:
    return f"call-{secrets.token_hex(8)}"


def start_call(device_id: str, caller: str = "指挥中心") -> CallSession:
    device_id = (device_id or "").strip()
    if not device_id:
        raise ValueError("device_id required")
    with _lock:
        # 结束同设备旧呼叫
        old_id = _pending_by_device.get(device_id)
        if old_id and old_id in _calls:
            _calls[old_id].status = "ended"
        call_id = _new_call_id()
        session = CallSession(call_id=call_id, device_id=device_id, caller=caller, status="pending")
        _calls[call_id] = session
        _pending_by_device[device_id] = call_id
        return session


def poll_device(device_id: str) -> dict[str, Any] | None:
    device_id = (device_id or "").strip()
    with _lock:
        end_id = _end_notify.pop(device_id, None)
        if end_id:
            return {"action": "call_end", "call_id": end_id}
        call_id = _pending_by_device.get(device_id)
        if not call_id:
            return None
        session = _calls.get(call_id)
        if session is None or session.status == "ended":
            _pending_by_device.pop(device_id, None)
            return None
        if session.status != "pending":
            return None
        session.status = "signaling"
        session.touch()
        return {
            "action": "call_start",
            "call_id": session.call_id,
            "caller": session.caller,
        }


def get_call(call_id: str) -> CallSession | None:
    with _lock:
        return _calls.get(call_id)


def post_offer(call_id: str, sdp: str) -> None:
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            raise KeyError("call not found")
        session.offer_sdp = sdp
        session.status = "signaling"
        session.touch()


def post_answer(call_id: str, sdp: str) -> None:
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            raise KeyError("call not found")
        session.answer_sdp = sdp
        session.status = "connected"
        session.touch()


def poll_answer(call_id: str) -> str | None:
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            return None
        sdp = session.answer_sdp
        return sdp if sdp else None


def add_ice(call_id: str, role: str, candidate: str, sdp_mid: str, sdp_mline_index: int) -> None:
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            raise KeyError("call not found")
        session.ice.append({
            "role": role,
            "candidate": candidate,
            "sdpMid": sdp_mid,
            "sdpMLineIndex": sdp_mline_index,
            "at": time.time(),
        })
        session.touch()


def list_ice(call_id: str, since: int = 0) -> list[dict[str, Any]]:
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            return []
        return session.ice[since:]


def post_frame(call_id: str, frame_b64: str) -> None:
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            raise KeyError("call not found")
        session.latest_frame_b64 = frame_b64
        session.latest_frame_at = time.time()
        if session.status == "signaling" and session.offer_sdp:
            session.status = "connected"
        session.touch()


def post_nal(call_id: str, nal_b64: str) -> None:
    """记录最近 NAL 上报时间（帧预览仍走 JPEG）。"""
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            raise KeyError("call not found")
        session.latest_frame_at = time.time()
        if session.status == "signaling" and session.offer_sdp:
            session.status = "connected"
        session.touch()


def get_frame(call_id: str, max_age_sec: float = 5.0) -> str | None:
    with _lock:
        session = _calls.get(call_id)
        if session is None or not session.latest_frame_b64:
            return None
        if time.time() - session.latest_frame_at > max_age_sec:
            return None
        return session.latest_frame_b64


def end_call(call_id: str) -> None:
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            return
        session.status = "ended"
        session.touch()
        _end_notify[session.device_id] = call_id
        if _pending_by_device.get(session.device_id) == call_id:
            _pending_by_device.pop(session.device_id, None)


def call_to_dict(session: CallSession) -> dict[str, Any]:
    return {
        "call_id": session.call_id,
        "device_id": session.device_id,
        "caller": session.caller,
        "status": session.status,
        "has_offer": bool(session.offer_sdp),
        "has_answer": bool(session.answer_sdp),
        "ice_count": len(session.ice),
        "has_frame": bool(session.latest_frame_b64),
        "frame_age_ms": int((time.time() - session.latest_frame_at) * 1000) if session.latest_frame_at else None,
        "updated_at": session.updated_at,
    }


def call_detail(call_id: str) -> dict[str, Any]:
    with _lock:
        session = _calls.get(call_id)
        if session is None:
            raise KeyError("call not found")
        data = call_to_dict(session)
        data["offer_sdp"] = session.offer_sdp
        data["answer_sdp"] = session.answer_sdp
        data["ice"] = list(session.ice)
        return data
