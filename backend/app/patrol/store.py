"""巡查员账号、人脸模板与执法仪一对一绑定。"""
from __future__ import annotations

from dataclasses import dataclass

from app.officers.repository import normalize_employee_id
from app.patrol import face_engine
from app.officers import repository as officer_db


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
    gender: str = "",
) -> tuple[bool, str, OfficerRecord | None]:
    phone = phone.strip()
    device_id = device_id.strip()
    employee_id = employee_id.strip()
    if not phone or not device_id:
        return False, "手机号与设备 ID 不能为空", None

    ok, msg = officer_db.check_device_bindable(device_id, employee_id)
    if not ok:
        return False, msg, None
    ok, msg = officer_db.check_phone_bindable(phone, employee_id)
    if not ok:
        return False, msg, None
    id_card_s = id_card.strip().upper()
    if id_card_s:
        ok, msg = officer_db.check_id_card_bindable(id_card_s, employee_id)
        if not ok:
            return False, msg, None

    vector, err = face_engine.extract_or_demo(face_image_b64)
    if vector is None:
        return False, err or "人脸特征提取失败", None

    existing = officer_db.get_by_employee_id(employee_id)
    if existing and existing.face_vector:
        ok_match, _score, msg = face_engine.verify_match(existing.face_vector, vector)
        if not ok_match:
            return False, msg, None

    try:
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
            gender=gender,
        )
    except officer_db._INTEGRITY_ERRORS:
        return False, "人员信息冲突（工号/手机/身份证/执法仪须唯一）", None
    officer_db.save_token(token, employee_id, phone)
    engine = face_engine.face_engine_name()
    return True, f"注册成功，人脸模板已写入云端（{engine}）", _to_record(row)


POOL_DEVICE_PREFIX = officer_db.POOL_DEVICE_PREFIX


def register_officer_mobile(
    *,
    phone: str,
    name: str,
    employee_id: str,
    department: str,
    face_image_b64: str,
    token: str,
    id_card: str = "",
    company: str = "",
    position: str = "",
    gender: str = "",
) -> tuple[bool, str, OfficerRecord | None]:
    """手机 App 自助注册：不在 officers 占用具体执法仪，仅进入在岗池可被扫码绑定。"""
    phone = phone.strip()
    employee_id = employee_id.strip()
    if not phone or not company.strip():
        return False, "手机号与公司不能为空", None

    ok, msg = officer_db.check_phone_bindable(phone, employee_id)
    if not ok:
        return False, msg, None
    id_card_s = id_card.strip().upper()
    if id_card_s:
        ok, msg = officer_db.check_id_card_bindable(id_card_s, employee_id)
        if not ok:
            return False, msg, None

    vector, err = face_engine.extract_or_demo(face_image_b64)
    if vector is None:
        return False, err or "人脸特征提取失败", None

    existing = officer_db.get_by_employee_id(employee_id)
    if existing and existing.face_vector:
        ok_match, _score, msg = face_engine.verify_match(existing.face_vector, vector)
        if not ok_match:
            return False, msg, None

    pool_device = f"{POOL_DEVICE_PREFIX}{normalize_employee_id(employee_id)}"
    try:
        row = officer_db.activate_officer(
            employee_id=employee_id,
            phone=phone,
            name=name,
            department=department,
            device_id=pool_device,
            face_vector=vector,
            id_card=id_card,
            company=company,
            position=position,
            gender=gender,
        )
    except officer_db._INTEGRITY_ERRORS:
        return False, "人员信息冲突（工号/手机/身份证须唯一）", None
    officer_db.save_token(token, employee_id, phone)
    engine = face_engine.face_engine_name()
    return True, f"注册成功，可在本公司执法仪扫码绑定（{engine}）", _to_record(row)


def login_officer_mobile(
    *,
    phone: str,
    face_image_b64: str,
    token: str,
) -> tuple[bool, str, OfficerRecord | None]:
    """手机 App 登录：人脸比对在岗人员，不要求绑定具体执法仪。"""
    phone = phone.strip()
    row = officer_db.get_by_phone(phone)
    if row is None or not officer_db.is_active_status(row.status):
        return False, "该手机号未注册或不在岗", None

    vector, err = face_engine.extract_or_demo(face_image_b64)
    if vector is None:
        return False, err or "人脸特征提取失败", None

    ok_match, _score, msg = face_engine.verify_match(row.face_vector, vector)
    if not ok_match:
        return False, msg, None

    officer_db.save_token(token, row.employee_id, phone)
    return True, msg, _to_record(row)


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
    if row is None or not officer_db.is_active_status(row.status):
        return False, "该手机号未完成注册，请按三步验证完成首次绑定", None

    if row.device_id != device_id:
        return False, "本机不是您绑定的执法仪，请使用专属设备登录", None

    by_device = officer_db.get_by_device(device_id)
    if by_device is None or by_device.phone != phone:
        return False, "设备绑定关系异常，请联系管理员", None

    vector, err = face_engine.extract_or_demo(face_image_b64)
    if vector is None:
        return False, err or "人脸特征提取失败", None

    ok_match, _score, msg = face_engine.verify_match(row.face_vector, vector)
    if not ok_match:
        return False, msg, None

    officer_db.save_token(token, row.employee_id, phone)
    return True, msg, _to_record(row)


def login_officer_by_device(
    *,
    device_id: str,
    face_image_b64: str,
    token: str,
) -> tuple[bool, str, OfficerRecord | None]:
    """已注册设备：仅凭人脸与云端库模板比对登录（无需短信验证）。"""
    row = officer_db.get_by_device(device_id.strip())
    if row is None or not officer_db.is_active_status(row.status):
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
