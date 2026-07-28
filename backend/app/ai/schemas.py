"""AI chat / vision / demo / expert DTOs."""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class ChatReq(BaseModel):
    session_id: str
    device_id: str = ""
    text: str
    state: dict[str, Any] | None = None


class VisionReq(BaseModel):
    session_id: str
    image_base64: str = Field(min_length=64)
    question: str = ""


class VideoReq(BaseModel):
    session_id: str
    images_base64: list[str] = Field(min_length=1, max_length=24)
    frame_count: int = Field(default=0, ge=0)


class DemoScenarioReq(BaseModel):
    scenario_id: str = Field(min_length=1)
    device_id: str = ""


class ExpertSessionReq(BaseModel):
    session_id: str
    device_id: str = ""
    text: str = ""
    image_base64: str = ""
