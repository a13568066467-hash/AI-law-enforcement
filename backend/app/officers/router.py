"""Officer demo login and mobile auth routes."""
from __future__ import annotations

import secrets

from fastapi import APIRouter, HTTPException

from app.core.auth import DEMO_TOKENS, DEMO_USERS
from app.officers import service as officers_service
from app.officers.schemas import LoginReq, MobileLoginReq, MobileRegisterReq

router = APIRouter(tags=["officers"])


@router.post("/auth/login")
def login(req: LoginReq):
    pwd = DEMO_USERS.get(req.phone)
    if pwd is None or pwd != req.password:
        raise HTTPException(401, "invalid credentials")
    token = secrets.token_urlsafe(24)
    DEMO_TOKENS[req.phone] = token
    return {"token": token, "phone": req.phone}


@router.post("/auth/mobile/login")
def mobile_login(req: MobileLoginReq):
    """手机 App 登录：在岗人员人脸比对，签发 Bearer token 供扫码绑定使用。"""
    try:
        return officers_service.mobile_login(
            phone=req.phone,
            face_image_base64=req.face_image_base64,
        )
    except ValueError as exc:
        raise HTTPException(403, str(exc)) from exc


@router.post("/auth/mobile/register")
def mobile_register(req: MobileRegisterReq):
    """手机 App 自助注册：人员进入在岗池，不绑定具体执法仪。"""
    try:
        return officers_service.mobile_register(
            phone=req.phone,
            name=req.name,
            employee_id=req.employee_id,
            department=req.department,
            face_image_base64=req.face_image_base64,
            id_card=req.id_card,
            company=req.company,
            position=req.position,
            gender=req.gender,
        )
    except ValueError as exc:
        raise HTTPException(403, str(exc)) from exc
