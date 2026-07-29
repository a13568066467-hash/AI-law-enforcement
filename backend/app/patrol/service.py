"""Patrol auth / bind-status orchestration (router stays thin)."""
from __future__ import annotations

import secrets
from typing import Any

from app.officers import repository as officer_db
from app.patrol.store import (
    get_officer,
    login_officer,
    login_officer_by_device,
    offboard_officer,
    officer_exists,
    register_officer,
)
from app.patrol.verify import (
    consume_verify_token,
    peek_verify_token,
)
from app.recorders import repository as recorder_db


def build_patrol_status(*, phone: str, device_id: str) -> dict[str, Any]:
    """查询手机号/执法仪绑定状态。"""
    recorder_db.ensure_recorder(device_id.strip())
    row_device = officer_db.get_by_device(device_id)
    rec = recorder_db.get_recorder(device_id.strip())
    active_on_device = row_device is not None and officer_db.is_active_status(row_device.status)
    pending_on_device = (
        row_device is not None
        and officer_db.is_registering_status(row_device.status)
    )
    by_phone = get_officer(phone) if phone else None
    return {
        "registered": officer_exists(phone) if phone else False,
        "device_id": device_id,
        "device_bound": active_on_device,
        "device_pending": pending_on_device,
        "device_available": row_device is None,
        "bound_officer": (
            {
                "name": row_device.name,
                "employee_id": row_device.employee_id,
                "phone_tail": row_device.phone[-4:] if len(row_device.phone) >= 4 else "",
                "status": row_device.status,
                "status_label": officer_db.status_label(row_device.status),
            }
            if row_device
            else None
        ),
        "profile_in_db": row_device is not None,
        "phone_matches_device": (
            by_phone is not None
            and by_phone.device_id == device_id
            and officer_exists(phone)
        ),
        "binding_rule": (
            "一台执法仪同时仅绑定一名人员；换人须前任在岗人员先注销/离职。"
        ),
        "recorder": recorder_db.recorder_to_dict(rec),
    }


def face_auth(*, verify_token: str, device_id: str, face_image_base64: str, register: bool) -> dict[str, Any]:
    """步骤 3：人脸注册或登录。成功返回响应体，失败抛 ValueError(message)。"""
    session = peek_verify_token(verify_token)
    if session is None:
        raise ValueError("请先完成步骤1和步骤2验证")
    if session.device_id != device_id.strip():
        raise ValueError("设备与验证会话不一致")

    token = secrets.token_urlsafe(24)
    if register:
        ok, msg, record = register_officer(
            phone=session.phone,
            name=session.name,
            employee_id=session.employee_id,
            department=session.department,
            device_id=session.device_id,
            face_image_b64=face_image_base64,
            token=token,
            id_card=session.id_card,
            company=session.company,
            position=session.position,
            gender=session.gender,
        )
    else:
        ok, msg, record = login_officer(
            phone=session.phone,
            device_id=session.device_id,
            face_image_b64=face_image_base64,
            token=token,
        )
    if not ok or record is None:
        raise ValueError(msg)
    consume_verify_token(verify_token)
    return {
        "step": 3,
        "passed": True,
        "token": token,
        "phone": record.phone,
        "name": record.name,
        "employee_id": record.employee_id,
        "department": record.department,
        "device_id": record.device_id,
        "message": msg,
    }


def face_only_login(*, device_id: str, face_image_base64: str) -> dict[str, Any]:
    token = secrets.token_urlsafe(24)
    ok, msg, record = login_officer_by_device(
        device_id=device_id,
        face_image_b64=face_image_base64,
        token=token,
    )
    if not ok or record is None:
        raise ValueError(msg)
    return {
        "step": "face_login",
        "passed": True,
        "token": token,
        "phone": record.phone,
        "name": record.name,
        "employee_id": record.employee_id,
        "department": record.department,
        "device_id": record.device_id,
        "message": msg,
    }


def offboard(*, device_id: str, authorization_token: str) -> dict[str, Any]:
    employee_id = officer_db.get_employee_id_by_token(authorization_token) if authorization_token else ""
    ok, msg, record = offboard_officer(
        device_id=device_id,
        employee_id=employee_id or "",
        token=authorization_token,
    )
    if not ok or record is None:
        raise ValueError(msg)
    return {
        "ok": True,
        "status": record.status,
        "status_label": officer_db.status_label(record.status),
        "gender": record.gender,
        "employee_id": record.employee_id,
        "name": record.name,
        "message": msg,
    }
