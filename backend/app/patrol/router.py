"""Historical patrol auth HTTP routes."""
from __future__ import annotations

from fastapi import APIRouter, Header, HTTPException

from app.core.auth import optional_bearer_token
from app.officers import repository as officer_db
from app.patrol import service as patrol_service
from app.patrol.schemas import (
    FaceOnlyLoginReq,
    OffboardReq,
    PatrolAuthReq,
    ProfileOrgReq,
    ProfileStepReq,
    SmsSendReq,
    SmsVerifyReq,
)
from app.patrol.verify import (
    complete_profile_org,
    get_session as get_verify_session,
    send_sms_code,
    verify_profile,
    verify_sms_code,
)

router = APIRouter(tags=["patrol"])


@router.get("/auth/patrol/status")
def patrol_status(phone: str, device_id: str):
    """查询手机号/执法仪绑定状态（读云端 SQLite 库）。"""
    return patrol_service.build_patrol_status(phone=phone, device_id=device_id)


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


@router.post("/auth/patrol/register")
def patrol_register(req: PatrolAuthReq):
    try:
        return patrol_service.face_auth(
            verify_token=req.verify_token,
            device_id=req.device_id,
            face_image_base64=req.face_image_base64,
            register=True,
        )
    except ValueError as exc:
        raise HTTPException(403, str(exc)) from exc


@router.post("/auth/patrol/login")
def patrol_login(req: PatrolAuthReq):
    try:
        return patrol_service.face_auth(
            verify_token=req.verify_token,
            device_id=req.device_id,
            face_image_base64=req.face_image_base64,
            register=False,
        )
    except ValueError as exc:
        raise HTTPException(403, str(exc)) from exc


@router.post("/auth/patrol/face-only-login")
def patrol_face_only_login(req: FaceOnlyLoginReq):
    """已注册执法仪：后续登录仅扫脸，与云端库人脸模板比对。"""
    try:
        return patrol_service.face_only_login(
            device_id=req.device_id,
            face_image_base64=req.face_image_base64,
        )
    except ValueError as exc:
        raise HTTPException(403, str(exc)) from exc


@router.post("/auth/patrol/offboard")
def patrol_offboard(req: OffboardReq, authorization: str | None = Header(default=None)):
    """执法仪端注销在岗巡查员：设备解绑，云端档案标记离职。"""
    try:
        return patrol_service.offboard(
            device_id=req.device_id,
            authorization_token=optional_bearer_token(authorization),
        )
    except ValueError as exc:
        raise HTTPException(403, str(exc)) from exc
