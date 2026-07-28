"""Officer demo login and mobile auth routes."""
from __future__ import annotations

import secrets

from fastapi import APIRouter, HTTPException

from app.core.auth import DEMO_TOKENS, DEMO_USERS
from app.officers.schemas import LoginReq, MobileLoginReq, MobileRegisterReq
from app.patrol.store import login_officer_mobile, register_officer_mobile

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
    token = secrets.token_urlsafe(24)
    ok, msg, record = login_officer_mobile(
        phone=req.phone,
        face_image_b64=req.face_image_base64,
        token=token,
    )
    if not ok or record is None:
        raise HTTPException(403, msg)
    return {
        "ok": True,
        "token": token,
        "phone": record.phone,
        "name": record.name,
        "employee_id": record.employee_id,
        "department": record.department,
        "message": msg,
    }


@router.post("/auth/mobile/register")
def mobile_register(req: MobileRegisterReq):
    """手机 App 自助注册：人员进入在岗池，不绑定具体执法仪。"""
    token = secrets.token_urlsafe(24)
    ok, msg, record = register_officer_mobile(
        phone=req.phone,
        name=req.name,
        employee_id=req.employee_id,
        department=req.department,
        face_image_b64=req.face_image_base64,
        token=token,
        id_card=req.id_card,
        company=req.company,
        position=req.position,
        gender=req.gender,
    )
    if not ok or record is None:
        raise HTTPException(403, msg)
    return {
        "ok": True,
        "token": token,
        "phone": record.phone,
        "name": record.name,
        "employee_id": record.employee_id,
        "department": record.department,
        "company": req.company,
        "message": msg,
    }
