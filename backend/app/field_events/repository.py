"""现场事件工单表。"""
from __future__ import annotations

from typing import Any

STATUS_PENDING = "pending"
STATUS_IN_PROGRESS = "in_progress"
STATUS_CLOSED = "closed"

VALID_STATUSES = frozenset({STATUS_PENDING, STATUS_IN_PROGRESS, STATUS_CLOSED})


def init_field_event_tickets_table(conn: Any) -> None:
    from app.db.migrations.field_event_tickets import ensure_schema

    ensure_schema(conn)
