"""Recorder DTOs."""
from __future__ import annotations

from pydantic import BaseModel


class RecorderFaultReq(BaseModel):
    is_faulty: bool = True
    fault_note: str = ""
