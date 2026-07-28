"""WebRTC signaling DTOs."""
from __future__ import annotations

from pydantic import BaseModel


class WebRtcCallStartReq(BaseModel):
    device_id: str
    caller: str = "指挥中心"


class WebRtcSdpReq(BaseModel):
    sdp: str


class WebRtcIceReq(BaseModel):
    role: str = "web"
    candidate: str
    sdpMid: str = ""
    sdpMLineIndex: int = 0


class WebRtcFrameReq(BaseModel):
    frame_b64: str


class WebRtcNalReq(BaseModel):
    nal_b64: str
