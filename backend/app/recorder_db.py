"""执法仪设备台账（与 officers 绑定关系联动）。"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from . import officer_db

PHASE_IDLE = 0
PHASE_REGISTERING = 1
PHASE_ACTIVE = 2
DEFAULT_MODEL = "DSJ-ZECN6A1"

def phase_label(phase: int) -> str:
    if phase == PHASE_ACTIVE:
        return "在岗"
    if phase == PHASE_REGISTERING:
        return "办理中"
    return "空闲"


def _normalize_phase(raw: Any) -> int:
    if isinstance(raw, int):
        return raw
    s = str(raw or "").strip().lower()
    if s.isdigit():
        return int(s)
    legacy = {"idle": PHASE_IDLE, "registering": PHASE_REGISTERING, "active": PHASE_ACTIVE}
    return legacy.get(s, PHASE_IDLE)


@dataclass
class RecorderRow:
    device_id: str
    device_name: str
    model: str
    company: str
    in_use: bool
    employee_id: str
    is_faulty: bool
    fault_note: str
    binding_phase: int
    bound_at: str
    unbound_at: str
    last_seen_at: str
    remark: str
    created_at: str
    updated_at: str


def _now() -> str:
    return officer_db._utc_now()


def _default_device_name(device_id: str) -> str:
    suffix = device_id[-8:] if len(device_id) > 8 else device_id
    return f"执法仪-{suffix}"


def init_recorders_table(conn: Any) -> None:
    if officer_db.use_mysql():
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
    if officer_db.use_mysql():
        _migrate_recorders_company_mysql(conn)
        return
    cols = {row[1] for row in conn.execute("PRAGMA table_info(recorders)")}
    if "company" not in cols:
        conn.execute("ALTER TABLE recorders ADD COLUMN company TEXT")


def _row_to_recorder(row: Any | None) -> RecorderRow | None:
    if row is None:
        return None
    return RecorderRow(
        device_id=officer_db._row_get(row, "device_id"),
        device_name=officer_db._row_get(row, "device_name"),
        model=officer_db._row_get(row, "model") or DEFAULT_MODEL,
        company=officer_db._row_get(row, "company") if "company" in officer_db._row_keys(row) else "",
        in_use=bool(int(officer_db._row_get(row, "in_use", "0") or "0")),
        employee_id=officer_db._row_get(row, "employee_id"),
        is_faulty=bool(int(officer_db._row_get(row, "is_faulty", "0") or "0")),
        fault_note=officer_db._row_get(row, "fault_note"),
        binding_phase=_normalize_phase(row["binding_phase"]),
        bound_at=officer_db._row_get(row, "bound_at"),
        unbound_at=officer_db._row_get(row, "unbound_at"),
        last_seen_at=officer_db._row_get(row, "last_seen_at"),
        remark=officer_db._row_get(row, "remark"),
        created_at=officer_db._row_get(row, "created_at"),
        updated_at=officer_db._row_get(row, "updated_at"),
    )


def get_recorder(device_id: str) -> RecorderRow | None:
    device_id = device_id.strip()
    if not device_id:
        return None
    with officer_db._conn() as conn:
        row = officer_db._fetchone(
            conn, "SELECT * FROM recorders WHERE device_id = ?", (device_id,)
        )
    return _row_to_recorder(row)


def ensure_recorder(
    device_id: str,
    *,
    device_name: str = "",
    model: str = DEFAULT_MODEL,
    touch_seen: bool = True,
) -> RecorderRow:
    """首次见到设备编号时登记台账；已存在则刷新 last_seen。"""
    device_id = device_id.strip()
    now = _now()
    name = device_name.strip() or _default_device_name(device_id)
    model_s = model.strip() or DEFAULT_MODEL
    existing = get_recorder(device_id)
    with officer_db._conn() as conn:
        if existing is None:
            officer_db._execute(
                conn,
                """
                INSERT INTO recorders (
                    device_id, device_name, model, in_use, employee_id,
                    is_faulty, fault_note, binding_phase,
                    bound_at, unbound_at, last_seen_at, remark,
                    created_at, updated_at
                ) VALUES (?, ?, ?, 0, NULL, 0, NULL, ?, NULL, NULL, ?, NULL, ?, ?)
                """,
                (device_id, name, model_s, PHASE_IDLE, now if touch_seen else None, now, now),
            )
        else:
            seen = now if touch_seen else existing.last_seen_at or None
            officer_db._execute(
                conn,
                """
                UPDATE recorders
                SET last_seen_at = COALESCE(?, last_seen_at),
                    device_name = CASE WHEN ? != '' THEN ? ELSE device_name END,
                    model = ?, updated_at = ?
                WHERE device_id = ?
                """,
                (seen, name, name, model_s, now, device_id),
            )
    row = get_recorder(device_id)
    assert row is not None
    return row


def touch_recorder(device_id: str) -> None:
    device_id = device_id.strip()
    if not device_id:
        return
    now = _now()
    with officer_db._conn() as conn:
        officer_db._execute(
            conn,
            "UPDATE recorders SET last_seen_at = ?, updated_at = ? WHERE device_id = ?",
            (now, now, device_id),
        )


def mark_registering(device_id: str, employee_id: str) -> None:
    """人员开始注册办理：占用设备编号。"""
    device_id = device_id.strip()
    eid = officer_db.normalize_employee_id(employee_id)
    ensure_recorder(device_id)
    now = _now()
    with officer_db._conn() as conn:
        officer_db._execute(
            conn,
            """
            UPDATE recorders
            SET in_use = 1, employee_id = ?, binding_phase = ?,
                bound_at = ?, unbound_at = NULL,
                last_seen_at = ?, updated_at = ?
            WHERE device_id = ?
            """,
            (eid, PHASE_REGISTERING, now, now, now, device_id),
        )


def mark_active(device_id: str, employee_id: str) -> None:
    """人脸激活成功：设备与人员在岗绑定。"""
    device_id = device_id.strip()
    eid = officer_db.normalize_employee_id(employee_id)
    ensure_recorder(device_id)
    now = _now()
    with officer_db._conn() as conn:
        officer_db._execute(
            conn,
            """
            UPDATE recorders
            SET in_use = 1, employee_id = ?, binding_phase = ?,
                bound_at = ?, last_seen_at = ?, updated_at = ?
            WHERE device_id = ?
            """,
            (eid, PHASE_ACTIVE, now, now, now, device_id),
        )


def release_recorder(device_id: str) -> None:
    """人员注销/离职：释放设备供下一人使用。"""
    device_id = device_id.strip()
    now = _now()
    with officer_db._conn() as conn:
        officer_db._execute(
            conn,
            """
            UPDATE recorders
            SET in_use = 0, employee_id = NULL, binding_phase = ?,
                unbound_at = ?, last_seen_at = ?, updated_at = ?
            WHERE device_id = ?
            """,
            (PHASE_IDLE, now, now, now, device_id),
        )


def set_faulty(device_id: str, *, is_faulty: bool, fault_note: str = "") -> RecorderRow | None:
    device_id = device_id.strip()
    now = _now()
    note = fault_note.strip() or None
    with officer_db._conn() as conn:
        officer_db._execute(
            conn,
            """
            UPDATE recorders
            SET is_faulty = ?, fault_note = ?, updated_at = ?
            WHERE device_id = ?
            """,
            (1 if is_faulty else 0, note, now, device_id),
        )
    return get_recorder(device_id)


def check_recorder_usable(device_id: str, employee_id: str = "") -> tuple[bool, str]:
    """设备是否可绑定：非故障，且未被其他工号占用。"""
    device_id = device_id.strip()
    eid = officer_db.normalize_employee_id(employee_id) if employee_id else ""
    rec = get_recorder(device_id)
    if rec is None:
        return True, ""
    if rec.is_faulty:
        detail = rec.fault_note.strip()
        msg = "本执法仪已标记为故障/损坏，暂不可绑定"
        if detail:
            msg += f"：{detail}"
        return False, msg
    if rec.in_use and rec.employee_id and rec.employee_id != eid:
        phase = "办理绑定" if rec.binding_phase == PHASE_REGISTERING else "使用中"
        return False, (
            f"本执法仪编号已被工号 {rec.employee_id} {phase}，"
            "需前任人员注销/离职后方可换人使用"
        )
    return True, ""


def get_recorder_company(device_id: str) -> str:
    rec = get_recorder(device_id.strip())
    return rec.company if rec else ""


def set_company(device_id: str, company: str) -> None:
    device_id = device_id.strip()
    now = _now()
    with officer_db._conn() as conn:
        officer_db._execute(
            conn,
            "UPDATE recorders SET company = ?, updated_at = ? WHERE device_id = ?",
            (company.strip(), now, device_id),
        )


def recorder_to_dict(row: RecorderRow | None) -> dict[str, Any]:
    if row is None:
        return {}
    return {
        "device_id": row.device_id,
        "device_name": row.device_name,
        "model": row.model,
        "company": row.company or None,
        "in_use": row.in_use,
        "employee_id": row.employee_id or None,
        "is_faulty": row.is_faulty,
        "fault_note": row.fault_note or None,
        "binding_phase": row.binding_phase,
        "binding_phase_label": phase_label(row.binding_phase),
        "bound_at": row.bound_at or None,
        "unbound_at": row.unbound_at or None,
        "last_seen_at": row.last_seen_at or None,
        "remark": row.remark or None,
    }


def backfill_from_officers() -> int:
    """从现有 officers 表同步执法仪占用状态（启动时一次）。"""
    count = 0
    with officer_db._conn() as conn:
        rows = officer_db._execute(
            conn,
            """
            SELECT device_id, employee_id, status FROM officers
            WHERE device_id NOT LIKE ?
            """,
            (f"{officer_db.RESIGNED_DEVICE_PREFIX}%",),
        ).fetchall()
    for row in rows:
        device_id = officer_db._row_get(row, "device_id")
        eid = officer_db._row_get(row, "employee_id")
        status = officer_db._row_get(row, "status")
        if not device_id:
            continue
        if device_id.startswith(officer_db.POOL_DEVICE_PREFIX):
            continue
        ensure_recorder(device_id, touch_seen=False)
        if status == officer_db.STATUS_ACTIVE:
            mark_active(device_id, eid)
            count += 1
        elif status == officer_db.STATUS_REGISTERING:
            mark_registering(device_id, eid)
            count += 1
    return count
