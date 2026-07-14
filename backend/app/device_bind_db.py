"""扫码绑定：token、当前占用、使用历史表。"""
from __future__ import annotations

from typing import Any

from . import officer_db

TOKEN_PENDING = "pending"
TOKEN_CONSUMED = "consumed"
TOKEN_EXPIRED = "expired"

END_UNBIND = "unbind"
END_SHUTDOWN = "shutdown"
END_ADMIN = "admin"
END_MIGRATE = "migrate"


def init_device_bind_tables(conn: Any) -> None:
    if officer_db.use_mysql():
        conn.cursor().execute(
            """
            CREATE TABLE IF NOT EXISTS device_bind_tokens (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                device_id VARCHAR(128) NOT NULL,
                token VARCHAR(128) NOT NULL,
                status VARCHAR(16) NOT NULL DEFAULT 'pending',
                expires_at VARCHAR(64) NOT NULL,
                session_token VARCHAR(128) NULL,
                employee_id VARCHAR(32) NULL,
                reject_reason VARCHAR(512) NULL,
                created_at VARCHAR(64) NOT NULL,
                UNIQUE KEY uq_bind_token (token),
                KEY idx_bind_device (device_id),
                KEY idx_bind_status (status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """
        )
        conn.cursor().execute(
            """
            CREATE TABLE IF NOT EXISTS device_occupancy (
                device_id VARCHAR(128) NOT NULL PRIMARY KEY,
                employee_id VARCHAR(32) NOT NULL,
                started_at VARCHAR(64) NOT NULL,
                session_token VARCHAR(128) NOT NULL,
                UNIQUE KEY uq_occupancy_employee (employee_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """
        )
        conn.cursor().execute(
            """
            CREATE TABLE IF NOT EXISTS device_usage_history (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                device_id VARCHAR(128) NOT NULL,
                employee_id VARCHAR(32) NOT NULL,
                started_at VARCHAR(64) NOT NULL,
                ended_at VARCHAR(64) NOT NULL,
                end_reason VARCHAR(32) NOT NULL,
                KEY idx_usage_device (device_id),
                KEY idx_usage_employee (employee_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """
        )
        return

    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS device_bind_tokens (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL,
            token TEXT NOT NULL UNIQUE,
            status TEXT NOT NULL DEFAULT 'pending',
            expires_at TEXT NOT NULL,
            session_token TEXT,
            employee_id TEXT,
            reject_reason TEXT,
            created_at TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_bind_device ON device_bind_tokens(device_id);
        CREATE INDEX IF NOT EXISTS idx_bind_status ON device_bind_tokens(status);

        CREATE TABLE IF NOT EXISTS device_occupancy (
            device_id TEXT PRIMARY KEY,
            employee_id TEXT NOT NULL UNIQUE,
            started_at TEXT NOT NULL,
            session_token TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS device_usage_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL,
            employee_id TEXT NOT NULL,
            started_at TEXT NOT NULL,
            ended_at TEXT NOT NULL,
            end_reason TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_usage_device ON device_usage_history(device_id);
        CREATE INDEX IF NOT EXISTS idx_usage_employee ON device_usage_history(employee_id);
        """
    )
