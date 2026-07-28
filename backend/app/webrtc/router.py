"""Legacy HTTP WebRTC signaling routes."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException

from app.webrtc import signaling as webrtc_signaling
from app.webrtc.schemas import (
    WebRtcCallStartReq,
    WebRtcFrameReq,
    WebRtcIceReq,
    WebRtcNalReq,
    WebRtcSdpReq,
)

router = APIRouter(tags=["webrtc"])


@router.post("/v1/webrtc/call/start")
def webrtc_call_start(req: WebRtcCallStartReq):
    try:
        session = webrtc_signaling.start_call(req.device_id.strip(), req.caller.strip())
        return webrtc_signaling.call_to_dict(session)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc


@router.get("/v1/webrtc/call/{call_id}")
def webrtc_call_status(call_id: str):
    try:
        return webrtc_signaling.call_detail(call_id)
    except KeyError as exc:
        raise HTTPException(404, "呼叫不存在") from exc


@router.post("/v1/webrtc/call/{call_id}/end")
def webrtc_call_end(call_id: str):
    webrtc_signaling.end_call(call_id)
    return {"ok": True}


@router.post("/v1/webrtc/call/{call_id}/offer")
def webrtc_post_offer(call_id: str, req: WebRtcSdpReq):
    try:
        webrtc_signaling.post_offer(call_id, req.sdp)
        return {"ok": True}
    except KeyError as exc:
        raise HTTPException(404, "呼叫不存在") from exc


@router.post("/v1/webrtc/call/{call_id}/answer")
def webrtc_post_answer(call_id: str, req: WebRtcSdpReq):
    try:
        webrtc_signaling.post_answer(call_id, req.sdp)
        return {"ok": True}
    except KeyError as exc:
        raise HTTPException(404, "呼叫不存在") from exc


@router.get("/v1/webrtc/call/{call_id}/answer")
def webrtc_poll_answer(call_id: str):
    sdp = webrtc_signaling.poll_answer(call_id)
    return {"sdp": sdp or ""}


@router.post("/v1/webrtc/call/{call_id}/ice")
def webrtc_post_ice(call_id: str, req: WebRtcIceReq):
    try:
        webrtc_signaling.add_ice(
            call_id, req.role, req.candidate, req.sdpMid, req.sdpMLineIndex,
        )
        return {"ok": True}
    except KeyError as exc:
        raise HTTPException(404, "呼叫不存在") from exc


@router.get("/v1/webrtc/call/{call_id}/ice")
def webrtc_list_ice(call_id: str, since: int = 0):
    return {"ice": webrtc_signaling.list_ice(call_id, since)}


@router.get("/v1/webrtc/device/{device_id}/poll")
def webrtc_device_poll(device_id: str):
    cmd = webrtc_signaling.poll_device(device_id.strip())
    return {"command": cmd}


@router.post("/v1/webrtc/call/{call_id}/frame")
def webrtc_post_frame(call_id: str, req: WebRtcFrameReq):
    try:
        webrtc_signaling.post_frame(call_id, req.frame_b64)
        return {"ok": True}
    except KeyError as exc:
        raise HTTPException(404, "呼叫不存在") from exc


@router.get("/v1/webrtc/call/{call_id}/frame")
def webrtc_get_frame(call_id: str):
    frame = webrtc_signaling.get_frame(call_id)
    return {"frame_b64": frame or "", "has_frame": bool(frame)}


@router.post("/v1/webrtc/call/{call_id}/nal")
def webrtc_post_nal(call_id: str, req: WebRtcNalReq):
    try:
        webrtc_signaling.post_nal(call_id, req.nal_b64)
        return {"ok": True}
    except KeyError as exc:
        raise HTTPException(404, "呼叫不存在") from exc
