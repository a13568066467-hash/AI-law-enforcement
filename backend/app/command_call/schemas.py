"""Command-call DTOs."""
from __future__ import annotations

from pydantic import BaseModel


class CommandCallStartReq(BaseModel):
    device_id: str
    caller: str = "指挥中心"


class OccupancyRoomEnsureReq(BaseModel):
    device_id: str
