"""巡查员三步验证：人员信息 → 短信验证码 → 人脸（会话内存 + 档案 SQLite）。"""
from __future__ import annotations

import random
import re
import secrets
import time
from dataclasses import dataclass

from . import officer_db
from .patrol_store import find_phone_by_employee_id, get_officer

PHONE_RE = re.compile(r"^1\d{10}$")
SMS_TTL_SEC = 300
SESSION_TTL_SEC = 1800

_DEMO_ROSTER: dict[str, str] = {
    "XC001": "张三",
    "XC002": "李四",
    "XC003": "王五",
}


@dataclass
class VerifySession:
    session_id: str
    phone: str
    name: str
    employee_id: str
    department: str
    device_id: str
    profile_ok: bool = False
    phone_ok: bool = False
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
) -> tuple[bool, str, str | None]:
    """步骤 1：人员信息验证并写入云端库。"""
    _purge_expired()
    name = name.strip()
    employee_id = employee_id.strip().upper()
    department = department.strip()
    device_id = device_id.strip()

    if len(name) < 2:
        return False, "姓名至少 2 个字", None
    if not employee_id:
        return False, "请填写工号", None
    if not department:
        return False, "请填写所属部门", None
    if not device_id:
        return False, "设备 ID 无效", None

    roster_name = _DEMO_ROSTER.get(employee_id)
    if roster_name and roster_name != name:
        return False, f"工号 {employee_id} 与姓名不匹配（应为 {roster_name}）", None

    active_row = officer_db.get_by_employee_id(employee_id)
    if active_row and active_row.status == officer_db.STATUS_ACTIVE:
        if active_row.device_id != device_id:
            return False, "该工号已绑定其他执法仪，请联系管理员解绑", None
        bound_phone = active_row.phone
        if bound_phone:
            return False, f"工号 {employee_id} 已完成绑定（手机尾号 {bound_phone[-4:]}）", None

    bound = find_phone_by_employee_id(employee_id)
    if bound and active_row and active_row.status == officer_db.STATUS_ACTIVE:
        return False, f"工号 {employee_id} 已绑定手机 {_mask_phone(bound)}", None

    saved_ok, save_msg, _row = officer_db.save_profile_draft(
        name=name,
        employee_id=employee_id,
        department=department,
        device_id=device_id,
    )
    if not saved_ok:
        return False, save_msg, None

    session_id = secrets.token_urlsafe(16)
    _SESSIONS[session_id] = VerifySession(
        session_id=session_id,
        phone="",
        name=name,
        employee_id=employee_id,
        department=department,
        device_id=device_id,
        profile_ok=True,
        created_at=_now(),
    )
    return True, f"步骤1通过：{save_msg}", session_id


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
    if existing and existing.employee_id.upper() != session.employee_id:
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
    if not session.phone or session.phone != phone:
        return False, "请先发送验证码并确认手机号", None
    if not session.sms_code or _now() > session.sms_expires:
        return False, "验证码已过期，请重新获取", None
    if code.strip() != session.sms_code:
        return False, "验证码错误", None

    row = officer_db.bind_phone(session.employee_id, phone)
    if row is None:
        return False, "云端档案不存在，请从步骤1重新开始", None

    session.phone_ok = True
    verify_token = secrets.token_urlsafe(20)
    _PHONE_TOKENS[verify_token] = session_id
    return True, "步骤2通过：手机号已写入云端库", verify_token


def peek_verify_token(verify_token: str) -> VerifySession | None:
    session_id = _PHONE_TOKENS.get(verify_token)
    if not session_id:
        return None
    session = _SESSIONS.get(session_id)
    if session is None or not session.profile_ok or not session.phone_ok:
        return None
    return session


def consume_verify_token(verify_token: str) -> VerifySession | None:
    session = peek_verify_token(verify_token)
    if session is None:
        return None
    _PHONE_TOKENS.pop(verify_token, None)
    return session


def get_session(session_id: str) -> VerifySession | None:
    _purge_expired()
    return _SESSIONS.get(session_id)
