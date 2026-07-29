"""Officers domain service — mobile login/register orchestration."""
from __future__ import annotations

import secrets
from typing import Any

from app.patrol.store import login_officer_mobile, register_officer_mobile


def mobile_login(*, phone: str, face_image_base64: str) -> dict[str, Any]:
    token = secrets.token_urlsafe(24)
    ok, msg, record = login_officer_mobile(
        phone=phone,
        face_image_b64=face_image_base64,
        token=token,
    )
    if not ok or record is None:
        raise ValueError(msg)
    return {
        "ok": True,
        "token": token,
        "phone": record.phone,
        "name": record.name,
        "employee_id": record.employee_id,
        "department": record.department,
        "message": msg,
    }


def mobile_register(
    *,
    phone: str,
    name: str,
    employee_id: str,
    department: str,
    face_image_base64: str,
    id_card: str = "",
    company: str = "",
    position: str = "",
    gender: str = "未知",
) -> dict[str, Any]:
    token = secrets.token_urlsafe(24)
    ok, msg, record = register_officer_mobile(
        phone=phone,
        name=name,
        employee_id=employee_id,
        department=department,
        face_image_b64=face_image_base64,
        token=token,
        id_card=id_card,
        company=company,
        position=position,
        gender=gender,
    )
    if not ok or record is None:
        raise ValueError(msg)
    return {
        "ok": True,
        "token": token,
        "phone": record.phone,
        "name": record.name,
        "employee_id": record.employee_id,
        "department": record.department,
        "company": company,
        "message": msg,
    }
