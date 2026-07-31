"""Task room request schemas."""
from __future__ import annotations

from pydantic import BaseModel, Field


class TaskRoomCreateReq(BaseModel):
    company: str = Field(min_length=1)
    title: str = ""
    created_by: str = ""


class TaskRoomAddDeviceReq(BaseModel):
    device_id: str = Field(min_length=4)
    push_video: bool = True


class TaskRoomJoinSeatReq(BaseModel):
    display_name: str = "指挥座席"
    employee_id: str = ""
    kind: str = "watch"  # watch | call


class TaskRoomLeaveSeatReq(BaseModel):
    seat_session_id: str = Field(min_length=4)


class TaskRoomHeartbeatReq(BaseModel):
    seat_session_id: str = Field(min_length=4)


class TaskRoomDeviceAckReq(BaseModel):
    device_id: str = Field(min_length=4)
    action: str = ""
