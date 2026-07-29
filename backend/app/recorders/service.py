"""Recorder online-status helpers."""
from __future__ import annotations

from datetime import datetime, timezone

from app.recorders.repository import PHASE_ACTIVE, PHASE_REGISTERING, RecorderRow


def is_recently_seen(last_seen_at: str, *, minutes: int = 30) -> bool:
    raw = (last_seen_at or "").strip()
    if not raw:
        return False
    try:
        dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return False
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    now = datetime.now(timezone.utc)
    return (now - dt).total_seconds() <= minutes * 60


def derive_status(rec: RecorderRow) -> str:
    """online | recording | offline — shared by dashboard and command-call online checks."""
    if rec.is_faulty:
        return "offline"
    if rec.binding_phase == PHASE_ACTIVE and is_recently_seen(rec.last_seen_at):
        return "online"
    if rec.binding_phase == PHASE_REGISTERING:
        return "online"
    if is_recently_seen(rec.last_seen_at, minutes=10):
        return "online"
    return "offline"
