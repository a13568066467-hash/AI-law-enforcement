"""巡查员档案 SQLite 持久化。"""
from __future__ import annotations

import json
import os
import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator

STATUS_PROFILE = "profile"  # 步骤1：人员信息已入库
STATUS_PHONE = "phone_verified"  # 步骤2：手机号已验证
STATUS_ACTIVE = "active"  # 步骤3：人脸完成，执法仪绑定生效
STATUS_RESIGNED = "resigned"  # 执法仪端注销，云端保留档案


@dataclass
class OfficerRow:
    employee_id: str
    name: str
    department: str
    phone: str
    device_id: str
    face_vector: list[float]
    status: str
    created_at: str
    updated_at: str
    last_device_id: str = ""
    resigned_at: str = ""


def _db_path() -> Path:
    raw = os.getenv("OFFICER_DB_PATH", "").strip()
    if raw:
        return Path(raw)
    return Path(__file__).resolve().parent.parent / "data" / "officers.db"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


@contextmanager
def _conn() -> Iterator[sqlite3.Connection]:
    path = _db_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    with _conn() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS officers (
                employee_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                department TEXT NOT NULL,
                phone TEXT UNIQUE,
                device_id TEXT NOT NULL UNIQUE,
                face_vector TEXT,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_officers_phone ON officers(phone);
            CREATE INDEX IF NOT EXISTS idx_officers_device ON officers(device_id);
            CREATE INDEX IF NOT EXISTS idx_officers_status ON officers(status);

            CREATE TABLE IF NOT EXISTS auth_tokens (
                token TEXT PRIMARY KEY,
                employee_id TEXT NOT NULL,
                phone TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            """
        )
        _migrate(conn)


def _migrate(conn: sqlite3.Connection) -> None:
    cols = {row[1] for row in conn.execute("PRAGMA table_info(officers)")}
    if "last_device_id" not in cols:
        conn.execute("ALTER TABLE officers ADD COLUMN last_device_id TEXT")
    if "resigned_at" not in cols:
        conn.execute("ALTER TABLE officers ADD COLUMN resigned_at TEXT")


def _row_to_officer(row: sqlite3.Row | None) -> OfficerRow | None:
    if row is None:
        return None
    vec_raw = row["face_vector"]
    vec: list[float] = json.loads(vec_raw) if vec_raw else []
    return OfficerRow(
        employee_id=row["employee_id"],
        name=row["name"],
        department=row["department"],
        phone=row["phone"] or "",
        device_id=row["device_id"],
        face_vector=vec,
        status=row["status"],
        created_at=row["created_at"],
        updated_at=row["updated_at"],
        last_device_id=row["last_device_id"] or "" if "last_device_id" in row.keys() else "",
        resigned_at=row["resigned_at"] or "" if "resigned_at" in row.keys() else "",
    )


def get_by_employee_id(employee_id: str) -> OfficerRow | None:
    eid = employee_id.strip().upper()
    with _conn() as conn:
        row = conn.execute(
            "SELECT * FROM officers WHERE employee_id = ?",
            (eid,),
        ).fetchone()
    return _row_to_officer(row)


def get_by_phone(phone: str) -> OfficerRow | None:
    with _conn() as conn:
        row = conn.execute(
            "SELECT * FROM officers WHERE phone = ?",
            (phone.strip(),),
        ).fetchone()
    return _row_to_officer(row)


def get_by_device(device_id: str) -> OfficerRow | None:
    with _conn() as conn:
        row = conn.execute(
            "SELECT * FROM officers WHERE device_id = ?",
            (device_id.strip(),),
        ).fetchone()
    return _row_to_officer(row)


def find_phone_by_employee_id(employee_id: str) -> str | None:
    row = get_by_employee_id(employee_id)
    if row and row.phone:
        return row.phone
    return None


def officer_exists(phone: str) -> bool:
    row = get_by_phone(phone)
    return row is not None and row.status == STATUS_ACTIVE


def save_profile_draft(
    *,
    name: str,
    employee_id: str,
    department: str,
    device_id: str,
) -> tuple[bool, str, OfficerRow | None]:
    """步骤1 通过后写入云端库（status=profile）。"""
    eid = employee_id.strip().upper()
    ok, msg = check_device_bindable(device_id, eid)
    if not ok:
        return False, msg, None
    now = _utc_now()
    try:
        with _conn() as conn:
            existing = conn.execute(
                "SELECT * FROM officers WHERE employee_id = ?",
                (eid,),
            ).fetchone()
            if existing:
                prev_status = existing["status"]
                if prev_status == STATUS_RESIGNED:
                    conn.execute(
                        """
                        UPDATE officers
                        SET name = ?, department = ?, device_id = ?, status = ?,
                            phone = NULL, face_vector = NULL, resigned_at = NULL,
                            updated_at = ?
                        WHERE employee_id = ?
                        """,
                        (name.strip(), department.strip(), device_id.strip(), STATUS_PROFILE, now, eid),
                    )
                else:
                    conn.execute(
                        """
                        UPDATE officers
                        SET name = ?, department = ?, device_id = ?, status = ?, updated_at = ?
                        WHERE employee_id = ?
                        """,
                        (name.strip(), department.strip(), device_id.strip(), STATUS_PROFILE, now, eid),
                    )
            else:
                conn.execute(
                    """
                    INSERT INTO officers (
                        employee_id, name, department, phone, device_id,
                        face_vector, status, created_at, updated_at
                    ) VALUES (?, ?, ?, NULL, ?, NULL, ?, ?, ?)
                    """,
                    (eid, name.strip(), department.strip(), device_id.strip(), STATUS_PROFILE, now, now),
                )
    except sqlite3.IntegrityError:
        return False, "该执法仪或工号已被占用，无法重复录入", None
    row = get_by_employee_id(eid)
    return True, "人员信息已写入云端数据库", row


def bind_phone(employee_id: str, phone: str) -> OfficerRow | None:
    """步骤2 通过后绑定手机号。"""
    eid = employee_id.strip().upper()
    now = _utc_now()
    with _conn() as conn:
        conn.execute(
            """
            UPDATE officers
            SET phone = ?, status = ?, updated_at = ?
            WHERE employee_id = ?
            """,
            (phone.strip(), STATUS_PHONE, now, eid),
        )
    return get_by_employee_id(eid)


def activate_officer(
    *,
    employee_id: str,
    phone: str,
    name: str,
    department: str,
    device_id: str,
    face_vector: list[float],
) -> OfficerRow:
    """步骤3 人脸通过后激活绑定（一人一台执法仪）。"""
    eid = employee_id.strip().upper()
    now = _utc_now()
    vec_json = json.dumps(face_vector)
    with _conn() as conn:
        conn.execute(
            """
            INSERT INTO officers (
                employee_id, name, department, phone, device_id,
                face_vector, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(employee_id) DO UPDATE SET
                name = excluded.name,
                department = excluded.department,
                phone = excluded.phone,
                device_id = excluded.device_id,
                face_vector = excluded.face_vector,
                status = excluded.status,
                updated_at = excluded.updated_at
            """,
            (
                eid,
                name.strip(),
                department.strip(),
                phone.strip(),
                device_id.strip(),
                vec_json,
                STATUS_ACTIVE,
                now,
                now,
            ),
        )
    row = get_by_employee_id(eid)
    assert row is not None
    return row


def save_token(token: str, employee_id: str, phone: str) -> None:
    with _conn() as conn:
        conn.execute(
            "INSERT OR REPLACE INTO auth_tokens (token, employee_id, phone, created_at) VALUES (?, ?, ?, ?)",
            (token, employee_id.strip().upper(), phone.strip(), _utc_now()),
        )


def is_token_valid(token: str) -> bool:
    with _conn() as conn:
        row = conn.execute(
            "SELECT 1 FROM auth_tokens WHERE token = ?",
            (token,),
        ).fetchone()
    return row is not None


def revoke_token(phone: str) -> None:
    with _conn() as conn:
        conn.execute("DELETE FROM auth_tokens WHERE phone = ?", (phone.strip(),))


def check_device_bindable(device_id: str, employee_id: str) -> tuple[bool, str]:
    """执法仪是否可绑定该巡查员（一台设备仅一人，含办理中的草稿）。"""
    device_id = device_id.strip()
    eid = employee_id.strip().upper()
    by_device = get_by_device(device_id)
    if by_device and by_device.employee_id != eid:
        if by_device.status == STATUS_ACTIVE:
            return False, (
                f"本执法仪已绑定巡查员 {by_device.name}（工号 {by_device.employee_id}），"
                "一台设备仅允许一人"
            )
        if by_device.status in (STATUS_PROFILE, STATUS_PHONE):
            return False, (
                f"本执法仪正在为 {by_device.name}（工号 {by_device.employee_id}）办理绑定，"
                "请换机或联系管理员"
            )
    by_emp = get_by_employee_id(eid)
    if by_emp and by_emp.status == STATUS_ACTIVE and by_emp.device_id != device_id:
        return False, "该巡查员已绑定另一台执法仪，请联系管理员解绑"
    return True, ""


def check_phone_bindable(phone: str, employee_id: str) -> tuple[bool, str]:
    phone = phone.strip()
    eid = employee_id.strip().upper()
    by_phone = get_by_phone(phone)
    if by_phone and by_phone.employee_id != eid:
        if by_phone.status == STATUS_ACTIVE:
            return False, "该手机号已绑定其他巡查员"
        if by_phone.status in (STATUS_PROFILE, STATUS_PHONE):
            return False, "该手机号正在办理其他人员绑定"
    return True, ""


def get_employee_id_by_token(token: str) -> str | None:
    with _conn() as conn:
        row = conn.execute(
            "SELECT employee_id FROM auth_tokens WHERE token = ?",
            (token,),
        ).fetchone()
    return row["employee_id"] if row else None


def offboard_officer(*, device_id: str, employee_id: str = "") -> tuple[bool, str, OfficerRow | None]:
    """执法仪端注销：解除设备绑定，云端档案标记为离职。"""
    device_id = device_id.strip()
    row = get_by_device(device_id)
    if row is None or row.status != STATUS_ACTIVE:
        return False, "本机未绑定在岗巡查员", None
    eid = employee_id.strip().upper() if employee_id else row.employee_id
    if row.employee_id != eid:
        return False, "注销人员与设备绑定不一致", None

    now = _utc_now()
    placeholder = f"__resigned__{row.employee_id}"
    with _conn() as conn:
        conn.execute(
            """
            UPDATE officers
            SET status = ?, last_device_id = device_id, device_id = ?,
                resigned_at = ?, updated_at = ?
            WHERE employee_id = ?
            """,
            (STATUS_RESIGNED, placeholder, now, now, row.employee_id),
        )
        conn.execute("DELETE FROM auth_tokens WHERE employee_id = ?", (row.employee_id,))
    updated = get_by_employee_id(row.employee_id)
    return True, "人员已注销，云端状态已更新为离职", updated
