"""巡查员档案持久化（SQLite 默认，可切换 MySQL）。"""
from __future__ import annotations

import json
import os
import random
import re
import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

try:
    import pymysql
    from pymysql.cursors import DictCursor
    from pymysql.err import IntegrityError as MySQLIntegrityError
except ImportError:  # pragma: no cover
    pymysql = None  # type: ignore[assignment]
    DictCursor = None  # type: ignore[assignment,misc]
    MySQLIntegrityError = type("_MissingMySQL", (), {})  # type: ignore[assignment,misc]

STATUS_PROFILE = "profile"  # 步骤1：人员信息已入库
STATUS_PHONE = "phone_verified"  # 步骤2：手机号已验证
STATUS_ACTIVE = "active"  # 步骤3：人脸完成，执法仪绑定生效
STATUS_RESIGNED = "resigned"  # 执法仪端注销，云端保留档案

EMPLOYEE_ID_RE = re.compile(r"^\d{6}$")
LEGACY_ID_CARD_COLUMN = "Identity card"

_INTEGRITY_ERRORS: tuple[type[BaseException], ...] = (sqlite3.IntegrityError,)
if pymysql is not None:
    _INTEGRITY_ERRORS = (sqlite3.IntegrityError, MySQLIntegrityError)


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
    id_card: str = ""
    company: str = ""
    position: str = ""


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


def use_mysql() -> bool:
    driver = os.getenv("OFFICER_DB_DRIVER", "").strip().lower()
    if driver == "sqlite":
        return False
    if driver == "mysql":
        return True
    return bool(os.getenv("MYSQL_HOST", "").strip())


def db_backend_label() -> str:
    if use_mysql():
        host = os.getenv("MYSQL_HOST", "127.0.0.1")
        port = os.getenv("MYSQL_PORT", "3306")
        database = os.getenv("MYSQL_DATABASE", "aifieldcam")
        return f"mysql://{host}:{port}/{database}"
    return f"sqlite://{_db_path()}"


def _db_path() -> Path:
    raw = os.getenv("OFFICER_DB_PATH", "").strip()
    if raw:
        return Path(raw)
    return Path(__file__).resolve().parent.parent / "data" / "officers.db"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _adapt_sql(sql: str) -> str:
    return sql.replace("?", "%s") if use_mysql() else sql


def _row_get(row: Any, key: str, default: str = "") -> str:
    if row is None:
        return default
    try:
        val = row[key]
    except (KeyError, IndexError, TypeError):
        return default
    return default if val is None else str(val)


def _row_keys(row: Any) -> set[str]:
    if row is None:
        return set()
    if isinstance(row, dict):
        return set(row.keys())
    return set(row.keys())


def _mysql_connect():
    if pymysql is None:
        raise RuntimeError("未安装 pymysql，请执行: pip install pymysql")
    return pymysql.connect(
        host=os.getenv("MYSQL_HOST", "127.0.0.1"),
        port=int(os.getenv("MYSQL_PORT", "3306")),
        user=os.getenv("MYSQL_USER", "root"),
        password=os.getenv("MYSQL_PASSWORD", ""),
        database=os.getenv("MYSQL_DATABASE", "aifieldcam"),
        charset="utf8mb4",
        cursorclass=DictCursor,
        autocommit=False,
    )


@contextmanager
def _conn() -> Iterator[Any]:
    if use_mysql():
        conn = _mysql_connect()
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()
        return

    path = _db_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def _execute(conn: Any, sql: str, params: tuple[Any, ...] = ()) -> Any:
    cur = conn.cursor()
    cur.execute(_adapt_sql(sql), params)
    return cur


def _fetchone(conn: Any, sql: str, params: tuple[Any, ...] = ()) -> Any:
    cur = _execute(conn, sql, params)
    return cur.fetchone()


def ping() -> None:
    """验证数据库可连接。"""
    with _conn() as conn:
        if use_mysql():
            conn.cursor().execute("SELECT 1")
        else:
            conn.execute("SELECT 1")


def init_db() -> None:
    with _conn() as conn:
        if use_mysql():
            conn.cursor().execute(
                """
                CREATE TABLE IF NOT EXISTS officers (
                    employee_id VARCHAR(32) NOT NULL PRIMARY KEY,
                    name VARCHAR(64) NOT NULL,
                    department VARCHAR(128) NOT NULL,
                    phone VARCHAR(16) NULL,
                    device_id VARCHAR(128) NOT NULL,
                    face_vector MEDIUMTEXT NULL,
                    status VARCHAR(32) NOT NULL,
                    created_at VARCHAR(64) NOT NULL,
                    updated_at VARCHAR(64) NOT NULL,
                    last_device_id VARCHAR(128) NULL,
                    resigned_at VARCHAR(64) NULL,
                    UNIQUE KEY uq_officers_phone (phone),
                    UNIQUE KEY uq_officers_device (device_id),
                    KEY idx_officers_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """
            )
            conn.cursor().execute(
                """
                CREATE TABLE IF NOT EXISTS auth_tokens (
                    token VARCHAR(128) NOT NULL PRIMARY KEY,
                    employee_id VARCHAR(32) NOT NULL,
                    phone VARCHAR(16) NOT NULL,
                    created_at VARCHAR(64) NOT NULL,
                    KEY idx_auth_tokens_employee (employee_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """
            )
        else:
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


