"""Historical patrol auth HTTP routes."""
from __future__ import annotations

import secrets

from fastapi import APIRouter, Header, HTTPException

from app.officers import repository as officer_db
from app.recorders import repository as recorder_db
from app.core.auth import optional_bearer_token
from app.patrol.schemas import (
    FaceOnlyLoginReq,
    OffboardReq,
    PatrolAuthReq,
    ProfileOrgReq,
    ProfileStepReq,
    SmsSendReq,
    SmsVerifyReq,
)
from app.patrol.store import (
    get_officer,
    login_officer,
    login_officer_by_device,
    offboard_officer,
    officer_exists,
    register_officer,
)
from app.patrol.verify import (
    complete_profile_org,
    consume_verify_token,
    get_session as get_verify_session,
    peek_verify_token,
    send_sms_code,
    verify_profile,
    verify_sms_code,
)

router = APIRouter(tags=["patrol"])


@router.get("/auth/patrol/status")
def patrol_status(phone: str, device_id: str):
    """查询手机号/执法仪绑定状态（读云端 SQLite 库）。"""
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


@router.get("/auth/patrol/employee-id/new")
def patrol_new_employee_id():
    """生成唯一工号（客户端注册向导使用）。"""
    try:
        eid = officer_db.generate_employee_id()
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc
    return {"employee_id": eid}


@router.post("/auth/patrol/step1/profile")
def patrol_step1_profile(req: ProfileStepReq):
    """步骤 1：人员信息验证。"""
    ok, msg, session_id = verify_profile(
        name=req.name,
        gender=req.gender,
        employee_id=req.employee_id,
        department=req.department,
        device_id=req.device_id,
        id_card=req.id_card,
        company=req.company,
        position=req.position,
    )
    if not ok or not session_id:
        raise HTTPException(400, msg)
    return {
        "step": 1,
        "passed": True,
        "session_id": session_id,
        "message": msg,
    }


@router.post("/auth/patrol/step1/org")
def patrol_step1_org(req: ProfileOrgReq):
    """注册向导：补充公司/部门/职位后允许人脸验证。"""
    ok, msg = complete_profile_org(
        req.session_id,
        company=req.company,
        department=req.department,
        position=req.position,
    )
    if not ok:
        raise HTTPException(400, msg)
    return {"step": "org", "passed": True, "message": msg}


@router.post("/auth/patrol/step2/sms/send")
def patrol_step2_sms_send(req: SmsSendReq):
    """步骤 2a：发送手机验证码。"""
    ok, msg, dev_code = send_sms_code(req.session_id, req.phone)
    if not ok:
        raise HTTPException(400, msg)
    return {
        "step": 2,
        "message": msg,
        "dev_code": dev_code,
    }


@router.post("/auth/patrol/step2/sms/verify")
def patrol_step2_sms_verify(req: SmsVerifyReq):
    """步骤 2b：校验手机验证码。"""
    ok, msg, verify_token = verify_sms_code(req.session_id, req.phone, req.code)
    if not ok or not verify_token:
        raise HTTPException(400, msg)
    session = get_verify_session(req.session_id)
    return {
        "step": 2,
        "passed": True,
        "verify_token": verify_token,
        "phone": session.phone if session else req.phone,
        "message": msg,
    }


def _patrol_face_auth(req: PatrolAuthReq, *, register: bool):
    session = peek_verify_token(req.verify_token)
    if session is None:
        raise HTTPException(403, "请先完成步骤1和步骤2验证")
    if session.device_id != req.device_id.strip():
        raise HTTPException(403, "设备与验证会话不一致")

    token = secrets.token_urlsafe(24)
    if register:
        ok, msg, record = register_officer(
            phone=session.phone,
            name=session.name,
            employee_id=session.employee_id,
            department=session.department,
            device_id=session.device_id,
            face_image_b64=req.face_image_base64,
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
            face_image_b64=req.face_image_base64,
            token=token,
        )
    if not ok or record is None:
        raise HTTPException(403, msg)
    consume_verify_token(req.verify_token)
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


@router.post("/auth/patrol/register")
def patrol_register(req: PatrolAuthReq):
    return _patrol_face_auth(req, register=True)


@router.post("/auth/patrol/login")
def patrol_login(req: PatrolAuthReq):
    return _patrol_face_auth(req, register=False)


@router.post("/auth/patrol/face-only-login")
def patrol_face_only_login(req: FaceOnlyLoginReq):
    """已注册执法仪：后续登录仅扫脸，与云端库人脸模板比对。"""
    token = secrets.token_urlsafe(24)
    ok, msg, record = login_officer_by_device(
        device_id=req.device_id,
        face_image_b64=req.face_image_base64,
        token=token,
    )
    if not ok or record is None:
        raise HTTPException(403, msg)
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


@router.post("/auth/patrol/offboard")
def patrol_offboard(req: OffboardReq, authorization: str | None = Header(default=None)):
    """执法仪端注销在岗巡查员：设备解绑，云端档案标记离职。"""
    token = optional_bearer_token(authorization)
    employee_id = officer_db.get_employee_id_by_token(token) if token else ""
    ok, msg, record = offboard_officer(
        device_id=req.device_id,
        employee_id=employee_id or "",
        token=token,
    )
    if not ok or record is None:
        raise HTTPException(403, msg)
    return {
        "ok": True,
        "status": record.status,
        "status_label": officer_db.status_label(record.status),
        "gender": record.gender,
        "employee_id": record.employee_id,
        "name": record.name,
        "message": msg,
    }
