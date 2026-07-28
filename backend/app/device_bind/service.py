"""扫码绑定业务：短期 token、占用会话、使用历史。"""
from __future__ import annotations

import os
import secrets
from datetime import datetime, timedelta, timezone
from typing import Any

from app.device_bind import repository as device_bind_db
from app.officers import repository as officer_db
from app.recorders import repository as recorder_db

TOKEN_TTL_SECONDS = int(os.getenv("DEVICE_BIND_TOKEN_TTL", "180"))


def _now() -> str:
    return officer_db._utc_now()


def _parse_iso(ts: str) -> datetime:
    return datetime.fromisoformat(ts.replace("Z", "+00:00"))


def _expires_at() -> str:
    return (datetime.now(timezone.utc) + timedelta(seconds=TOKEN_TTL_SECONDS)).isoformat()


def _is_expired(expires_at: str) -> bool:
    try:
        return _parse_iso(expires_at) <= datetime.now(timezone.utc)
    except ValueError:
        return True


def _officer_profile(row: officer_db.OfficerRow) -> dict[str, Any]:
    return {
        "employee_id": row.employee_id,
        "name": row.name,
        "gender": row.gender,
        "phone": row.phone,
        "department": row.department,
        "company": row.company,
        "position": row.position,
        "id_card": row.id_card,
        "status": row.status,
        "status_label": officer_db.status_label(row.status),
    }


def _get_token_row(conn: Any, token: str) -> Any | None:
    return officer_db._fetchone(
        conn,
        "SELECT * FROM device_bind_tokens WHERE token = ?",
        (token.strip(),),
    )


def _mark_token_expired(conn: Any, token: str) -> None:
    officer_db._execute(
        conn,
        "UPDATE device_bind_tokens SET status = ? WHERE token = ?",
        (device_bind_db.TOKEN_EXPIRED, token),
    )


def _get_occupancy_by_device(conn: Any, device_id: str) -> Any | None:
    return officer_db._fetchone(
        conn,
        "SELECT * FROM device_occupancy WHERE device_id = ?",
        (device_id.strip(),),
    )


def _get_occupancy_by_employee(conn: Any, employee_id: str) -> Any | None:
    eid = officer_db.normalize_employee_id(employee_id)
    return officer_db._fetchone(
        conn,
        "SELECT * FROM device_occupancy WHERE employee_id = ?",
        (eid,),
    )


def _write_history(
    conn: Any,
    *,
    device_id: str,
    employee_id: str,
    started_at: str,
    end_reason: str,
) -> None:
    officer_db._execute(
        conn,
        """
        INSERT INTO device_usage_history (
            device_id, employee_id, started_at, ended_at, end_reason
        ) VALUES (?, ?, ?, ?, ?)
        """,
        (device_id, employee_id, started_at, _now(), end_reason),
    )


def _insert_auth_token(
    conn: Any,
    *,
    token: str,
    employee_id: str,
    phone: str,
    created_at: str,
) -> None:
    params = (token, officer_db.normalize_employee_id(employee_id), phone.strip(), created_at)
    if officer_db.use_mysql():
        officer_db._execute(
            conn,
            """
            INSERT INTO auth_tokens (token, employee_id, phone, created_at)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                employee_id = VALUES(employee_id),
                phone = VALUES(phone),
                created_at = VALUES(created_at)
            """,
            params,
        )
        return
    officer_db._execute(
        conn,
        "INSERT OR REPLACE INTO auth_tokens (token, employee_id, phone, created_at) VALUES (?, ?, ?, ?)",
        params,
    )


def _recorder_use_row(conn: Any, device_id: str) -> tuple[bool, str]:
    row = officer_db._fetchone(
        conn,
        "SELECT in_use, employee_id FROM recorders WHERE device_id = ?",
        (device_id,),
    )
    if row is None:
        return False, ""
    in_use = bool(int(officer_db._row_get(row, "in_use", "0") or "0"))
    eid = officer_db._row_get(row, "employee_id")
    return in_use, eid