def _migrate(conn: Any) -> None:
    if use_mysql():
        cur = conn.cursor()
        cur.execute(
            """
            SELECT COLUMN_NAME FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'officers'
            """
        )
        cols = {row["COLUMN_NAME"] for row in cur.fetchall()}
        if "last_device_id" not in cols:
            cur.execute("ALTER TABLE officers ADD COLUMN last_device_id VARCHAR(128) NULL")
        if "resigned_at" not in cols:
            cur.execute("ALTER TABLE officers ADD COLUMN resigned_at VARCHAR(64) NULL")
        for col, ddl in (
            ("id_card", "VARCHAR(32) NULL"),
            ("company", "VARCHAR(128) NULL"),
            ("position", "VARCHAR(64) NULL"),
        ):
            if col not in cols:
                cur.execute(f"ALTER TABLE officers ADD COLUMN {col} {ddl}")
                cols.add(col)
        _migrate_mysql_legacy_id_card(cur, cols)
        return

    cols = {row[1] for row in conn.execute("PRAGMA table_info(officers)")}
    if "last_device_id" not in cols:
        conn.execute("ALTER TABLE officers ADD COLUMN last_device_id TEXT")
    if "resigned_at" not in cols:
        conn.execute("ALTER TABLE officers ADD COLUMN resigned_at TEXT")
    for col in ("id_card", "company", "position"):
        if col not in cols:
            conn.execute(f"ALTER TABLE officers ADD COLUMN {col} TEXT")


def _migrate_mysql_legacy_id_card(cur: Any, cols: set[str]) -> None:
    """修复 init_mysql.sql 误建的 `Identity card` 列与复合主键，统一到 id_card。"""
    if LEGACY_ID_CARD_COLUMN not in cols:
        return
    try:
        cur.execute(
            f"""
            UPDATE officers
            SET id_card = COALESCE(NULLIF(id_card, ''), `{LEGACY_ID_CARD_COLUMN}`)
            WHERE `{LEGACY_ID_CARD_COLUMN}` IS NOT NULL AND `{LEGACY_ID_CARD_COLUMN}` != ''
            """
        )
        cur.execute("ALTER TABLE officers DROP PRIMARY KEY")
        cur.execute(f"ALTER TABLE officers DROP COLUMN `{LEGACY_ID_CARD_COLUMN}`")
        cur.execute("ALTER TABLE officers ADD PRIMARY KEY (employee_id)")
    except Exception:
        pass


