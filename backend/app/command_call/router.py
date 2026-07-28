"""Command-call / live-preview HTTP routes."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException

from app.command_call import service as command_call_session
from app.recorders import repository as recorder_db
from app.command_call.schemas import CommandCallStartReq, OccupancyRoomEnsureReq

router = APIRouter(tags=["command-call"])


@router.post("/v1/command-call/occupancy-room/ensure")
def command_call_occupancy_room_ensure(req: OccupancyRoomEnsureReq):
    try:
        return command_call_session.ensure_occupancy_room(req.device_id.strip())
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc


@router.get("/v1/command-call/device/{device_id}/occupancy-room")
def command_call_occupancy_room_get(device_id: str):
    info = command_call_session.get_occupancy_room(device_id.strip())
    if info is None:
        return {"room": None}
    return {"room": info}


@router.post("/v1/command-call/device/{device_id}/occupancy-room/ready")
def command_call_occupancy_room_ready(device_id: str):
    try:
        return command_call_session.mark_occupancy_room_ready(device_id.strip())
    except KeyError as exc:
        raise HTTPException(404, "occupancy room not found") from exc


@router.post("/v1/command-call/watch/start")
def command_call_watch_start(req: CommandCallStartReq):
    try:
        return command_call_session.start_watch(
            req.device_id.strip(),
            req.caller.strip() or "指挥中心",
        )
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc


@router.post("/v1/command-call/start")
def command_call_start(req: CommandCallStartReq):
    try:
        return command_call_session.start_command_call(
            req.device_id.strip(),
            req.caller.strip() or "指挥中心",
        )
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc


@router.get("/v1/command-call/device/{device_id}/poll")
def command_call_device_poll(device_id: str):
    did = device_id.strip()
    try:
        recorder_db.touch_recorder(did)
    except Exception:  # noqa: BLE001
        pass
    cmd = command_call_session.poll_device(did)
    return {"command": cmd}


@router.get("/v1/command-call/device/{device_id}/active")
def command_call_device_active(device_id: str):
    """调试：查看设备当前活跃监看/连线（含 start_delivered）。"""
    did = device_id.strip()
    info = command_call_session.get_active_for_device(did)
    if info is None:
        return {"active": None}
    return {"active": info}


@router.post("/v1/command-call/device/{device_id}/force-end")
def command_call_device_force_end(device_id: str):
    """调试/运维：结束该设备上未关闭的监看或连线。"""
    did = device_id.strip()
    ended = command_call_session.force_end_device(did)
    return {"ok": True, "ended_call_id": ended}


@router.get("/v1/command-call/{call_id}")
def command_call_status(call_id: str):
    try:
        return command_call_session.get_call(call_id)
    except KeyError as exc:
        raise HTTPException(404, "连线不存在") from exc


@router.post("/v1/command-call/{call_id}/end")
def command_call_end(call_id: str):
    command_call_session.end_command_call(call_id)
    return {"ok": True}


@router.post("/v1/command-call/{call_id}/watch/end")
def command_call_watch_end(call_id: str):
    command_call_session.end_watch(call_id)
    return {"ok": True}


@router.post("/v1/command-call/{call_id}/watch/heartbeat")
def command_call_watch_heartbeat(call_id: str):
    try:
        return command_call_session.touch_watch_heartbeat(call_id)
    except KeyError as exc:
        raise HTTPException(404, "监看不存在") from exc
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc


@router.post("/v1/command-call/{call_id}/upgrade")
def command_call_upgrade(call_id: str):
    try:
        return command_call_session.upgrade_watch_to_call(call_id)
    except KeyError as exc:
        raise HTTPException(404, "监看不存在") from exc
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
