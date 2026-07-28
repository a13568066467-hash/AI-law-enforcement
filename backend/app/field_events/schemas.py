"""Field event ticket DTOs."""
from __future__ import annotations

from pydantic import BaseModel, Field


class FieldEventTicketCreateReq(BaseModel):
    """设备松手后提交；transcript 与 audio_pcm_base64 至少其一。"""

    transcript: str = ""
    audio_pcm_base64: str = ""


class FieldEventTicketStatusReq(BaseModel):
    company: str = Field(min_length=1)
    status: str = Field(min_length=1)
