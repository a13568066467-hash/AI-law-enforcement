"""Device bind HTTP routes."""
from __future__ import annotations

from fastapi import APIRouter, Header, HTTPException

from app.device_bind import service as device_bind_store
from app.command_call import service as command_call_session
from app.task_room import service as task_room_session
from app.core.auth import require_auth_token
from app.device_bind.schemas import (
    DeviceBindConfirmReq,
    DeviceBindReleaseReq,
    DeviceBindTokenReq,
    RecorderCompanyReq,
)

router = APIRouter(tags=["device-bind"])


@router.post("/auth/device/bind/token")
def device_bind_token(req: DeviceBindTokenReq):
    """执法仪申请短期扫码绑定 token。"""
    result = device_bind_store.create_bind_token(req.device_id)
    if not result.get("ok"):
        raise HTTPException(400, result.get("message", "申请失败"))
    return result


@router.get("/auth/device/bind/status")
def device_bind_status(device_id: str, token: str):
    """执法仪轮询扫码绑定状态。"""
    return device_bind_store.get_bind_status(device_id, token)


@router.post("/auth/device/bind/confirm")
def device_bind_confirm(
    req: DeviceBindConfirmReq,
    authorization: str | None = Header(default=None),
):
    """手机 App 扫码确认绑定（须携带手机端 Bearer token）。"""
    mobile = require_auth_token(authorization)
    result = device_bind_store.confirm_bind(req.device_id, req.token, mobile)
    if not result.get("ok"):
        raise HTTPException(403, result.get("message", "绑定失败"))
    try:
        command_call_session.ensure_occupancy_room(req.device_id.strip())
    except Exception:  # noqa: BLE001
        pass
    return result


@router.post("/auth/device/bind/release")
def device_bind_release(req: DeviceBindReleaseReq):
    """解绑：结束设备当前占用，保留使用历史。"""
    result = device_bind_store.release_bind(req.device_id)
    if not result.get("ok"):
        raise HTTPException(400, result.get("message", "解绑失败"))
    command_call_session.release_occupancy_room(req.device_id.strip())
    task_room_session.remove_device_on_unbind(req.device_id.strip())
    return result


@router.post("/auth/device/bind/shutdown")
def device_bind_shutdown(req: DeviceBindReleaseReq):
    """关机：结束设备当前占用（reason=shutdown）。"""
    result = device_bind_store.shutdown_bind(req.device_id)
    if not result.get("ok"):
        raise HTTPException(400, result.get("message", "关机解绑失败"))
    command_call_session.release_occupancy_room(req.device_id.strip())
    task_room_session.remove_device_on_unbind(req.device_id.strip())
    return result


@router.patch("/v1/recorders/{device_id}/company")
def patch_recorder_company(device_id: str, req: RecorderCompanyReq):
    """管理后台：设备入库绑定公司。"""
    result = device_bind_store.set_recorder_company(device_id, req.company)
    if not result.get("ok"):
        raise HTTPException(400, result.get("message", "设置失败"))
    return result
