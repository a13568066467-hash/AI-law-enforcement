"""巡查员档案持久化（SQLite 默认，可切换 MySQL）。"""
from __future__ import annotations

import json
import os
import random
import re
from dataclasses import dataclass
from typing import Any

from app.db.connection import (  # re-export for callers / shims
    _INTEGRITY_ERRORS,
    _adapt_sql,
    _conn,
    _db_path,
    _execute,
    _fetchone,
    _mysql_connect,
    _row_get,
    _row_keys,
    _utc_now,
    db_backend_label,
    ping,
    use_mysql,
)
from app.db.migrations.officers import LEGACY_ID_CARD_COLUMN

# 绑定状态（整型）：0=已离职 1=在岗 2=注册办理中
STATUS_RESIGNED = 0
STATUS_ACTIVE = 1
STATUS_REGISTERING = 2

# 兼容旧代码引用
STATUS_PROFILE = STATUS_REGISTERING
STATUS_PHONE = STATUS_REGISTERING

RESIGNED_DEVICE_PREFIX = "__resigned__"
RESIGNED_PHONE_PREFIX = "__resigned_p__"
RESIGNED_ID_CARD_PREFIX = "__resigned_i__"
POOL_DEVICE_PREFIX = "__pool__"

EMPLOYEE_ID_RE = re.compile(r"^\d{6}$")


@dataclass
class OfficerRow:
    employee_id: str
    name: str
    gender: str
    department: str
    phone: str
    device_id: str
    face_vector: list[float]
    status: int
    created_at: str
    updated_at: str
    last_device_id: str = ""
    resigned_at: str = ""
    id_card: str = ""
    company: str = ""
    position: str = ""


def normalize_status(raw: Any) -> int:
    """将库内 status（历史字符串或整型）规范为 0/1/2。"""
    if raw is None:
        return STATUS_REGISTERING
    if isinstance(raw, int):
        return raw
    s = str(raw).strip().lower()
    if s.isdigit():
        return int(s)
    legacy = {
        "resigned": STATUS_RESIGNED,
        "active": STATUS_ACTIVE,
        "profile": STATUS_REGISTERING,
        "phone_verified": STATUS_REGISTERING,
    }
    return legacy.get(s, STATUS_REGISTERING)


def status_label(status: int) -> str:
    if status == STATUS_ACTIVE:
        return "在岗"
    if status == STATUS_RESIGNED:
        return "已离职"
    return "注册办理中"


def is_active_status(status: int) -> bool:
    return normalize_status(status) == STATUS_ACTIVE


def is_resigned_status(status: int) -> bool:
    return normalize_status(status) == STATUS_RESIGNED


def is_registering_status(status: int) -> bool:
    return normalize_status(status) == STATUS_REGISTERING


def normalize_gender(raw: str) -> str:
    g = (raw or "").strip()
    if g in ("男", "M", "m", "male", "1"):
        return "男"
    if g in ("女", "F", "f", "female", "2"):
        return "女"
    return "未知" if not g else g[:8]


def normalize_employee_id(employee_id: str) -> str:
    return employee_id.strip()


def validate_employee_id(employee_id: str) -> tuple[bool, str]:
    eid = normalize_employee_id(employee_id)
    if not EMPLOYEE_ID_RE.match(eid):
        return False, "工号须为6位数字"
    return True, eid


def _read_id_card_from_row(row: Any) -> str:
    val = _row_get(row, "id_card")
    if val:
        return val
    return _row_get(row, LEGACY_ID_CARD_COLUMN)


def init_db() -> None:
    """Ensure schema; delegates to centralized migrations."""
    from app.db.migrations import run_all_migrations

    run_all_migrations()


