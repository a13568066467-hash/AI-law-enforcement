"""现场事件工单：创建、按公司查阅。"""
from __future__ import annotations

import base64
import secrets
from typing import Any, Callable

from . import field_event_ticket_db, officer_db, recorder_db

BodyOrganizer = Callable[[str], str]
AudioTranscriber = Callable[[str], str]

_body_organizer: BodyOrganizer | None = None
_audio_transcriber: AudioTranscriber | None = None


def use_body_organizer(fn: BodyOrganizer | None) -> None:
    global _body_organizer
    _body_organizer = fn


def use_audio_transcriber(fn: AudioTranscriber | None) -> None:
    global _audio_transcriber
    _audio_transcriber = fn


def _organize_body(transcript: str) -> str:
    text = (transcript or "").strip()
    if not text:
        return ""
    fallback = _default_organize(text)
    if _body_organizer is None:
        return fallback
    try:
        organized = (_body_organizer(text) or "").strip()
    except Exception:
        return fallback
    return organized if organized else fallback


def _default_organize(transcript: str) -> str:
    """无 LLM 时原样通顺化为正文（去多余空白）。"""
    return " ".join(transcript.split())


def llm_organize_body(transcript: str) -> str:
    """轻量整理现场口述：通顺、去赘词、保留事实。无 Key 或失败时返回空以触发回退。"""
    text = (transcript or "").strip()
    if not text:
        return ""
    try:
        from .agents import CHAT_MODEL, _client
    except Exception:
        return ""
    client = _client()
    if client is None:
        return ""
    system = (
        "你是工地现场事件工单助手。将执勤员口述转写整理成简洁工单正文："
        "通顺、去掉口头禅与重复，保留地点、现象、诉求等事实，不要编造未提及的信息。"
        "只输出正文本身，不要标题或前缀。"
    )
    try:
        resp = client.chat.completions.create(
            model=CHAT_MODEL,
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": text},
            ],
            max_tokens=256,
            temperature=0.2,
        )
        return (resp.choices[0].message.content or "").strip()
    except Exception:
        return ""


def install_default_body_organizer() -> None:
    """进程启动时挂接 LLM 整理器（无 Key 时仍可用默认通顺化）。"""
    use_body_organizer(llm_organize_body)


def _transcribe_audio(audio_pcm_base64: str) -> str:
    raw = (audio_pcm_base64 or "").strip()
    if not raw:
        return ""
    if _audio_transcriber is not None:
        return (_audio_transcriber(raw) or "").strip()
    try:
        pcm = base64.b64decode(raw, validate=False)
    except Exception:
        return ""
    if len(pcm) < 1600:
        return ""
    # 未配置转写器时不臆造正文
    return ""


def _row_to_ticket(row: Any) -> dict[str, Any]:
    return {
        "id": officer_db._row_get(row, "id"),
        "company": officer_db._row_get(row, "company") or "",
        "device_id": officer_db._row_get(row, "device_id") or "",
        "employee_id": officer_db._row_get(row, "employee_id") or "",
        "officer_name": officer_db._row_get(row, "officer_name") or "",
        "body": officer_db._row_get(row, "body") or "",
        "status": officer_db._row_get(row, "status") or field_event_ticket_db.STATUS_PENDING,
        "created_at": officer_db._row_get(row, "created_at") or "",
        "updated_at": officer_db._row_get(row, "updated_at") or "",
    }


def create_from_session(
    *,
    session_token: str,
    transcript: str = "",
    audio_pcm_base64: str = "",
) -> dict[str, Any]:
    """占用会话凭证创建工单。空转写拒建。"""
    token = (session_token or "").strip()
    if not token:
        return {"ok": False, "status_code": 401, "message": "missing token"}

    text = (transcript or "").strip()
    if not text:
        text = _transcribe_audio(audio_pcm_base64)
    body = _organize_body(text)
    if not body:
        return {"ok": False, "status_code": 400, "message": "无有效语音内容"}

    with officer_db._conn() as conn:
        occ = officer_db._fetchone(
            conn,
            "SELECT * FROM device_occupancy WHERE session_token = ?",
            (token,),
        )
        if occ is None:
            return {"ok": False, "status_code": 401, "message": "未占用绑定"}

        device_id = officer_db._row_get(occ, "device_id") or ""
        employee_id = officer_db._row_get(occ, "employee_id") or ""
        officer = officer_db.get_by_employee_id(employee_id)
        if officer is None:
            return {"ok": False, "status_code": 400, "message": "执勤人员不存在"}

        company = (officer.company or "").strip()
        if not company:
            company = (recorder_db.get_recorder_company(device_id) or "").strip()
        if not company:
            return {"ok": False, "status_code": 400, "message": "缺少公司信息"}

        now = officer_db._utc_now()
        ticket_id = secrets.token_urlsafe(16)
        officer_db._execute(
            conn,
            """
            INSERT INTO field_event_tickets (
                id, company, device_id, employee_id, officer_name,
                body, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                ticket_id,
                company,
                device_id,
                employee_id,
                officer.name or "",
                body,
                field_event_ticket_db.STATUS_PENDING,
                now,
                now,
            ),
        )

    ticket = get_by_id(ticket_id)
    return {"ok": True, "status_code": 200, "ticket": ticket}


def get_by_id(ticket_id: str) -> dict[str, Any] | None:
    tid = (ticket_id or "").strip()
    if not tid:
        return None
    with officer_db._conn() as conn:
        row = officer_db._fetchone(
            conn,
            "SELECT * FROM field_event_tickets WHERE id = ?",
            (tid,),
        )
    return _row_to_ticket(row) if row else None


def list_by_company(company: str) -> list[dict[str, Any]]:
    company = (company or "").strip()
    if not company:
        return []
    with officer_db._conn() as conn:
        rows = officer_db._execute(
            conn,
            """
            SELECT * FROM field_event_tickets
            WHERE company = ?
            ORDER BY created_at DESC
            """,
            (company,),
        ).fetchall()
    return [_row_to_ticket(r) for r in rows]


def update_status(*, ticket_id: str, company: str, status: str) -> dict[str, Any]:
    tid = (ticket_id or "").strip()
    company_s = (company or "").strip()
    status_s = (status or "").strip()
    if not tid or not company_s:
        return {"ok": False, "status_code": 400, "message": "ticket_id and company required"}
    if status_s not in field_event_ticket_db.VALID_STATUSES:
        return {"ok": False, "status_code": 400, "message": "invalid status"}

    ticket = get_by_id(tid)
    if ticket is None or ticket.get("company") != company_s:
        return {"ok": False, "status_code": 404, "message": "ticket not found"}

    now = officer_db._utc_now()
    with officer_db._conn() as conn:
        officer_db._execute(
            conn,
            """
            UPDATE field_event_tickets
            SET status = ?, updated_at = ?
            WHERE id = ? AND company = ?
            """,
            (status_s, now, tid, company_s),
        )
    updated = get_by_id(tid)
    return {"ok": True, "status_code": 200, "ticket": updated}
