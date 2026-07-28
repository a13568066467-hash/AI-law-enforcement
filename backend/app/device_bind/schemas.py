"""Device bind DTOs."""
from __future__ import annotations

from pydantic import BaseModel, Field


class DeviceBindTokenReq(BaseModel):
    device_id: str = Field(min_length=4)


class DeviceBindConfirmReq(BaseModel):
    device_id: str = Field(min_length=4)
    token: str = Field(min_length=8)


class DeviceBindReleaseReq(BaseModel):
    device_id: str = Field(min_length=4)


class RecorderCompanyReq(BaseModel):
    company: str = Field(min_length=1)
