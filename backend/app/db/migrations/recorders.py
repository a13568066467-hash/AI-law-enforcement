"""Recorders table schema."""
from __future__ import annotations

from typing import Any

from app.db.connection import use_mysql


def ensure_schema(conn: Any) -> None:
    if use_mysql():
        conn.cursor().execute(
            """
            CREATE TABLE IF NOT EXISTS recorders (
                device_id VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '设备编号',
                device_name VARCHAR(128) NOT NULL COMMENT '设备名称',
                model VARCHAR(64) NOT NULL DEFAULT 'DSJ-ZECN6A1' COMMENT '型号',
                in_use TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否被使用',
                employee_id VARCHAR(32) NULL COMMENT '当前绑定工号',
                is_faulty TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否故障损坏',
                fault_note VARCHAR(512) NULL COMMENT '故障说明',
                binding_phase TINYINT NOT NULL DEFAULT 0 COMMENT '0空闲 1办理中 2在岗',
                bound_at VARCHAR(64) NULL COMMENT '最近绑定时间',
                unbound_at VARCHAR(64) NULL COMMENT '最近解绑时间',
                last_seen_at VARCHAR(64) NULL COMMENT '最后在线时间',
                remark VARCHAR(512) NULL COMMENT '备注',
                company VARCHAR(128) NULL COMMENT '所属公司',
                created_at VARCHAR(64) NOT NULL,
                updated_at VARCHAR(64) NOT NULL,
                KEY idx_recorders_in_use (in_use),
                KEY idx_recorders_employee (employee_id),
                KEY idx_recorders_faulty (is_faulty)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """
        )
        _migrate_recorders_company_mysql(conn)
        return

    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS recorders (
            device_id TEXT PRIMARY KEY,
            device_name TEXT NOT NULL,
            model TEXT NOT NULL DEFAULT 'DSJ-ZECN6A1',
            in_use INTEGER NOT NULL DEFAULT 0,
            employee_id TEXT,
            is_faulty INTEGER NOT NULL DEFAULT 0,
            fault_note TEXT,
            binding_phase INTEGER NOT NULL DEFAULT 0,
            bound_at TEXT,
            unbound_at TEXT,
            last_seen_at TEXT,
            remark TEXT,
            company TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """
    )
    _migrate_recorders_company(conn)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_recorders_in_use ON recorders(in_use)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_recorders_employee ON recorders(employee_id)")


def _migrate_recorders_company_mysql(conn: Any) -> None:
    cur = conn.cursor()
    cur.execute(
        """
        SELECT COLUMN_NAME FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recorders'
        """
    )
    cols = {row["COLUMN_NAME"] for row in cur.fetchall()}
    if "company" not in cols:
        cur.execute("ALTER TABLE recorders ADD COLUMN company VARCHAR(128) NULL COMMENT '所属公司'")


def _migrate_recorders_company(conn: Any) -> None:
    if use_mysql():
        _migrate_recorders_company_mysql(conn)
        return
    cols = {row[1] for row in conn.execute("PRAGMA table_info(recorders)")}
    if "company" not in cols:
        conn.execute("ALTER TABLE recorders ADD COLUMN company TEXT")
