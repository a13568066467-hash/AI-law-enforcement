"""Shared DB connection helpers (SQLite / MySQL)."""
from __future__ import annotations

import os
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

try:
    import pymysql
    from pymysql.cursors import DictCursor
    from pymysql.err import IntegrityError as MySQLIntegrityError
except ImportError:  # pragma: no cover
    pymysql = None  # type: ignore[assignment]
    DictCursor = None  # type: ignore[assignment,misc]
    MySQLIntegrityError = type("_MissingMySQL", (), {})  # type: ignore[assignment,misc]

_INTEGRITY_ERRORS: tuple[type[BaseException], ...] = (sqlite3.IntegrityError,)
if pymysql is not None:
    _INTEGRITY_ERRORS = (sqlite3.IntegrityError, MySQLIntegrityError)


def use_mysql() -> bool:
    driver = os.getenv("OFFICER_DB_DRIVER", "").strip().lower()
    if driver == "sqlite":
        return False
    if driver == "mysql":
        return True
    return bool(os.getenv("MYSQL_HOST", "").strip())


def db_backend_label() -> str:
    if use_mysql():
        host = os.getenv("MYSQL_HOST", "127.0.0.1")
        port = os.getenv("MYSQL_PORT", "3306")
        database = os.getenv("MYSQL_DATABASE", "aifieldcam")
        return f"mysql://{host}:{port}/{database}"
    return f"sqlite://{_db_path()}"


def _db_path() -> Path:
    raw = os.getenv("OFFICER_DB_PATH", "").strip()
    if raw:
        return Path(raw)
    return Path(__file__).resolve().parent.parent / "data" / "officers.db"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _adapt_sql(sql: str) -> str:
    return sql.replace("?", "%s") if use_mysql() else sql


def _row_get(row: Any, key: str, default: str = "") -> str:
    if row is None:
        return default
    try:
        val = row[key]
    except (KeyError, IndexError, TypeError):
        return default
    return default if val is None else str(val)


def _row_keys(row: Any) -> set[str]:
    if row is None:
        return set()
    if isinstance(row, dict):
        return set(row.keys())
    return set(row.keys())


def _mysql_connect():
    if pymysql is None:
        raise RuntimeError("未安装 pymysql，请执行: pip install pymysql")
    return pymysql.connect(
        host=os.getenv("MYSQL_HOST", "127.0.0.1"),
        port=int(os.getenv("MYSQL_PORT", "3306")),
        user=os.getenv("MYSQL_USER", "root"),
        password=os.getenv("MYSQL_PASSWORD", ""),
        database=os.getenv("MYSQL_DATABASE", "aifieldcam"),
        charset="utf8mb4",
        cursorclass=DictCursor,
        autocommit=False,
    )


@contextmanager
def _conn() -> Iterator[Any]:
    if use_mysql():
        conn = _mysql_connect()
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()
        return

    path = _db_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def _execute(conn: Any, sql: str, params: tuple[Any, ...] = ()) -> Any:
    cur = conn.cursor()
    cur.execute(_adapt_sql(sql), params)
    return cur


def _fetchone(conn: Any, sql: str, params: tuple[Any, ...] = ()) -> Any:
    cur = _execute(conn, sql, params)
    return cur.fetchone()


def ping() -> None:
    """验证数据库可连接。"""
    with _conn() as conn:
        if use_mysql():
            conn.cursor().execute("SELECT 1")
        else:
            conn.execute("SELECT 1")
