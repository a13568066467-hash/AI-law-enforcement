"""Centralized schema ensure entrypoint."""
from __future__ import annotations

from app.db.connection import _conn
from app.db.migrations import device_bind, field_event_tickets, officers, recorders, task_rooms


def run_all_migrations() -> None:
    """Fixed order: officers → recorders → device_bind → field_events → task_rooms."""
    with _conn() as conn:
        officers.ensure_schema(conn)
        recorders.ensure_schema(conn)
        device_bind.ensure_schema(conn)
        field_event_tickets.ensure_schema(conn)
        task_rooms.ensure_schema(conn)
    try:
        from app.recorders.repository import backfill_from_officers

        backfill_from_officers()
    except Exception:
        pass