def _upsert_occupancy(
    conn: Any,
    *,
    device_id: str,
    employee_id: str,
    session_token: str,
    started_at: str,
) -> str:
    """写入或刷新占用行，返回被替换的旧 session_token（若有）。"""
    occ = _get_occupancy_by_device(conn, device_id)
    if occ is not None:
        old = officer_db._row_get(occ, "session_token")
        officer_db._execute(
            conn,
            """
            UPDATE device_occupancy
            SET employee_id = ?, session_token = ?, started_at = ?
            WHERE device_id = ?
            """,
            (employee_id, session_token, started_at, device_id),
        )
        return old

    emp_occ = _get_occupancy_by_employee(conn, employee_id)
    if emp_occ is not None:
        other_device = officer_db._row_get(emp_occ, "device_id")
        if other_device != device_id:
            return ""
        old = officer_db._row_get(emp_occ, "session_token")
        officer_db._execute(conn, "DELETE FROM device_occupancy WHERE employee_id = ?", (employee_id,))

    officer_db._execute(
        conn,
        """
        INSERT INTO device_occupancy (device_id, employee_id, started_at, session_token)
        VALUES (?, ?, ?, ?)
        """,
        (device_id, employee_id, started_at, session_token),
    )
    return ""


def _legacy_recorder_blocks(
    conn: Any,
    device_id: str,
    employee_id: str,
) -> tuple[bool, str]:
    """旧模型 recorders.in_use 与 occupancy 表不一致时的占用校验。"""
    occ = _get_occupancy_by_device(conn, device_id)
    if occ is not None:
        return False, ""
    in_use, rec_eid = _recorder_use_row(conn, device_id)
    if not in_use or not rec_eid:
        return False, ""
    if rec_eid == employee_id:
        return False, ""
    return True, f"本执法仪编号已被工号 {rec_eid} 使用中，无法绑定"


def _clear_legacy_recorder_if_stuck(conn: Any, device_id: str) -> bool:
    """occupancy 已空但 recorders 仍 in_use 时对齐为空闲。"""
    occ = _get_occupancy_by_device(conn, device_id)
    if occ is not None:
        return False
    in_use, _ = _recorder_use_row(conn, device_id)
    if not in_use:
        return False
    now = _now()
    officer_db._execute(
        conn,
        """
        UPDATE recorders
        SET in_use = 0, employee_id = NULL, binding_phase = ?,
            unbound_at = ?, updated_at = ?
        WHERE device_id = ?
        """,
        (recorder_db.PHASE_IDLE, now, now, device_id),
    )
    return True


def _end_occupancy(conn: Any, device_id: str, end_reason: str) -> bool:
    occ = _get_occupancy_by_device(conn, device_id)
    if occ is None:
        return False
    eid = officer_db._row_get(occ, "employee_id")
    started = officer_db._row_get(occ, "started_at")
    session_token = officer_db._row_get(occ, "session_token")
    _write_history(
        conn,
        device_id=device_id,
        employee_id=eid,
        started_at=started,
        end_reason=end_reason,
    )
    officer_db._execute(conn, "DELETE FROM device_occupancy WHERE device_id = ?", (device_id,))
    if session_token:
        officer_db._execute(conn, "DELETE FROM auth_tokens WHERE token = ?", (session_token,))
    now = _now()
    officer_db._execute(
        conn,
        """
        UPDATE recorders
        SET in_use = 0, employee_id = NULL, binding_phase = ?,
            unbound_at = ?, last_seen_at = ?, updated_at = ?
        WHERE device_id = ?
        """,
        (recorder_db.PHASE_IDLE, now, now, now, device_id),
    )
    return True


def create_bind_token(device_id: str) -> dict[str, Any]:
    device_id = device_id.strip()
    if not device_id:
        return {"ok": False, "message": "设备编号无效"}
    recorder_db.ensure_recorder(device_id)
    device_company = (recorder_db.get_recorder_company(device_id) or "").strip()
    if not device_company:
        return {"ok": False, "message": "设备尚未入库绑定公司，请联系管理员"}
    token = secrets.token_urlsafe(24)
    expires = _expires_at()
    now = _now()
    with officer_db._conn() as conn:
        officer_db._execute(
            conn,
            """
            UPDATE device_bind_tokens
            SET status = ?
            WHERE device_id = ? AND status = ?
            """,
            (device_bind_db.TOKEN_EXPIRED, device_id, device_bind_db.TOKEN_PENDING),
        )
        officer_db._execute(
            conn,
            """
            INSERT INTO device_bind_tokens (
                device_id, token, status, expires_at, created_at
            ) VALUES (?, ?, ?, ?, ?)
            """,
            (device_id, token, device_bind_db.TOKEN_PENDING, expires, now),
        )
    base = os.getenv("PUBLIC_API_BASE", "").strip().rstrip("/")
    qr_path = f"/bind?device={device_id}&token={token}"
    qr_url = f"{base}{qr_path}" if base else qr_path
    return {
        "ok": True,
        "device_id": device_id,
        "token": token,
        "expires_at": expires,
        "qr_url": qr_url,
        "ttl_seconds": TOKEN_TTL_SECONDS,
    }


