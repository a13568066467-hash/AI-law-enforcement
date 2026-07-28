"""Officers + auth_tokens schema."""
from __future__ import annotations

from typing import Any

from app.db.connection import use_mysql

LEGACY_ID_CARD_COLUMN = "Identity card"


def ensure_schema(conn: Any) -> None:
    if use_mysql():
        conn.cursor().execute(
            """
            CREATE TABLE IF NOT EXISTS officers (
                employee_id VARCHAR(32) NOT NULL PRIMARY KEY,
                name VARCHAR(64) NOT NULL,
                gender VARCHAR(8) NOT NULL DEFAULT '未知',
                department VARCHAR(128) NOT NULL,
                phone VARCHAR(16) NULL,
                device_id VARCHAR(128) NOT NULL,
                face_vector MEDIUMTEXT NULL,
                status TINYINT NOT NULL DEFAULT 2,
                created_at VARCHAR(64) NOT NULL,
                updated_at VARCHAR(64) NOT NULL,
                last_device_id VARCHAR(128) NULL,
                resigned_at VARCHAR(64) NULL,
                UNIQUE KEY uq_officers_phone (phone),
                UNIQUE KEY uq_officers_device (device_id),
                KEY idx_officers_status (status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """
        )
        conn.cursor().execute(
            """
            CREATE TABLE IF NOT EXISTS auth_tokens (
                token VARCHAR(128) NOT NULL PRIMARY KEY,
                employee_id VARCHAR(32) NOT NULL,
                phone VARCHAR(16) NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                KEY idx_auth_tokens_employee (employee_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """
        )
    else:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS officers (
                employee_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                gender TEXT NOT NULL DEFAULT '未知',
                department TEXT NOT NULL,
                phone TEXT UNIQUE,
                device_id TEXT NOT NULL UNIQUE,
                face_vector TEXT,
                status INTEGER NOT NULL DEFAULT 2,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_officers_phone ON officers(phone);
            CREATE INDEX IF NOT EXISTS idx_officers_device ON officers(device_id);
            CREATE INDEX IF NOT EXISTS idx_officers_status ON officers(status);

            CREATE TABLE IF NOT EXISTS auth_tokens (
                token TEXT PRIMARY KEY,
                employee_id TEXT NOT NULL,
                phone TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            """
        )
    _migrate(conn)


def _migrate(conn: Any) -> None:
    if use_mysql():
        cur = conn.cursor()
        cur.execute(
            """
            SELECT COLUMN_NAME FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'officers'
            """
        )
        cols = {row["COLUMN_NAME"] for row in cur.fetchall()}
        if "last_device_id" not in cols:
            cur.execute("ALTER TABLE officers ADD COLUMN last_device_id VARCHAR(128) NULL")
        if "resigned_at" not in cols:
            cur.execute("ALTER TABLE officers ADD COLUMN resigned_at VARCHAR(64) NULL")
        for col, ddl in (
            ("id_card", "VARCHAR(32) NULL"),
            ("company", "VARCHAR(128) NULL"),
            ("position", "VARCHAR(64) NULL"),
        ):
            if col not in cols:
                cur.execute(f"ALTER TABLE officers ADD COLUMN {col} {ddl}")
                cols.add(col)
        _migrate_mysql_legacy_id_card(cur, cols)
        if "id_card" in cols:
            try:
                cur.execute(
                    "CREATE UNIQUE INDEX uq_officers_id_card ON officers (id_card)"
                )
            except Exception:
                pass
        if "gender" not in cols:
            cur.execute(
                "ALTER TABLE officers ADD COLUMN gender VARCHAR(8) NOT NULL DEFAULT '未知' AFTER name"
            )
        _migrate_officer_status_int(cur)
        return

    cols = {row[1] for row in conn.execute("PRAGMA table_info(officers)")}
    if "last_device_id" not in cols:
        conn.execute("ALTER TABLE officers ADD COLUMN last_device_id TEXT")
    if "resigned_at" not in cols:
        conn.execute("ALTER TABLE officers ADD COLUMN resigned_at TEXT")
    for col in ("id_card", "company", "position"):
        if col not in cols:
            conn.execute(f"ALTER TABLE officers ADD COLUMN {col} TEXT")
    if "gender" not in cols:
        conn.execute("ALTER TABLE officers ADD COLUMN gender TEXT NOT NULL DEFAULT '未知'")
    _migrate_officer_status_int_sqlite(conn)
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_officers_id_card ON officers(id_card)"
    )


def _migrate_officer_status_int(cur: Any) -> None:
    """MySQL：将 status 从 VARCHAR 迁移为 TINYINT 0/1/2。"""
    try:
        cur.execute("SHOW COLUMNS FROM officers LIKE 'status'")
        col = cur.fetchone()
        if not col:
            return
        col_type = str(col.get("Type") if isinstance(col, dict) else col[1]).lower()
        if "tinyint" in col_type or "int" in col_type:
            cur.execute(
                """
                UPDATE officers SET status = CASE
                    WHEN CAST(status AS CHAR) IN ('0', '1', '2') THEN CAST(status AS UNSIGNED)
                    WHEN status = 'active' THEN 1
                    WHEN status = 'resigned' THEN 0
                    ELSE 2 END
                """
            )
            return
        cur.execute("ALTER TABLE officers ADD COLUMN status_code TINYINT NOT NULL DEFAULT 2")
        cur.execute(
            """
            UPDATE officers SET status_code = CASE
                WHEN status = 'active' THEN 1
                WHEN status = 'resigned' THEN 0
                ELSE 2 END
            """
        )
        cur.execute("ALTER TABLE officers DROP COLUMN status")
        cur.execute("ALTER TABLE officers CHANGE status_code status TINYINT NOT NULL DEFAULT 2")
    except Exception:
        pass


def _migrate_officer_status_int_sqlite(conn: Any) -> None:
    try:
        row = conn.execute("SELECT typeof(status) FROM officers LIMIT 1").fetchone()
        if row and row[0] == "integer":
            return
        conn.execute(
            """
            UPDATE officers SET status = CASE
                WHEN status = 'active' THEN 1
                WHEN status = 'resigned' THEN 0
                WHEN status IN ('1', '0', '2') THEN CAST(status AS INTEGER)
                ELSE 2 END
            """
        )
    except Exception:
        pass


def _migrate_mysql_legacy_id_card(cur: Any, cols: set[str]) -> None:
    """修复 init_mysql.sql 误建的 `Identity card` 列与复合主键，统一到 id_card。"""
    if LEGACY_ID_CARD_COLUMN not in cols:
        return
    try:
        cur.execute(
            f"""
            UPDATE officers
            SET id_card = COALESCE(NULLIF(id_card, ''), `{LEGACY_ID_CARD_COLUMN}`)
            WHERE `{LEGACY_ID_CARD_COLUMN}` IS NOT NULL AND `{LEGACY_ID_CARD_COLUMN}` != ''
            """
        )
        cur.execute("ALTER TABLE officers DROP PRIMARY KEY")
        cur.execute(f"ALTER TABLE officers DROP COLUMN `{LEGACY_ID_CARD_COLUMN}`")
        cur.execute("ALTER TABLE officers ADD PRIMARY KEY (employee_id)")
    except Exception:
        pass
