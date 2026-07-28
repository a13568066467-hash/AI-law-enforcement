"""巡查员注册验证：人员信息 → 短信验证码 → 人脸（会话内存 + 档案 SQLite/MySQL）。"""
from __future__ import annotations

import random
import re
import secrets
import time
from dataclasses import dataclass

from app.officers import repository as officer_db
from app.officers.repository import is_active_status
from app.patrol.store import find_phone_by_employee_id, get_officer

PHONE_RE = re.compile(r"^1\d{10}$")
ID_CARD_RE = re.compile(r"^[1-9]\d{5}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]$")
SMS_TTL_SEC = 300
SESSION_TTL_SEC = 1800


@dataclass
class VerifySession:
    session_id: str
    phone: str
    name: str
    employee_id: str
    department: str
    device_id: str
    id_card: str = ""
    company: str = ""
    position: str = ""
    gender: str = ""
    profile_ok: bool = False
    phone_ok: bool = False
    org_ok: bool = False
    sms_code: str = ""
    sms_expires: float = 0.0
    created_at: float = 0.0


_SESSIONS: dict[str, VerifySession] = {}
_PHONE_TOKENS: dict[str, str] = {}


def _now() -> float:
    return time.time()


def _purge_expired() -> None:
    now = _now()
    dead = [sid for sid, s in _SESSIONS.items() if now - s.created_at > SESSION_TTL_SEC]
    for sid in dead:
        _SESSIONS.pop(sid, None)


def _mask_phone(phone: str) -> str:
    if len(phone) != 11:
        return phone
    return f"{phone[:3]}****{phone[-4:]}"


def verify_profile(
    *,
    name: str,
    employee_id: str,
    department: str,
    device_id: str,
    id_card: str = "",
    company: str = "",
    position: str = "",
    gender: str = "",
) -> tuple[bool, str, str | None]:
    """步骤1：姓名/工号/身份证写入云端库，创建验证会话。"""
    _purge_expired()
    name = name.strip()
    ok_eid, eid_or_msg = officer_db.validate_employee_id(employee_id)
    if not ok_eid:
        return False, eid_or_msg, None
    employee_id = eid_or_msg
    device_id = device_id.strip()
    id_card = id_card.strip().upper()

    if len(name) < 2:
        return False, "姓名至少 2 个字", None
    if not id_card or not ID_CARD_RE.match(id_card):
        return False, "请填写18位有效身份证号", None
    ok_ic, msg_ic = officer_db.check_id_card_bindable(id_card, employee_id)
    if not ok_ic:
        return False, msg_ic, None
    if not device_id:
        return False, "设备 ID 无效", None

    active_row = officer_db.get_by_employee_id(employee_id)
    if active_row and is_active_status(active_row.status):
        if active_row.device_id != device_id:
            return False, "该工号已绑定其他执法仪，请联系管理员解绑", None
        bound_phone = active_row.phone
        if bound_phone:
            return False, f"工号 {employee_id} 已完成绑定（手机尾号 {bound_phone[-4:]}）", None

    saved_ok, save_msg, _row = officer_db.save_profile_draft(
        name=name,
        employee_id=employee_id,
        department=department,
        device_id=device_id,
        id_card=id_card,
        company=company,
        position=position,
        gender=gender,
    )
    if not saved_ok:
        return False, save_msg, None

    session_id = secrets.token_urlsafe(16)
    _SESSIONS[session_id] = VerifySession(
        session_id=session_id,
        phone="",
        name=name,
        employee_id=employee_id,
        department=department.strip() or "待完善",
        device_id=device_id,
        id_card=id_card,
        company=company.strip(),
        position=position.strip(),
        gender=gender.strip(),
        profile_ok=True,
        created_at=_now(),
    )
    return True, f"步骤1通过：{save_msg}", session_id


def complete_profile_org(
    session_id: str,
    *,
    company: str,
    department: str,
    position: str,
) -> tuple[bool, str]:
    """注册向导：手机验证后补充公司/部门/职位。"""
    _purge_expired()
    session = _SESSIONS.get(session_id)
    if session is None:
        return False, "验证会话已过期，请从步骤1重新开始"
    if not session.profile_ok:
        return False, "请先完成步骤1"
    ok, msg, _row = officer_db.update_profile_org(
        employee_id=session.employee_id,
        company=company,
        department=department,
        position=position,
    )
    if not ok:
        return False, msg
    session.company = company.strip()
    session.department = department.strip()
    session.position = position.strip()
    session.org_ok = True
    return True, msg


def send_sms_code(session_id: str, phone: str) -> tuple[bool, str, str | None]:
    _purge_expired()
    session = _SESSIONS.get(session_id)
    if session is None:
        return False, "验证会话已过期，请从步骤1重新开始", None
    if not session.profile_ok:
        return False, "请先完成步骤1人员信息验证", None
    phone = phone.strip()
    if not PHONE_RE.match(phone):
        return False, "手机号须为 11 位且以 1 开头", None
    if session.phone and session.phone != phone:
        return False, "手机号与本次验证会话不一致", None

    ok, msg = officer_db.check_phone_bindable(phone, session.employee_id)
    if not ok:
        return False, msg, None

    existing = get_officer(phone)
    if existing and existing.employee_id != session.employee_id:
        return False, "该手机号已绑定其他工号", None
    if existing and existing.name != session.name:
        return False, "该手机号已绑定其他姓名", None
    session.phone = phone

    code = f"{random.randint(0, 999999):06d}"
    session.sms_code = code
    session.sms_expires = _now() + SMS_TTL_SEC
    session.phone_ok = False
    return True, f"验证码已发送至 {_mask_phone(phone)}", code


def verify_sms_code(session_id: str, phone: str, code: str) -> tuple[bool, str, str | None]:
    _purge_expired()
    session = _SESSIONS.get(session_id)
    if session is None:
        return False, "验证会话已过期，请从步骤1重新开始", None
    if not session.profile_ok:
        return False, "请先完成步骤1", None
    phone = phone.strip()
    if not PHONE_RE.match(phone):
        return False, "手机号格式错误", None
    if session.phone != phone:
        return False, "手机号与发送验证码时不一致", None
    if _now() > session.sms_expires:
        return False, "验证码已过期，请重新发送", None
    if code.strip() != session.sms_code:
        return False, "验证码错误", None

    ok_bind, bind_msg, row = officer_db.bind_phone(session.employee_id, phone)
    if not ok_bind or row is None:
        return False, bind_msg or "手机号绑定失败", None
    session.phone_ok = True
    verify_token = secrets.token_urlsafe(24)
    _PHONE_TOKENS[verify_token] = session_id
    return True, "步骤2通过：手机号已验证", verify_token


def get_verify_session(session_id: str) -> VerifySession | None:
    _purge_expired()
    return _SESSIONS.get(session_id)


def get_session(session_id: str) -> VerifySession | None:
    return get_verify_session(session_id)


def peek_verify_token(verify_token: str) -> VerifySession | None:
    session_id = _PHONE_TOKENS.get(verify_token)
    if not session_id:
        return None
    session = _SESSIONS.get(session_id)
    if session is None or not session.phone_ok:
        return None
    if not session.org_ok:
        return None
    return session


def consume_verify_token(verify_token: str) -> VerifySession | None:
    session_id = _PHONE_TOKENS.pop(verify_token, None)
    if not session_id:
        return None
    return _SESSIONS.get(session_id)
