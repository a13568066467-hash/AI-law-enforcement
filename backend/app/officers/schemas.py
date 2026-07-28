"""Officer / mobile auth DTOs."""
from __future__ import annotations

from pydantic import BaseModel, Field


class LoginReq(BaseModel):
    phone: str
    password: str


class MobileRegisterReq(BaseModel):
    phone: str = Field(min_length=11, max_length=11)
    name: str = Field(min_length=2)
    employee_id: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")
    department: str = Field(min_length=1)
    company: str = Field(min_length=1)
    position: str = Field(min_length=1)
    gender: str = Field(default="未知", max_length=8)
    id_card: str = Field(default="", max_length=18)
    face_image_base64: str = Field(min_length=64)


class MobileLoginReq(BaseModel):
    phone: str = Field(min_length=11, max_length=11)
    face_image_base64: str = Field(min_length=64)
