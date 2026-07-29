"""扫码绑定业务：短期 token、占用会话、使用历史。"""
from __future__ import annotations

import os
import secrets
from datetime import datetime, timedelta, timezone
from typing import Any

from app.device_bind import repository as repo
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
    with repo.conn() as conn:
        repo.expire_pending_tokens_for_device(conn, device_id)
        repo.insert_pending_token(
            conn,
            device_id=device_id,
            token=token,
            expires_at=expires,
            created_at=now,
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

    with repo.conn() as conn:
        row = repo.get_token_row(conn, token)
        if row is None or repo.row_get(row, "device_id") != device_id:
            return {"ok": False, "status": "rejected", "message": "无效的绑定凭证"}

        status = repo.row_get(row, "status", repo.TOKEN_PENDING)
        expires_at = repo.row_get(row, "expires_at")

        if status == repo.TOKEN_PENDING and _is_expired(expires_at):
            repo.mark_token_expired(conn, token)
            status = repo.TOKEN_EXPIRED

        if status == repo.TOKEN_EXPIRED:
            return {"ok": True, "status": "expired", "message": "二维码已过期，请刷新"}

        reject = repo.row_get(row, "reject_reason")
        if status == repo.TOKEN_CONSUMED and reject:
            return {"ok": False, "status": "rejected", "message": reject}

        if status == repo.TOKEN_CONSUMED:
            eid = repo.row_get(row, "employee_id")
            session_token = repo.row_get(row, "session_token")
            occ = repo.get_occupancy_by_device(conn, device_id)
            if occ is None or not eid:
                return {"ok": False, "status": "rejected", "message": "占用会话已结束"}

    if status == repo.TOKEN_CONSUMED:
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

    with repo.conn() as conn:
        row = repo.get_token_row(conn, token)
        if row is None or repo.row_get(row, "device_id") != device_id:
            return {"ok": False, "message": "无效的绑定凭证"}

        status = repo.row_get(row, "status")
        expires_at = repo.row_get(row, "expires_at")
        if status != repo.TOKEN_PENDING:
            if status == repo.TOKEN_CONSUMED:
                eid = repo.row_get(row, "employee_id")
                if eid == employee_id:
                    session_token = repo.row_get(row, "session_token")
                    return {
                        "ok": True,
                        "message": "已绑定",
                        "session_token": session_token,
                        "officer": _officer_profile(officer),
                    }
            return {"ok": False, "message": "绑定凭证已失效"}
        if _is_expired(expires_at):
            repo.mark_token_expired(conn, token)
            return {"ok": False, "message": "二维码已过期，请让执法仪刷新后重扫"}

        device_occ = repo.get_occupancy_by_device(conn, device_id)
        if device_occ is not None:
            occ_eid = repo.row_get(device_occ, "employee_id")
            if occ_eid != employee_id:
                repo.reject_token(conn, token, "该执法仪已被其他人员占用")
                return {"ok": False, "message": "该执法仪已被其他人员占用，无法绑定"}
        else:
            blocked, legacy_msg = repo.legacy_recorder_blocks(conn, device_id, employee_id)
            if blocked:
                repo.reject_token(conn, token, legacy_msg)
                return {"ok": False, "message": legacy_msg}

        emp_occ = repo.get_occupancy_by_employee(conn, employee_id)
        if emp_occ is not None:
            other_device = repo.row_get(emp_occ, "device_id")
            if other_device != device_id:
                repo.reject_token(
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
        repo.consume_token(
            conn,
            token=token,
            employee_id=employee_id,
            session_token=session_token,
        )
        old_session = repo.upsert_occupancy(
            conn,
            device_id=device_id,
            employee_id=employee_id,
            session_token=session_token,
            started_at=now,
        )
        if old_session and old_session != session_token:
            repo.delete_auth_token(conn, old_session)
        repo.insert_auth_token(
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


def release_bind(device_id: str, *, end_reason: str = repo.END_UNBIND) -> dict[str, Any]:
    device_id = device_id.strip()
    if not device_id:
        return {"ok": False, "message": "设备编号无效"}
    legacy_cleared = False
    with repo.conn() as conn:
        ended = repo.end_occupancy(conn, device_id, end_reason)
        if not ended:
            legacy_cleared = repo.clear_legacy_recorder_if_stuck(conn, device_id)
    if ended:
        return {"ok": True, "message": "已解绑", "released": True, "device_id": device_id}
    if legacy_cleared:
        return {"ok": True, "message": "已解绑（旧台账已对齐）", "released": True, "device_id": device_id}
    recorder_db.touch_recorder(device_id)
    return {"ok": True, "message": "当前无占用会话", "released": False}


def shutdown_bind(device_id: str) -> dict[str, Any]:
    return release_bind(device_id, end_reason=repo.END_SHUTDOWN)


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