def get_bind_status(device_id: str, token: str) -> dict[str, Any]:
    device_id = device_id.strip()
    token = token.strip()
    if not device_id or not token:
        return {"ok": False, "status": "rejected", "message": "参数无效"}

    with officer_db._conn() as conn:
        row = _get_token_row(conn, token)
        if row is None or officer_db._row_get(row, "device_id") != device_id:
            return {"ok": False, "status": "rejected", "message": "无效的绑定凭证"}

        status = officer_db._row_get(row, "status", device_bind_db.TOKEN_PENDING)
        expires_at = officer_db._row_get(row, "expires_at")

        if status == device_bind_db.TOKEN_PENDING and _is_expired(expires_at):
            _mark_token_expired(conn, token)
            status = device_bind_db.TOKEN_EXPIRED

        if status == device_bind_db.TOKEN_EXPIRED:
            return {"ok": True, "status": "expired", "message": "二维码已过期，请刷新"}

        reject = officer_db._row_get(row, "reject_reason")
        if status == device_bind_db.TOKEN_CONSUMED and reject:
            return {"ok": False, "status": "rejected", "message": reject}

        if status == device_bind_db.TOKEN_CONSUMED:
            eid = officer_db._row_get(row, "employee_id")
            session_token = officer_db._row_get(row, "session_token")
            occ = _get_occupancy_by_device(conn, device_id)
            if occ is None or not eid:
                return {"ok": False, "status": "rejected", "message": "占用会话已结束"}

    if status == device_bind_db.TOKEN_CONSUMED:
        officer = officer_db.get_by_employee_id(eid)
        if officer is None:
            return {"ok": False, "status": "rejected", "message": "占用会话已结束"}
        return {
            "ok": True,
            "status": "bound",
            "device_id": device_id,
            "token": token,
            "session_token": session_token,
            "officer": _officer_profile(officer),
        }

    return {
        "ok": True,
        "status": "pending",
        "device_id": device_id,
        "token": token,
        "expires_at": expires_at,
    }


