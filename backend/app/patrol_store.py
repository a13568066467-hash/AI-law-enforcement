"""巡查员账号、人脸模板与执法仪一对一绑定。"""
from __future__ import annotations

import base64
import io
import math
import os
from dataclasses import dataclass

from PIL import Image

from . import officer_db
from .officer_db import normalize_employee_id

FACE_MATCH_THRESHOLD = 0.82


@dataclass
class OfficerRecord:
    phone: str
    name: str
    employee_id: str
    department: str
    face_vector: list[float]
    device_id: str


def _to_record(row: officer_db.OfficerRow) -> OfficerRecord:
    return OfficerRecord(
        phone=row.phone,
        name=row.name,
        employee_id=row.employee_id,
        department=row.department,
        face_vector=row.face_vector,
        device_id=row.device_id,
    )


def _normalize_b64(image_b64: str) -> str:
    raw = image_b64.strip()
    if "," in raw and raw.lower().startswith("data:"):
        raw = raw.split(",", 1)[1]
    return raw.replace("\n", "").replace("\r", "")


def _center_crop_square(img: Image.Image) -> Image.Image:
    w, h = img.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    return img.crop((left, top, left + side, top + side))


def _face_vector_from_b64(image_b64: str) -> list[float] | None:
    try:
        raw = base64.b64decode(_normalize_b64(image_b64), validate=False)
        if len(raw) < 32:
            return None
        img = Image.open(io.BytesIO(raw)).convert("L")
        img = _center_crop_square(img).resize((32, 32), Image.Resampling.LANCZOS)
    except Exception:
        return None
    pixels = list(img.getdata())
    mean = sum(pixels) / len(pixels)
    std = math.sqrt(sum((p - mean) ** 2 for p in pixels) / len(pixels)) or 1.0
    return [(p - mean) / std for p in pixels]


def _similarity(a: list[float], b: list[float]) -> float:
    if len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def register_officer(
    *,
    phone: str,
    name: str,
    employee_id: str,
    department: str,
    device_id: str,
    face_image_b64: str,
    token: str,
    id_card: str = "",
    company: str = "",
    position: str = "",
) -> tuple[bool, str, OfficerRecord | None]:
    phone = phone.strip()
    device_id = device_id.strip()
    employee_id = employee_id.strip()
    if not phone or not device_id:
        return False, "手机号与设备 ID 不能为空", None
    if len(face_image_b64) < 64:
        return False, "人脸图像无效", None

    ok, msg = officer_db.check_device_bindable(device_id, employee_id)
    if not ok:
        return False, msg, None
    ok, msg = officer_db.check_phone_bindable(phone, employee_id)
    if not ok:
        return False, msg, None

    vector = _face_vector_from_b64(face_image_b64)
    if vector is None:
        if os.getenv("PATROL_DEMO_RELAX_FACE", "").strip().lower() in ("1", "true", "yes"):
            vector = [0.0] * 1024
        else:
            return False, "人脸图像无法解析，请重新采集", None

    existing = officer_db.get_by_employee_id(employee_id)
    if existing and existing.face_vector:
        score = _similarity(vector, existing.face_vector)
        if score < FACE_MATCH_THRESHOLD:
            return False, "人脸与已注册信息不匹配，请确认本人操作", None

    row = officer_db.activate_officer(
        employee_id=employee_id,
        phone=phone,
        name=name,
        department=department,
        device_id=device_id,
        face_vector=vector,
        id_card=id_card,
        company=company,
        position=position,
    )
    officer_db.save_token(token, employee_id, phone)
    return True, "注册成功，人员信息已写入云端库并绑定本执法仪", _to_record(row)


def login_officer(
    *,
    phone: str,
    device_id: str,
    face_image_b64: str,
    token: str,
) -> tuple[bool, str, OfficerRecord | None]:
    phone = phone.strip()
    device_id = device_id.strip()
    row = officer_db.get_by_phone(phone)
    if row is None or row.status != officer_db.STATUS_ACTIVE:
        return False, "该手机号未完成注册，请按三步验证完成首次绑定", None

    if row.device_id != device_id:
        return False, "本机不是您绑定的执法仪，请使用专属设备登录", None

    by_device = officer_db.get_by_device(device_id)
    if by_device is None or by_device.phone != phone:
        return False, "设备绑定关系异常，请联系管理员", None

    if len(face_image_b64) < 64:
        return False, "人脸图像无效", None

    vector = _face_vector_from_b64(face_image_b64)
    if vector is None:
        if os.getenv("PATROL_DEMO_RELAX_FACE", "").strip().lower() in ("1", "true", "yes"):
            vector = [0.0] * 1024
        else:
            return False, "人脸图像无法解析，请重新采集", None
    score = _similarity(vector, row.face_vector)
    if score < FACE_MATCH_THRESHOLD:
        return False, f"人脸验证未通过（相似度 {score:.0%}）", None

    officer_db.save_token(token, row.employee_id, phone)
    return True, "人脸验证通过", _to_record(row)


def login_officer_by_device(
    *,
    device_id: str,
    face_image_b64: str,
    token: str,
) -> tuple[bool, str, OfficerRecord | None]:
    """已注册设备：仅凭人脸与云端库模板比对登录（无需短信验证）。"""
    row = officer_db.get_by_device(device_id.strip())
    if row is None or row.status != officer_db.STATUS_ACTIVE:
        return False, "本机未绑定巡查员，请先完成首次注册", None
    return login_officer(
        phone=row.phone,
        device_id=device_id,
        face_image_b64=face_image_b64,
        token=token,
    )


def officer_exists(phone: str) -> bool:
    return officer_db.officer_exists(phone)


def get_officer(phone: str) -> OfficerRecord | None:
    row = officer_db.get_by_phone(phone.strip())
    if row is None:
        return None
    return _to_record(row)


def get_officer_by_device(device_id: str) -> OfficerRecord | None:
    row = officer_db.get_by_device(device_id.strip())
    if row is None:
        return None
    return _to_record(row)


def find_phone_by_employee_id(employee_id: str) -> str | None:
    return officer_db.find_phone_by_employee_id(employee_id)


def is_token_valid(token: str) -> bool:
    return officer_db.is_token_valid(token)


def revoke_token(phone: str) -> None:
    officer_db.revoke_token(phone)


def offboard_officer(
    *,
    device_id: str,
    employee_id: str = "",
    token: str = "",
) -> tuple[bool, str, OfficerRecord | None]:
    resolved_eid = normalize_employee_id(employee_id) if employee_id else ""
    if token and officer_db.is_token_valid(token):
        resolved_eid = officer_db.get_employee_id_by_token(token) or resolved_eid
    ok, msg, row = officer_db.offboard_officer(device_id=device_id, employee_id=resolved_eid)
    if not ok or row is None:
        return False, msg, None
    return True, msg, _to_record(row)
