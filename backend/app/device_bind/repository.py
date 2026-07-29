"""扫码绑定表访问：token、占用、使用历史。"""
from __future__ import annotations

from typing import Any

from app.db.connection import _conn, _execute, _fetchone, _row_get, _utc_now, use_mysql
from app.officers import repository as officer_db
from app.recorders import repository as recorder_db

TOKEN_PENDING = "pending"
TOKEN_CONSUMED = "consumed"
TOKEN_EXPIRED = "expired"

END_UNBIND = "unbind"
END_SHUTDOWN = "shutdown"
END_ADMIN = "admin"
END_MIGRATE = "migrate"


def init_device_bind_tables(conn: Any) -> None:
    from app.db.migrations.device_bind import ensure_schema

    ensure_schema(conn)


def get_token_row(conn: Any, token: str) -> Any | None:
    return _fetchone(
        conn,
        "SELECT * FROM device_bind_tokens WHERE token = ?",
        (token.strip(),),
    )


def mark_token_expired(conn: Any, token: str) -> None:
    _execute(
        conn,
        "UPDATE device_bind_tokens SET status = ? WHERE token = ?",
        (TOKEN_EXPIRED, token),
    )


def reject_token(conn: Any, token: str, reason: str) -> None:
    _execute(
        conn,
        """
        UPDATE device_bind_tokens
        SET status = ?, reject_reason = ?
        WHERE token = ? AND status = ?
        """,
        (TOKEN_CONSUMED, reason, token, TOKEN_PENDING),
    )


def expire_pending_tokens_for_device(conn: Any, device_id: str) -> None:
    _execute(
        conn,
        """
        UPDATE device_bind_tokens
        SET status = ?
        WHERE device_id = ? AND status = ?
        """,
        (TOKEN_EXPIRED, device_id, TOKEN_PENDING),
    )


def insert_pending_token(
    conn: Any,
    *,
    device_id: str,
    token: str,
    expires_at: str,
    created_at: str,
) -> None:
    _execute(
        conn,
        """
        INSERT INTO device_bind_tokens (
            device_id, token, status, expires_at, created_at
        ) VALUES (?, ?, ?, ?, ?)
        """,
        (device_id, token, TOKEN_PENDING, expires_at, created_at),
    )


def consume_token(
    conn: Any,
    *,
    token: str,
    employee_id: str,
    session_token: str,
) -> None:
    _execute(
        conn,
        """
        UPDATE device_bind_tokens
        SET status = ?, employee_id = ?, session_token = ?, reject_reason = NULL
        WHERE token = ?
        """,
        (TOKEN_CONSUMED, employee_id, session_token, token),
    )


def get_occupancy_by_device(conn: Any, device_id: str) -> Any | None:
    return _fetchone(
        conn,
        "SELECT * FROM device_occupancy WHERE device_id = ?",
        (device_id.strip(),),
    )


def get_occupancy_by_employee(conn: Any, employee_id: str) -> Any | None:
    eid = officer_db.normalize_employee_id(employee_id)
    return _fetchone(
        conn,
        "SELECT * FROM device_occupancy WHERE employee_id = ?",
        (eid,),
    )


def get_occupancy_by_session_token(conn: Any, session_token: str) -> Any | None:
    return _fetchone(
        conn,
        "SELECT * FROM device_occupancy WHERE session_token = ?",
        (session_token.strip(),),
    )


def write_history(
    conn: Any,
    *,
    device_id: str,
    employee_id: str,
    started_at: str,
    end_reason: str,
) -> None:
    _execute(
        conn,
        """
        INSERT INTO device_usage_history (
            device_id, employee_id, started_at, ended_at, end_reason
        ) VALUES (?, ?, ?, ?, ?)
        """,
        (device_id, employee_id, started_at, _utc_now(), end_reason),
    )


def insert_auth_token(
    conn: Any,
    *,
    token: str,
    employee_id: str,
    phone: str,
    created_at: str,
) -> None:
    params = (token, officer_db.normalize_employee_id(employee_id), phone.strip(), created_at)
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
        return
    _execute(
        conn,
        "INSERT OR REPLACE INTO auth_tokens (token, employee_id, phone, created_at) VALUES (?, ?, ?, ?)",
        params,
    )


