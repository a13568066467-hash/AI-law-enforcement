"""Field event tickets schema."""
from __future__ import annotations

from typing import Any

from app.db.connection import use_mysql


def ensure_schema(conn: Any) -> None:
    if use_mysql():
        conn.cursor().execute(
            """
            CREATE TABLE IF NOT EXISTS field_event_tickets (
                id VARCHAR(64) NOT NULL PRIMARY KEY,
                company VARCHAR(128) NOT NULL,
                device_id VARCHAR(128) NOT NULL,
                employee_id VARCHAR(32) NOT NULL,
                officer_name VARCHAR(128) NOT NULL DEFAULT '',
                body TEXT NOT NULL,
                status VARCHAR(32) NOT NULL DEFAULT 'pending',
                created_at VARCHAR(64) NOT NULL,
                updated_at VARCHAR(64) NOT NULL,
                KEY idx_fet_company_created (company, created_at),
                KEY idx_fet_device (device_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """
        )
        return

    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS field_event_tickets (
            id TEXT PRIMARY KEY,
            company TEXT NOT NULL,
            device_id TEXT NOT NULL,
            employee_id TEXT NOT NULL,
            officer_name TEXT NOT NULL DEFAULT '',
            body TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_fet_company_created
            ON field_event_tickets(company, created_at);
        CREATE INDEX IF NOT EXISTS idx_fet_device ON field_event_tickets(device_id);
        """
    )
