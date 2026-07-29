"""现场事件工单表访问。"""
from __future__ import annotations

from typing import Any

from app.db.connection import _conn, _execute, _fetchone, _row_get, _utc_now

STATUS_PENDING = "pending"
STATUS_IN_PROGRESS = "in_progress"
STATUS_CLOSED = "closed"

VALID_STATUSES = frozenset({STATUS_PENDING, STATUS_IN_PROGRESS, STATUS_CLOSED})


def init_field_event_tickets_table(conn: Any) -> None:
    from app.db.migrations.field_event_tickets import ensure_schema

    ensure_schema(conn)


def row_to_ticket(row: Any) -> dict[str, Any]:
    return {
        "id": _row_get(row, "id"),
        "company": _row_get(row, "company") or "",
        "device_id": _row_get(row, "device_id") or "",
        "employee_id": _row_get(row, "employee_id") or "",
        "officer_name": _row_get(row, "officer_name") or "",
        "body": _row_get(row, "body") or "",
        "status": _row_get(row, "status") or STATUS_PENDING,
        "created_at": _row_get(row, "created_at") or "",
        "updated_at": _row_get(row, "updated_at") or "",
    }


def insert_ticket(
    conn: Any,
    *,
    ticket_id: str,
    company: str,
    device_id: str,
    employee_id: str,
    officer_name: str,
    body: str,
    created_at: str,
) -> None:
    _execute(
        conn,
        """
        INSERT INTO field_event_tickets (
            id, company, device_id, employee_id, officer_name,
            body, status, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            ticket_id,
            company,
            device_id,
            employee_id,
            officer_name,
            body,
            STATUS_PENDING,
            created_at,
            created_at,
        ),
    )


def get_by_id(ticket_id: str) -> dict[str, Any] | None:
    tid = (ticket_id or "").strip()
    if not tid:
        return None
    with _conn() as conn:
        row = _fetchone(
            conn,
            "SELECT * FROM field_event_tickets WHERE id = ?",
            (tid,),
        )
    return row_to_ticket(row) if row else None


def list_by_company(company: str) -> list[dict[str, Any]]:
    company = (company or "").strip()
    if not company:
        return []
    with _conn() as conn:
        rows = _execute(
            conn,
            """
            SELECT * FROM field_event_tickets
            WHERE company = ?
            ORDER BY created_at DESC
            """,
            (company,),
        ).fetchall()
    return [row_to_ticket(r) for r in rows]


def update_status_row(*, ticket_id: str, company: str, status: str) -> None:
    now = _utc_now()
    with _conn() as conn:
        _execute(
            conn,
            """
            UPDATE field_event_tickets
            SET status = ?, updated_at = ?
            WHERE id = ? AND company = ?
            """,
            (status, now, ticket_id, company),
        )


conn = _conn
utc_now = _utc_now