def _row_to_officer(row: Any | None) -> OfficerRow | None:
    if row is None:
        return None
    vec_raw = row["face_vector"]
    vec: list[float] = json.loads(vec_raw) if vec_raw else []
    keys = _row_keys(row)
    return OfficerRow(
        employee_id=_row_get(row, "employee_id"),
        name=_row_get(row, "name"),
        department=_row_get(row, "department"),
        phone=_row_get(row, "phone"),
        device_id=_row_get(row, "device_id"),
        face_vector=vec,
        status=_row_get(row, "status"),
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
    with _conn() as conn:
        row = _fetchone(conn, "SELECT * FROM officers WHERE phone = ?", (phone.strip(),))
    return _row_to_officer(row)


def get_by_device(device_id: str) -> OfficerRow | None:
    with _conn() as conn:
        row = _fetchone(conn, "SELECT * FROM officers WHERE device_id = ?", (device_id.strip(),))
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
    id_card: str = "",
    company: str = "",
    position: str = "",
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
    dept = department.strip() or "待完善"
    now = _utc_now()
    company_s = company.strip()
    position_s = position.strip()
    try:
        with _conn() as conn:
            existing = _fetchone(conn, "SELECT * FROM officers WHERE employee_id = ?", (eid,))
            if existing:
                prev_status = existing["status"]
                if prev_status == STATUS_RESIGNED:
                    _execute(
                        conn,
                        """
                        UPDATE officers
                        SET name = ?, department = ?, device_id = ?, status = ?,
                            phone = NULL, face_vector = NULL, resigned_at = NULL,
                            id_card = ?, company = ?, position = ?,
                            updated_at = ?
                        WHERE employee_id = ?
                        """,
                        (
                            name.strip(), dept, device_id.strip(), STATUS_PROFILE,
                            id_card_s or None, company_s or None, position_s or None,
                            now, eid,
                        ),
                    )
                else:
                    _execute(
                        conn,
                        """
                        UPDATE officers
                        SET name = ?, department = ?, device_id = ?, status = ?,
                            id_card = ?, company = ?, position = ?,
                            updated_at = ?
                        WHERE employee_id = ?
                        """,
                        (
                            name.strip(), dept, device_id.strip(), STATUS_PROFILE,
                            id_card_s or None, company_s or None, position_s or None,
                            now, eid,
                        ),
                    )
            else:
                _execute(
                    conn,
                    """
                    INSERT INTO officers (
                        employee_id, name, department, phone, device_id,
                        face_vector, status, created_at, updated_at,
                        id_card, company, position
                    ) VALUES (?, ?, ?, NULL, ?, NULL, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        eid, name.strip(), dept, device_id.strip(), STATUS_PROFILE, now, now,
                        id_card_s or None, company_s or None, position_s or None,
                    ),
                )
    except _INTEGRITY_ERRORS:
        return False, "该执法仪或工号已被占用，无法重复录入", None
    except Exception as exc:  # pragma: no cover
        return False, f"数据库写入失败：{exc}", None
    row = get_by_employee_id(eid)
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


def bind_phone(employee_id: str, phone: str) -> OfficerRow | None:
    """步骤2 通过后绑定手机号。"""
    eid = normalize_employee_id(employee_id)
    now = _utc_now()
    with _conn() as conn:
        _execute(
            conn,
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
    id_card: str = "",
    company: str = "",
    position: str = "",
) -> OfficerRow:
    """步骤3 人脸通过后激活绑定（一人一台执法仪）。"""
    eid = normalize_employee_id(employee_id)
    now = _utc_now()
    vec_json = json.dumps(face_vector)
    dept = department.strip() or "待完善"
    params = (
        eid,
        name.strip(),
        dept,
        phone.strip(),
        device_id.strip(),
        vec_json,
        STATUS_ACTIVE,
        now,
        now,
        id_card.strip().upper() or None,
        company.strip() or None,
        position.strip() or None,
    )
    with _conn() as conn:
        if use_mysql():
            _execute(
                conn,
                """
                INSERT INTO officers (
                    employee_id, name, department, phone, device_id,
                    face_vector, status, created_at, updated_at,
                    id_card, company, position
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
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
                    employee_id, name, department, phone, device_id,
                    face_vector, status, created_at, updated_at,
                    id_card, company, position
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(employee_id) DO UPDATE SET
                    name = excluded.name,
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
    """执法仪是否可绑定该巡查员（一台设备仅一人，含办理中的草稿）。"""
    device_id = device_id.strip()
    eid = normalize_employee_id(employee_id)
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
    eid = normalize_employee_id(employee_id)
    by_phone = get_by_phone(phone)
    if by_phone and by_phone.employee_id != eid:
        if by_phone.status == STATUS_ACTIVE:
            return False, "该手机号已绑定其他巡查员"
        if by_phone.status in (STATUS_PROFILE, STATUS_PHONE):
            return False, "该手机号正在办理其他人员绑定"
    return True, ""


def get_employee_id_by_token(token: str) -> str | None:
    with _conn() as conn:
        row = _fetchone(conn, "SELECT employee_id FROM auth_tokens WHERE token = ?", (token,))
    return _row_get(row, "employee_id") if row else None


def offboard_officer(*, device_id: str, employee_id: str = "") -> tuple[bool, str, OfficerRow | None]:
    """执法仪端注销：解除设备绑定，云端档案标记为离职。"""
    device_id = device_id.strip()
    row = get_by_device(device_id)
    if row is None or row.status != STATUS_ACTIVE:
        return False, "本机未绑定在岗巡查员", None
    eid = normalize_employee_id(employee_id) if employee_id else row.employee_id
    if row.employee_id != eid:
        return False, "注销人员与设备绑定不一致", None

    now = _utc_now()
    placeholder = f"__resigned__{row.employee_id}"
    with _conn() as conn:
        _execute(
            conn,
            """
            UPDATE officers
            SET status = ?, last_device_id = device_id, device_id = ?,
                resigned_at = ?, updated_at = ?
            WHERE employee_id = ?
            """,
            (STATUS_RESIGNED, placeholder, now, now, row.employee_id),
        )
        _execute(conn, "DELETE FROM auth_tokens WHERE employee_id = ?", (row.employee_id,))
    updated = get_by_employee_id(row.employee_id)
    return True, "人员已注销，云端状态已更新为离职", updated