def confirm_bind(device_id: str, token: str, mobile_token: str) -> dict[str, Any]:
    device_id = device_id.strip()
    token = token.strip()
    if not mobile_token:
        return {"ok": False, "message": "请先登录手机 App"}

    employee_id = officer_db.get_employee_id_by_token(mobile_token)
    if not employee_id:
        return {"ok": False, "message": "手机登录已失效，请重新登录"}

    officer = officer_db.get_by_employee_id(employee_id)
    if officer is None:
        return {"ok": False, "message": "人员档案不存在"}
    if not officer_db.is_active_status(officer.status):
        return {"ok": False, "message": "仅在岗人员可绑定执法仪"}

    recorder = recorder_db.ensure_recorder(device_id)
    if recorder.is_faulty:
        return {"ok": False, "message": "本执法仪已标记故障，暂不可绑定"}

    device_company = (recorder_db.get_recorder_company(device_id) or "").strip()
    officer_company = (officer.company or "").strip()
    if not device_company:
        return {"ok": False, "message": "设备尚未入库绑定公司，请联系管理员"}
    if not officer_company:
        return {"ok": False, "message": "人员档案缺少公司信息，请联系管理员"}
    if device_company != officer_company:
        return {"ok": False, "message": "非本公司设备，无法绑定"}

    with officer_db._conn() as conn:
        row = _get_token_row(conn, token)
        if row is None or officer_db._row_get(row, "device_id") != device_id:
            return {"ok": False, "message": "无效的绑定凭证"}

        status = officer_db._row_get(row, "status")
        expires_at = officer_db._row_get(row, "expires_at")
        if status != device_bind_db.TOKEN_PENDING:
            if status == device_bind_db.TOKEN_CONSUMED:
                eid = officer_db._row_get(row, "employee_id")
                if eid == employee_id:
                    session_token = officer_db._row_get(row, "session_token")
                    return {
                        "ok": True,
                        "message": "已绑定",
                        "session_token": session_token,
                        "officer": _officer_profile(officer),
                    }
            return {"ok": False, "message": "绑定凭证已失效"}
        if _is_expired(expires_at):
            _mark_token_expired(conn, token)
            return {"ok": False, "message": "二维码已过期，请让执法仪刷新后重扫"}

        device_occ = _get_occupancy_by_device(conn, device_id)
        if device_occ is not None:
            occ_eid = officer_db._row_get(device_occ, "employee_id")
            if occ_eid != employee_id:
                _reject_token(conn, token, "该执法仪已被其他人员占用")
                return {"ok": False, "message": "该执法仪已被其他人员占用，无法绑定"}
        else:
            blocked, legacy_msg = _legacy_recorder_blocks(conn, device_id, employee_id)
            if blocked:
                _reject_token(conn, token, legacy_msg)
                return {"ok": False, "message": legacy_msg}

        emp_occ = _get_occupancy_by_employee(conn, employee_id)
        if emp_occ is not None:
            other_device = officer_db._row_get(emp_occ, "device_id")
            if other_device != device_id:
                _reject_token(
                    conn,
                    token,
                    f"您已在设备 {other_device} 登录，请先解绑或关机后再绑定本机",
                )
                return {
                    "ok": False,
                    "message": f"您已在设备 {other_device} 占用中，请先解绑后再绑定本机",
                }

        session_token = secrets.token_urlsafe(24)
        now = _now()
        officer_db._execute(
            conn,
            """
            UPDATE device_bind_tokens
            SET status = ?, employee_id = ?, session_token = ?, reject_reason = NULL
            WHERE token = ?
            """,
            (device_bind_db.TOKEN_CONSUMED, employee_id, session_token, token),
        )
        old_session = _upsert_occupancy(
            conn,
            device_id=device_id,
            employee_id=employee_id,
            session_token=session_token,
            started_at=now,
        )
        if old_session and old_session != session_token:
            officer_db._execute(
                conn,
                "DELETE FROM auth_tokens WHERE token = ?",
                (old_session,),
            )
        _insert_auth_token(
            conn,
            token=session_token,
            employee_id=employee_id,
            phone=officer.phone or "",
            created_at=now,
        )

    recorder_db.mark_active(device_id, employee_id)
    return {
        "ok": True,
        "message": "绑定成功",
        "device_id": device_id,
        "session_token": session_token,
        "officer": _officer_profile(officer),
    }


def _reject_token(conn: Any, token: str, reason: str) -> None:
    officer_db._execute(
        conn,
        """
        UPDATE device_bind_tokens
        SET status = ?, reject_reason = ?
        WHERE token = ? AND status = ?
        """,
        (device_bind_db.TOKEN_CONSUMED, reason, token, device_bind_db.TOKEN_PENDING),
    )


def release_bind(device_id: str, *, end_reason: str = device_bind_db.END_UNBIND) -> dict[str, Any]:
    device_id = device_id.strip()
    if not device_id:
        return {"ok": False, "message": "设备编号无效"}
    legacy_cleared = False
    with officer_db._conn() as conn:
        ended = _end_occupancy(conn, device_id, end_reason)
        if not ended:
            legacy_cleared = _clear_legacy_recorder_if_stuck(conn, device_id)
    if ended:
        return {"ok": True, "message": "已解绑", "released": True, "device_id": device_id}
    if legacy_cleared:
        return {"ok": True, "message": "已解绑（旧台账已对齐）", "released": True, "device_id": device_id}
    recorder_db.touch_recorder(device_id)
    return {"ok": True, "message": "当前无占用会话", "released": False}


def shutdown_bind(device_id: str) -> dict[str, Any]:
    return release_bind(device_id, end_reason=device_bind_db.END_SHUTDOWN)


def set_recorder_company(device_id: str, company: str) -> dict[str, Any]:
    device_id = device_id.strip()
    company_s = company.strip()
    if not device_id or not company_s:
        return {"ok": False, "message": "设备编号与公司名称均必填"}
    recorder_db.ensure_recorder(device_id)
    recorder_db.set_company(device_id, company_s)
    row = recorder_db.get_recorder(device_id)
    return {
        "ok": True,
        "device_id": device_id,
        "company": company_s,
        "recorder": recorder_db.recorder_to_dict(row),
    }
