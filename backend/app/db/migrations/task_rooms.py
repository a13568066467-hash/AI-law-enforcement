"""Company task room tables schema."""
from __future__ import annotations

from typing import Any

from app.db.connection import use_mysql


def ensure_schema(conn: Any) -> None:
    if use_mysql():
        conn.cursor().execute(
            """
            CREATE TABLE IF NOT EXISTS command_task_rooms (
                id VARCHAR(64) PRIMARY KEY,
                company VARCHAR(128) NOT NULL,
                trtc_room_id VARCHAR(128) NOT NULL,
                title VARCHAR(256) NOT NULL DEFAULT '',
                status VARCHAR(32) NOT NULL DEFAULT 'open',
                created_by VARCHAR(128) NOT NULL DEFAULT '',
                created_at VARCHAR(64) NOT NULL,
                updated_at VARCHAR(64) NOT NULL,
                INDEX idx_task_rooms_company_status (company, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """
        )
        conn.cursor().execute(
            """
            CREATE TABLE IF NOT EXISTS command_task_room_devices (
                room_id VARCHAR(64) NOT NULL,
                device_id VARCHAR(128) NOT NULL,
                joined_at VARCHAR(64) NOT NULL,
                PRIMARY KEY (room_id, device_id),
                UNIQUE KEY uq_task_device_one_room (device_id),
                INDEX idx_task_devices_room (room_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """
        )
        conn.cursor().execute(
            """
            CREATE TABLE IF NOT EXISTS command_task_room_seats (
                room_id VARCHAR(64) NOT NULL,
                seat_session_id VARCHAR(64) NOT NULL,
                employee_id VARCHAR(128) NOT NULL DEFAULT '',
                display_name VARCHAR(128) NOT NULL DEFAULT '',
                role VARCHAR(32) NOT NULL DEFAULT 'seat',
                joined_at VARCHAR(64) NOT NULL,
                last_heartbeat_at VARCHAR(64) NOT NULL DEFAULT '',
                PRIMARY KEY (seat_session_id),
                INDEX idx_task_seats_room (room_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """
        )
        return

    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS command_task_rooms (
            id TEXT PRIMARY KEY,
            company TEXT NOT NULL,
            trtc_room_id TEXT NOT NULL,
            title TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'open',
            created_by TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS command_task_room_devices (
            room_id TEXT NOT NULL,
            device_id TEXT NOT NULL,
            joined_at TEXT NOT NULL,
            PRIMARY KEY (room_id, device_id),
            UNIQUE (device_id)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS command_task_room_seats (
            room_id TEXT NOT NULL,
            seat_session_id TEXT NOT NULL PRIMARY KEY,
            employee_id TEXT NOT NULL DEFAULT '',
            display_name TEXT NOT NULL DEFAULT '',
            role TEXT NOT NULL DEFAULT 'seat',
            joined_at TEXT NOT NULL,
            last_heartbeat_at TEXT NOT NULL DEFAULT ''
        )
        """
    )