def _row_to_officer(row: Any | None) -> OfficerRow | None:
    if row is None:
        return None
    vec_raw = row["face_vector"]
    vec: list[float] = json.loads(vec_raw) if vec_raw else []
    keys = _row_keys(row)
    return OfficerRow(
        employee_id=_row_get(row, "employee_id"),
        name=_row_get(row, "name"),
        gender=normalize_gender(_row_get(row, "gender") if "gender" in keys else ""),
        department=_row_get(row, "department"),
        phone=_row_get(row, "phone"),
        device_id=_row_get(row, "device_id"),
        face_vector=vec,
        status=normalize_status(row["status"]),
        created_at=_row_get(row, "created_at"),
        updated_at=_row_get(row, "updated_at"),
        last_device_id=_row_get(row, "last_device_id") if "last_device_id" in keys else "",
        resigned_at=_row_get(row, "resigned_at") if "resigned_at" in keys else "",
        id_card=_read_id_card_from_row(row),
        company=_row_get(row, "company") if "company" in keys else "",
        position=_row_get(row, "position") if "position" in keys else "",
    )


def get_by_employee_id(employee_id: str) -> OfficerRow | None:
    eid = normalize_employee_id(employee_id)
    with _conn() as conn:
        row = _fetchone(conn, "SELECT * FROM officers WHERE employee_id = ?", (eid,))
    return _row_to_officer(row)


def generate_employee_id() -> str:
    """生成唯一6位数字工号。"""
    for _ in range(128):
        eid = f"{random.randint(0, 999999):06d}"
        if get_by_employee_id(eid) is None:
            return eid
    raise RuntimeError("无法生成唯一工号")


def get_by_phone(phone: str) -> OfficerRow | None:
    phone = phone.strip()
    if not phone or phone.startswith(RESIGNED_PHONE_PREFIX):
        return None
    with _conn() as conn:
        row = _fetchone(conn, "SELECT * FROM officers WHERE phone = ?", (phone,))
    return _row_to_officer(row)


def get_by_id_card(id_card: str) -> OfficerRow | None:
    id_card = id_card.strip().upper()
    if not id_card or id_card.startswith(RESIGNED_ID_CARD_PREFIX):
        return None
    with _conn() as conn:
        row = _fetchone(conn, "SELECT * FROM officers WHERE id_card = ?", (id_card,))
    return _row_to_officer(row)


def get_by_device(device_id: str) -> OfficerRow | None:
    device_id = device_id.strip()
    if not device_id or device_id.startswith(RESIGNED_DEVICE_PREFIX):
        return None
    with _conn() as conn:
        row = _fetchone(conn, "SELECT * FROM officers WHERE device_id = ?", (device_id,))
    return _row_to_officer(row)


def get_resigned_by_device(device_id: str) -> OfficerRow | None:
    """查找曾绑定该执法仪且已离职的人员（用于审计，非活跃绑定）。"""
    device_id = device_id.strip()
    with _conn() as conn:
        row = _fetchone(
            conn,
            """
            SELECT * FROM officers
            WHERE last_device_id = ? AND status = ?
            ORDER BY resigned_at DESC
            LIMIT 1
            """,
            (device_id, STATUS_RESIGNED),
        )
    return _row_to_officer(row)


def find_phone_by_employee_id(employee_id: str) -> str | None:
    row = get_by_employee_id(employee_id)
    if row and row.phone:
        return row.phone
    return None


def officer_exists(phone: str) -> bool:
    row = get_by_phone(phone)
    return row is not None and is_active_status(row.status)


