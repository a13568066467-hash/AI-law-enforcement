"""Patrol auth DTOs."""
from __future__ import annotations

from pydantic import BaseModel, Field


class PatrolAuthReq(BaseModel):
    verify_token: str = Field(min_length=8)
    device_id: str = Field(min_length=4)
    face_image_base64: str = Field(min_length=64)


class FaceOnlyLoginReq(BaseModel):
    device_id: str = Field(min_length=4)
    face_image_base64: str = Field(min_length=64)


class ProfileStepReq(BaseModel):
    name: str = Field(min_length=2)
    gender: str = Field(default="未知", max_length=8)
    employee_id: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")
    department: str = ""
    device_id: str = Field(min_length=4)
    id_card: str = Field(min_length=18, max_length=18)
    company: str = ""
    position: str = ""


class ProfileOrgReq(BaseModel):
    session_id: str
    company: str = Field(min_length=1)
    department: str = Field(min_length=1)
    position: str = Field(min_length=1)


class SmsSendReq(BaseModel):
    session_id: str
    phone: str = Field(min_length=11, max_length=11)


class SmsVerifyReq(BaseModel):
    session_id: str
    phone: str = Field(min_length=11, max_length=11)
    code: str = Field(min_length=4, max_length=8)


class OffboardReq(BaseModel):
    device_id: str = Field(min_length=4)
