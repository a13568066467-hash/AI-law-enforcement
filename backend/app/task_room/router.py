"""公司任务房 HTTP 路由。"""
from __future__ import annotations

from fastapi import APIRouter, HTTPException

from app.task_room import service as task_room_session
from app.task_room.schemas import (
    TaskRoomAddDeviceReq,
    TaskRoomCreateReq,
    TaskRoomDeviceAckReq,
    TaskRoomHeartbeatReq,
    TaskRoomJoinSeatReq,
    TaskRoomLeaveSeatReq,
)

router = APIRouter(tags=["task-room"])


@router.post("/v1/task-rooms")
def task_rooms_create(req: TaskRoomCreateReq):
    try:
        return task_room_session.create_room(
            company=req.company.strip(),
            title=req.title,
            created_by=req.created_by,
        )
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc


@router.get("/v1/task-rooms")
def task_rooms_list(company: str = ""):
    try:
        return {"rooms": task_room_session.list_rooms(company)}
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc


@router.get("/v1/task-rooms/{room_id}")
def task_rooms_get(room_id: str):
    try:
        return task_room_session.get_room(room_id)
    except KeyError as exc:
        raise HTTPException(404, "task room not found") from exc


@router.post("/v1/task-rooms/{room_id}/devices")
def task_rooms_add_device(room_id: str, req: TaskRoomAddDeviceReq):
    try:
        return task_room_session.add_device(
            room_id,
            req.device_id.strip(),
            push_video=req.push_video,
        )
    except KeyError as exc:
        raise HTTPException(404, str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc


@router.delete("/v1/task-rooms/{room_id}/devices/{device_id}")
def task_rooms_remove_device(room_id: str, device_id: str):
    try:
        return task_room_session.remove_device(room_id, device_id)
    except KeyError as exc:
        raise HTTPException(404, str(exc)) from exc


@router.post("/v1/task-rooms/{room_id}/join")
def task_rooms_join(room_id: str, req: TaskRoomJoinSeatReq):
    try:
        return task_room_session.join_seat(
            room_id,
            display_name=req.display_name,
            employee_id=req.employee_id,
            kind=req.kind,
        )
    except KeyError as exc:
        raise HTTPException(404, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc


@router.post("/v1/task-rooms/leave")
def task_rooms_leave(req: TaskRoomLeaveSeatReq):
    try:
        return task_room_session.leave_seat(req.seat_session_id)
    except KeyError as exc:
        raise HTTPException(404, str(exc)) from exc


@router.post("/v1/task-rooms/heartbeat")
def task_rooms_heartbeat(req: TaskRoomHeartbeatReq):
    try:
        return task_room_session.heartbeat_seat(req.seat_session_id)
    except KeyError as exc:
        raise HTTPException(404, str(exc)) from exc


@router.post("/v1/task-rooms/{room_id}/close")
def task_rooms_close(room_id: str):
    try:
        return task_room_session.close_room(room_id)
    except KeyError as exc:
        raise HTTPException(404, str(exc)) from exc


@router.post("/v1/task-rooms/device-ack")
def task_rooms_device_ack(req: TaskRoomDeviceAckReq):
    return task_room_session.ack_device(req.device_id, req.action)