def delete_auth_token(conn: Any, token: str) -> None:
    _execute(conn, "DELETE FROM auth_tokens WHERE token = ?", (token,))


def recorder_use_row(conn: Any, device_id: str) -> tuple[bool, str]:
    row = _fetchone(
        conn,
        "SELECT in_use, employee_id FROM recorders WHERE device_id = ?",
        (device_id,),
    )
    if row is None:
        return False, ""
    in_use = bool(int(_row_get(row, "in_use", "0") or "0"))
    eid = _row_get(row, "employee_id")
    return in_use, eid


def upsert_occupancy(
    conn: Any,
    *,
    device_id: str,
    employee_id: str,
    session_token: str,
    started_at: str,
) -> str:
    """写入或刷新占用行，返回被替换的旧 session_token（若有）。"""
    occ = get_occupancy_by_device(conn, device_id)
    if occ is not None:
        old = _row_get(occ, "session_token")
        _execute(
            conn,
            """
            UPDATE device_occupancy
            SET employee_id = ?, session_token = ?, started_at = ?
            WHERE device_id = ?
            """,
            (employee_id, session_token, started_at, device_id),
        )
        return old

    emp_occ = get_occupancy_by_employee(conn, employee_id)
    if emp_occ is not None:
        other_device = _row_get(emp_occ, "device_id")
        if other_device != device_id:
            return ""
        old = _row_get(emp_occ, "session_token")
        _execute(conn, "DELETE FROM device_occupancy WHERE employee_id = ?", (employee_id,))

    _execute(
        conn,
        """
        INSERT INTO device_occupancy (device_id, employee_id, started_at, session_token)
        VALUES (?, ?, ?, ?)
        """,
        (device_id, employee_id, started_at, session_token),
    )
    return ""


def legacy_recorder_blocks(
    conn: Any,
    device_id: str,
    employee_id: str,
) -> tuple[bool, str]:
    """旧模型 recorders.in_use 与 occupancy 表不一致时的占用校验。"""
    occ = get_occupancy_by_device(conn, device_id)
    if occ is not None:
        return False, ""
    in_use, rec_eid = recorder_use_row(conn, device_id)
    if not in_use or not rec_eid:
        return False, ""
    if rec_eid == employee_id:
        return False, ""
    return True, f"本执法仪编号已被工号 {rec_eid} 使用中，无法绑定"


def clear_legacy_recorder_if_stuck(conn: Any, device_id: str) -> bool:
    """occupancy 已空但 recorders 仍 in_use 时清空为空闲。"""
    occ = get_occupancy_by_device(conn, device_id)
    if occ is not None:
        return False
    in_use, _ = recorder_use_row(conn, device_id)
    if not in_use:
        return False
    now = _utc_now()
    _execute(
        conn,
        """
        UPDATE recorders
        SET in_use = 0, employee_id = NULL, binding_phase = ?,
            unbound_at = ?, updated_at = ?
        WHERE device_id = ?
        """,
        (recorder_db.PHASE_IDLE, now, now, device_id),
    )
    return True


def end_occupancy(conn: Any, device_id: str, end_reason: str) -> bool:
    occ = get_occupancy_by_device(conn, device_id)
    if occ is None:
        return False
    eid = _row_get(occ, "employee_id")
    started = _row_get(occ, "started_at")
    session_token = _row_get(occ, "session_token")
    write_history(
        conn,
        device_id=device_id,
        employee_id=eid,
        started_at=started,
        end_reason=end_reason,
    )
    _execute(conn, "DELETE FROM device_occupancy WHERE device_id = ?", (device_id,))
    if session_token:
        delete_auth_token(conn, session_token)
    now = _utc_now()
    _execute(
        conn,
        """
        UPDATE recorders
        SET in_use = 0, employee_id = NULL, binding_phase = ?,
            unbound_at = ?, last_seen_at = ?, updated_at = ?
        WHERE device_id = ?
        """,
        (recorder_db.PHASE_IDLE, now, now, now, device_id),
    )
    return True


# re-export connection opener for service transactions
conn = _conn
row_get = _row_get
