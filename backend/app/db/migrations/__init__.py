"""Centralized schema ensure entrypoint."""
from __future__ import annotations

from app.db.connection import _conn
from app.db.migrations import device_bind, field_event_tickets, officers, recorders


def run_all_migrations() -> None:
    """Fixed order: officers → recorders → device_bind → field_events, then backfill."""
    with _conn() as conn:
        officers.ensure_schema(conn)
        recorders.ensure_schema(conn)
        device_bind.ensure_schema(conn)
        field_event_tickets.ensure_schema(conn)
    try:
        from app.recorders.repository import backfill_from_officers

        backfill_from_officers()
    except Exception:
        pass