def save_profile_draft(
    *,
    name: str,
    employee_id: str,
    department: str,
    device_id: str,
    id_card: str = "",
    company: str = "",
    position: str = "",
    gender: str = "",
) -> tuple[bool, str, OfficerRow | None]:
    """步骤1 通过后写入云端库（status=profile）。"""
    ok_eid, eid_or_msg = validate_employee_id(employee_id)
    if not ok_eid:
        return False, eid_or_msg, None
    eid = eid_or_msg
    id_card_s = id_card.strip().upper()
    if len(id_card_s) != 18:
        return False, "请填写18位有效身份证号", None
    ok, msg = check_device_bindable(device_id, eid)
    if not ok:
        return False, msg, None
    ok_ic, msg_ic = check_id_card_bindable(id_card_s, eid)
    if not ok_ic:
        return False, msg_ic, None
    gender_s = normalize_gender(gender)
    dept = department.strip() or "待完善"
    now = _utc_now()
    company_s = company.strip()
    position_s = position.strip()
    try:
        with _conn() as conn:
            existing = _fetchone(conn, "SELECT * FROM officers WHERE employee_id = ?", (eid,))
            if existing:
                prev_status = normalize_status(existing["status"])
                if prev_status == STATUS_RESIGNED:
                    _execute(
                        conn,
                        """
                        UPDATE officers
                        SET name = ?, gender = ?, department = ?, device_id = ?, status = ?,
                            phone = NULL, face_vector = NULL, resigned_at = NULL,
                            id_card = ?, company = ?, position = ?,
                            updated_at = ?
                        WHERE employee_id = ?
                        """,
                        (
                            name.strip(), gender_s, dept, device_id.strip(), STATUS_REGISTERING,
                            id_card_s or None, company_s or None, position_s or None,
                            now, eid,
                        ),
                    )
                else:
                    _execute(
                        conn,
                        """
                        UPDATE officers
                        SET name = ?, gender = ?, department = ?, device_id = ?, status = ?,
                            id_card = ?, company = ?, position = ?,
                            updated_at = ?
                        WHERE employee_id = ?
                        """,
                        (
                            name.strip(), gender_s, dept, device_id.strip(), STATUS_REGISTERING,
                            id_card_s or None, company_s or None, position_s or None,
                            now, eid,
                        ),
                    )
            else:
                _execute(
                    conn,
                    """
                    INSERT INTO officers (
                        employee_id, name, gender, department, phone, device_id,
                        face_vector, status, created_at, updated_at,
                        id_card, company, position
                    ) VALUES (?, ?, ?, ?, NULL, ?, NULL, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        eid, name.strip(), gender_s, dept, device_id.strip(), STATUS_REGISTERING,
                        now, now, id_card_s or None, company_s or None, position_s or None,
                    ),
                )
    except _INTEGRITY_ERRORS:
        return False, _integrity_user_message(eid, id_card_s, device_id.strip()), None
    except Exception as exc:  # pragma: no cover
        return False, f"数据库写入失败：{exc}", None
    row = get_by_employee_id(eid)
    try:
        from app.recorders import repository as recorder_db

        recorder_db.mark_registering(device_id.strip(), eid)
    except Exception:
        pass
    return True, "人员信息已写入云端数据库", row


def update_profile_org(
    *,
    employee_id: str,
    company: str,
    department: str,
    position: str,
) -> tuple[bool, str, OfficerRow | None]:
    """注册向导：补充公司/部门/职位。"""
    eid = normalize_employee_id(employee_id)
    dept = department.strip()
    if not dept:
        return False, "请填写所属部门", None
    now = _utc_now()
    with _conn() as conn:
        _execute(
            conn,
            """
            UPDATE officers
            SET company = ?, department = ?, position = ?, updated_at = ?
            WHERE employee_id = ?
            """,
            (company.strip(), dept, position.strip(), now, eid),
        )
    row = get_by_employee_id(eid)
    if row is None:
        return False, "人员档案不存在", None
    return True, "组织信息已更新", row


def bind_phone(employee_id: str, phone: str) -> tuple[bool, str, OfficerRow | None]:
    """步骤2 通过后绑定手机号。"""
    eid = normalize_employee_id(employee_id)
    phone = phone.strip()
    ok, msg = check_phone_bindable(phone, eid)
    if not ok:
        return False, msg, None
    now = _utc_now()
    try:
        with _conn() as conn:
            _execute(
                conn,
                """
                UPDATE officers
                SET phone = ?, status = ?, updated_at = ?
                WHERE employee_id = ?
                """,
                (phone, STATUS_REGISTERING, now, eid),
            )
    except _INTEGRITY_ERRORS:
        return False, "该手机号已被其他人员使用，不可重复", None
    row = get_by_employee_id(eid)
    return True, "手机号已绑定", row


def activate_officer(
    *,
    employee_id: str,
    phone: str,
    name: str,
    department: str,
    device_id: str,
    face_vector: list[float],
    id_card: str = "",
    company: str = "",
    position: str = "",
    gender: str = "",
) -> OfficerRow:
    """步骤3 人脸通过后激活绑定（一人一台执法仪）。"""
    eid = normalize_employee_id(employee_id)
    phone_s = phone.strip()
    device_s = device_id.strip()
    id_card_s = id_card.strip().upper()
    now = _utc_now()
    vec_json = json.dumps(face_vector)
    dept = department.strip() or "待完善"
    gender_s = normalize_gender(gender)
    params = (
        eid,
        name.strip(),
        gender_s,
        dept,
        phone_s,
        device_s,
        vec_json,
        STATUS_ACTIVE,
        now,
        now,
        id_card_s or None,
        company.strip() or None,
        position.strip() or None,
    )
    with _conn() as conn:
        if use_mysql():
            _execute(
                conn,
                """
                INSERT INTO officers (
                    employee_id, name, gender, department, phone, device_id,
                    face_vector, status, created_at, updated_at,
                    id_card, company, position
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
                    gender = VALUES(gender),
                    department = VALUES(department),
                    phone = VALUES(phone),
                    device_id = VALUES(device_id),
                    face_vector = VALUES(face_vector),
                    status = VALUES(status),
                    id_card = VALUES(id_card),
                    company = VALUES(company),
                    position = VALUES(position),
                    updated_at = VALUES(updated_at)
                """,
                params,
            )
        else:
            _execute(
                conn,
                """
                INSERT INTO officers (
                    employee_id, name, gender, department, phone, device_id,
                    face_vector, status, created_at, updated_at,
                    id_card, company, position
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(employee_id) DO UPDATE SET
                    name = excluded.name,
                    gender = excluded.gender,
                    department = excluded.department,
                    phone = excluded.phone,
                    device_id = excluded.device_id,
                    face_vector = excluded.face_vector,
                    status = excluded.status,
                    id_card = excluded.id_card,
                    company = excluded.company,
                    position = excluded.position,
                    updated_at = excluded.updated_at
                """,
                params,
            )
    row = get_by_employee_id(eid)
    assert row is not None
    try:
        from app.recorders import repository as recorder_db

        if not device_s.startswith(RESIGNED_DEVICE_PREFIX) and not device_s.startswith(POOL_DEVICE_PREFIX):
            recorder_db.mark_active(device_s, eid)
    except Exception:
        pass
    return row


def save_token(token: str, employee_id: str, phone: str) -> None:
    params = (token, normalize_employee_id(employee_id), phone.strip(), _utc_now())
    with _conn() as conn:
        if use_mysql():
            _execute(
                conn,
                """
                INSERT INTO auth_tokens (token, employee_id, phone, created_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    employee_id = VALUES(employee_id),
                    phone = VALUES(phone),
                    created_at = VALUES(created_at)
                """,
                params,
            )
        else:
            _execute(
                conn,
                "INSERT OR REPLACE INTO auth_tokens (token, employee_id, phone, created_at) VALUES (?, ?, ?, ?)",
                params,
            )


def is_token_valid(token: str) -> bool:
    with _conn() as conn:
        row = _fetchone(conn, "SELECT 1 AS ok FROM auth_tokens WHERE token = ?", (token,))
    return row is not None


def revoke_token(phone: str) -> None:
    with _conn() as conn:
        _execute(conn, "DELETE FROM auth_tokens WHERE phone = ?", (phone.strip(),))


def check_device_bindable(device_id: str, employee_id: str) -> tuple[bool, str]:
    """执法仪是否可绑定该巡查员：一机同时仅一人（含注册中），前任须注销后才可换人。"""
    device_id = device_id.strip()
    eid = normalize_employee_id(employee_id)
    try:
        from app.recorders import repository as recorder_db

        ok_r, msg_r = recorder_db.check_recorder_usable(device_id, eid)
        if not ok_r:
            return False, msg_r
    except Exception:
        pass
    by_device = get_by_device(device_id)
    if by_device and by_device.employee_id != eid:
        if is_active_status(by_device.status):
            return False, (
                f"本执法仪已绑定在岗巡查员 {by_device.name}（工号 {by_device.employee_id}）。"
                "一台执法仪同时只能绑定一名人员；如需换人，请当前人员先在设置中完成注销/离职。"
            )
        if is_registering_status(by_device.status):
            return False, (
                f"本执法仪正在为 {by_device.name}（工号 {by_device.employee_id}）办理绑定。"
                "请等待其完成注册或注销后再绑定其他人员。"
            )
    by_emp = get_by_employee_id(eid)
    if by_emp and is_active_status(by_emp.status) and by_emp.device_id != device_id:
        return False, "该巡查员已绑定另一台执法仪，请先注销后再绑定新设备"
    return True, ""


def check_phone_bindable(phone: str, employee_id: str) -> tuple[bool, str]:
    phone = phone.strip()
    eid = normalize_employee_id(employee_id)
    by_phone = get_by_phone(phone)
    if by_phone and by_phone.employee_id != eid:
        if is_active_status(by_phone.status):
            return False, "该手机号已绑定其他在岗巡查员，不可重复"
        if is_registering_status(by_phone.status):
            return False, "该手机号正在为其他人员办理绑定，不可重复"
    return True, ""


def check_id_card_bindable(id_card: str, employee_id: str) -> tuple[bool, str]:
    id_card = id_card.strip().upper()
    eid = normalize_employee_id(employee_id)
    if len(id_card) != 18:
        return False, "请填写18位有效身份证号"
    by_id = get_by_id_card(id_card)
    if by_id and by_id.employee_id != eid:
        if is_active_status(by_id.status):
            return False, "该身份证号已绑定其他在岗巡查员，不可重复"
        if is_registering_status(by_id.status):
            return False, "该身份证号正在为其他人员办理绑定，不可重复"
    return True, ""


def _integrity_user_message(employee_id: str, id_card: str, device_id: str) -> str:
    if get_by_employee_id(employee_id):
        return "该工号已存在，请重新生成工号"
    if id_card and get_by_id_card(id_card):
        return "该身份证号已被占用，不可重复"
    if device_id and get_by_device(device_id):
        return "本执法仪已被其他人员占用，请先完成注销后再绑定"
    return "人员信息冲突（工号/手机/身份证/执法仪须唯一），请检查后重试"


def get_employee_id_by_token(token: str) -> str | None:
    with _conn() as conn:
        row = _fetchone(conn, "SELECT employee_id FROM auth_tokens WHERE token = ?", (token,))
    return _row_get(row, "employee_id") if row else None


def offboard_officer(*, device_id: str, employee_id: str = "") -> tuple[bool, str, OfficerRow | None]:
    """执法仪端注销：解除设备绑定，云端档案标记为离职。"""
    device_id = device_id.strip()
    row = get_by_device(device_id)
    if row is None or not is_active_status(row.status):
        return False, "本机未绑定在岗巡查员", None
    eid = normalize_employee_id(employee_id) if employee_id else row.employee_id
    if row.employee_id != eid:
        return False, "注销人员与设备绑定不一致", None

    now = _utc_now()
    dev_ph = f"{RESIGNED_DEVICE_PREFIX}{row.employee_id}"
    phone_ph = f"{RESIGNED_PHONE_PREFIX}{row.employee_id}"
    id_ph = f"{RESIGNED_ID_CARD_PREFIX}{row.employee_id}"
    with _conn() as conn:
        _execute(
            conn,
            """
            UPDATE officers
            SET status = ?, last_device_id = device_id, device_id = ?,
                phone = ?, id_card = ?,
                resigned_at = ?, updated_at = ?
            WHERE employee_id = ?
            """,
            (STATUS_RESIGNED, dev_ph, phone_ph, id_ph, now, now, row.employee_id),
        )
        _execute(conn, "DELETE FROM auth_tokens WHERE employee_id = ?", (row.employee_id,))
    real_device_id = device_id
    try:
        from app.recorders import repository as recorder_db

        recorder_db.release_recorder(real_device_id)
    except Exception:
        pass
    updated = get_by_employee_id(row.employee_id)
    return True, "人员已注销，云端状态已更新为离职", updated
