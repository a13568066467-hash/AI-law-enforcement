"""Task room persistence."""
from __future__ import annotations

from typing import Any

from app.db.connection import _conn, _execute, _fetchone, _row_get, _utc_now
from app.db.migrations.task_rooms import ensure_schema


def _ensure(conn: Any) -> None:
    ensure_schema(conn)


def insert_room(
    *,
    room_pk: str,
    company: str,
    trtc_room_id: str,
    title: str,
    created_by: str,
) -> None:
    now = _utc_now()
    with _conn() as conn:
        _ensure(conn)
        _execute(
            conn,
            """
            INSERT INTO command_task_rooms
                (id, company, trtc_room_id, title, status, created_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'open', ?, ?, ?)
            """,
            (room_pk, company, trtc_room_id, title, created_by, now, now),
        )


def get_room(room_pk: str) -> dict[str, str] | None:
    with _conn() as conn:
        _ensure(conn)
        row = _fetchone(conn, "SELECT * FROM command_task_rooms WHERE id = ?", (room_pk,))
        if row is None:
            return None
        return {
            "id": _row_get(row, "id"),
            "company": _row_get(row, "company"),
            "trtc_room_id": _row_get(row, "trtc_room_id"),
            "title": _row_get(row, "title"),
            "status": _row_get(row, "status"),
            "created_by": _row_get(row, "created_by"),
            "created_at": _row_get(row, "created_at"),
            "updated_at": _row_get(row, "updated_at"),
        }


def list_open_rooms(company: str) -> list[dict[str, str]]:
    company_s = company.strip()
    with _conn() as conn:
        _ensure(conn)
        cur = _execute(
            conn,
            """
            SELECT * FROM command_task_rooms
            WHERE company = ? AND status = 'open'
            ORDER BY created_at DESC
            """,
            (company_s,),
        )
        rows = cur.fetchall() or []
        return [
            {
                "id": _row_get(r, "id"),
                "company": _row_get(r, "company"),
                "trtc_room_id": _row_get(r, "trtc_room_id"),
                "title": _row_get(r, "title"),
                "status": _row_get(r, "status"),
                "created_by": _row_get(r, "created_by"),
                "created_at": _row_get(r, "created_at"),
                "updated_at": _row_get(r, "updated_at"),
            }
            for r in rows
        ]


def close_room(room_pk: str) -> None:
    now = _utc_now()
    with _conn() as conn:
        _ensure(conn)
        _execute(
            conn,
            "UPDATE command_task_rooms SET status = 'closed', updated_at = ? WHERE id = ?",
            (now, room_pk),
        )
        _execute(conn, "DELETE FROM command_task_room_devices WHERE room_id = ?", (room_pk,))
        _execute(conn, "DELETE FROM command_task_room_seats WHERE room_id = ?", (room_pk,))


def find_room_id_for_device(device_id: str) -> str | None:
    with _conn() as conn:
        _ensure(conn)
        row = _fetchone(
            conn,
            "SELECT room_id FROM command_task_room_devices WHERE device_id = ?",
            (device_id.strip(),),
        )
        if row is None:
            return None
        return _row_get(row, "room_id") or None


def list_devices(room_pk: str) -> list[str]:
    with _conn() as conn:
        _ensure(conn)
        cur = _execute(
            conn,
            "SELECT device_id FROM command_task_room_devices WHERE room_id = ? ORDER BY joined_at",
            (room_pk,),
        )
        return [_row_get(r, "device_id") for r in (cur.fetchall() or [])]


def add_device(room_pk: str, device_id: str) -> None:
    with _conn() as conn:
        _ensure(conn)
        _execute(
            conn,
            """
            INSERT INTO command_task_room_devices (room_id, device_id, joined_at)
            VALUES (?, ?, ?)
            """,
            (room_pk, device_id.strip(), _utc_now()),
        )
        _execute(
            conn,
            "UPDATE command_task_rooms SET updated_at = ? WHERE id = ?",
            (_utc_now(), room_pk),
        )


def remove_device(room_pk: str, device_id: str) -> bool:
    with _conn() as conn:
        _ensure(conn)
        cur = _execute(
            conn,
            "DELETE FROM command_task_room_devices WHERE room_id = ? AND device_id = ?",
            (room_pk, device_id.strip()),
        )
        _execute(
            conn,
            "UPDATE command_task_rooms SET updated_at = ? WHERE id = ?",
            (_utc_now(), room_pk),
        )
        return (cur.rowcount or 0) > 0


def remove_device_from_any(device_id: str) -> str | None:
    room_id = find_room_id_for_device(device_id)
    if not room_id:
        return None
    remove_device(room_id, device_id)
    return room_id


def add_seat(
    *,
    room_pk: str,
    seat_session_id: str,
    employee_id: str,
    display_name: str,
    role: str = "seat",
) -> None:
    now = _utc_now()
    with _conn() as conn:
        _ensure(conn)
        _execute(
            conn,
            """
            INSERT INTO command_task_room_seats
                (room_id, seat_session_id, employee_id, display_name, role, joined_at, last_heartbeat_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (room_pk, seat_session_id, employee_id, display_name, role, now, now),
        )
        _execute(
            conn,
            "UPDATE command_task_rooms SET updated_at = ? WHERE id = ?",
            (now, room_pk),
        )


def get_seat(seat_session_id: str) -> dict[str, str] | None:
    with _conn() as conn:
        _ensure(conn)
        row = _fetchone(
            conn,
            "SELECT * FROM command_task_room_seats WHERE seat_session_id = ?",
            (seat_session_id,),
        )
        if row is None:
            return None
        return {
            "room_id": _row_get(row, "room_id"),
            "seat_session_id": _row_get(row, "seat_session_id"),
            "employee_id": _row_get(row, "employee_id"),
            "display_name": _row_get(row, "display_name"),
            "role": _row_get(row, "role"),
            "joined_at": _row_get(row, "joined_at"),
            "last_heartbeat_at": _row_get(row, "last_heartbeat_at"),
        }


def list_seats(room_pk: str) -> list[dict[str, str]]:
    with _conn() as conn:
        _ensure(conn)
        cur = _execute(
            conn,
            "SELECT * FROM command_task_room_seats WHERE room_id = ? ORDER BY joined_at",
            (room_pk,),
        )
        rows = cur.fetchall() or []
        return [
            {
                "room_id": _row_get(r, "room_id"),
                "seat_session_id": _row_get(r, "seat_session_id"),
                "employee_id": _row_get(r, "employee_id"),
                "display_name": _row_get(r, "display_name"),
                "role": _row_get(r, "role"),
                "joined_at": _row_get(r, "joined_at"),
                "last_heartbeat_at": _row_get(r, "last_heartbeat_at"),
            }
            for r in rows
        ]


def heartbeat_seat(seat_session_id: str) -> bool:
    with _conn() as conn:
        _ensure(conn)
        cur = _execute(
            conn,
            """
            UPDATE command_task_room_seats SET last_heartbeat_at = ?
            WHERE seat_session_id = ?
            """,
            (_utc_now(), seat_session_id),
        )
        return (cur.rowcount or 0) > 0


def remove_seat(seat_session_id: str) -> str | None:
    seat = get_seat(seat_session_id)
    if seat is None:
        return None
    with _conn() as conn:
        _ensure(conn)
        _execute(
            conn,
            "DELETE FROM command_task_room_seats WHERE seat_session_id = ?",
            (seat_session_id,),
        )
    return seat["room_id"]
