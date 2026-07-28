"""Field event ticket HTTP routes."""
from __future__ import annotations

from fastapi import APIRouter, Header, HTTPException

from app.field_events import service as field_event_ticket_store
from app.core.auth import require_auth_token
from app.field_events.schemas import FieldEventTicketCreateReq, FieldEventTicketStatusReq

router = APIRouter(tags=["field-events"])


@router.post("/v1/field-event-tickets")
def create_field_event_ticket(
    req: FieldEventTicketCreateReq,
    authorization: str | None = Header(default=None),
):
    token = require_auth_token(authorization)
    result = field_event_ticket_store.create_from_session(
        session_token=token,
        transcript=req.transcript,
        audio_pcm_base64=req.audio_pcm_base64,
    )
    if not result.get("ok"):
        raise HTTPException(
            int(result.get("status_code") or 400),
            str(result.get("message") or "create failed"),
        )
    return {"ticket": result["ticket"]}


@router.get("/v1/field-event-tickets")
def list_field_event_tickets(company: str):
    company_s = (company or "").strip()
    if not company_s:
        raise HTTPException(400, "company required")
    return {"tickets": field_event_ticket_store.list_by_company(company_s)}


@router.get("/v1/field-event-tickets/{ticket_id}")
def get_field_event_ticket(ticket_id: str, company: str):
    company_s = (company or "").strip()
    if not company_s:
        raise HTTPException(400, "company required")
    ticket = field_event_ticket_store.get_by_id(ticket_id)
    if ticket is None or ticket.get("company") != company_s:
        raise HTTPException(404, "ticket not found")
    return {"ticket": ticket}


@router.patch("/v1/field-event-tickets/{ticket_id}")
def patch_field_event_ticket(ticket_id: str, req: FieldEventTicketStatusReq):
    result = field_event_ticket_store.update_status(
        ticket_id=ticket_id,
        company=req.company,
        status=req.status,
    )
    if not result.get("ok"):
        raise HTTPException(
            int(result.get("status_code") or 400),
            str(result.get("message") or "update failed"),
        )
    return {"ticket": result["ticket"]}
