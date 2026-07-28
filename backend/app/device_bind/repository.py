"""扫码绑定：token、当前占用、使用历史表。"""
from __future__ import annotations

from typing import Any

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
