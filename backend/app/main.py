"""
赢筑AI 云端后端 V1
- POST /auth/login
- POST /v1/chat   Agent A/B + ble_cmds
- POST /v1/vision 多模态识图（无 Key 时 mock）
- POST /v1/video 视频抽帧分析（无 Key 时 mock）

运行：cd backend && uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
"""
from __future__ import annotations

import os
import secrets
from typing import Any

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from .agents import route_chat, vision_explain, video_explain
from .demo_scenarios import list_scenarios, run_scenario
from .expert import ExpertServiceError, consult_expert
from . import face_engine
from . import usersig
from . import command_call_session
from .patrol_store import (
    find_phone_by_employee_id,
    get_officer,
    is_token_valid,
    login_officer,
    login_officer_by_device,
    login_officer_mobile,
    offboard_officer,
    officer_exists,
    register_officer,
    register_officer_mobile,
)
from . import officer_db
from . import recorder_db
from . import dashboard_api
from . import webrtc_signaling
from . import device_bind_store
from .patrol_verify import (
    complete_profile_org,
    consume_verify_token,
    get_session as get_verify_session,
    peek_verify_token,
    send_sms_code,
    verify_profile,
    verify_sms_code,
)
from .session_store import get_session, set_vision_result, trim_history
from .realtime_voice import RealtimeSettings, bridge_realtime_websocket

load_dotenv()

officer_db.init_db()
try:
    recorder_db.backfill_from_officers()
except Exception:
    pass


def _command_call_device_online(device_id: str) -> bool:
    """与大屏一致：按 recorders.last_seen 推导是否在线。"""
    rec = recorder_db.get_recorder(device_id)
    if rec is None:
        return False
    return dashboard_api._derive_status(rec) != "offline"


command_call_session.use_online_checker(_command_call_device_online)

app = FastAPI(title="赢筑AI API", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 演示账号（V1）
_USERS = {"13800000000": "demo"}
_TOKENS: dict[str, str] = {}


class LoginReq(BaseModel):
    phone: str
    password: str


class ChatReq(BaseModel):
    session_id: str
    device_id: str = ""
    text: str
    state: dict[str, Any] | None = None


class VisionReq(BaseModel):
    session_id: str
    image_base64: str = Field(min_length=64)


class VideoReq(BaseModel):
    session_id: str
    images_base64: list[str] = Field(min_length=1, max_length=24)
    frame_count: int = Field(default=0, ge=0)


class PatrolAuthReq(BaseModel):
    verify_token: str = Field(min_length=8)
    device_id: str = Field(min_length=4)
    face_image_base64: str = Field(min_length=64)


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


class DeviceBindTokenReq(BaseModel):
    device_id: str = Field(min_length=4)


class DeviceBindConfirmReq(BaseModel):
    device_id: str = Field(min_length=4)
    token: str = Field(min_length=8)


class DeviceBindReleaseReq(BaseModel):
    device_id: str = Field(min_length=4)


class RecorderCompanyReq(BaseModel):
    company: str = Field(min_length=1)


class DemoScenarioReq(BaseModel):
    scenario_id: str = Field(min_length=1)
    device_id: str = ""


class ExpertSessionReq(BaseModel):
    session_id: str
    device_id: str = ""
    text: str = ""
    image_base64: str = ""


def _optional_bearer_token(authorization: str | None) -> str:
    """解析 Authorization 头；注销接口允许无 token，仅凭 device_id 操作。"""
    if not authorization or not authorization.startswith("Bearer "):
        return ""
    return authorization[7:].strip()


def _auth_token(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing token")
    token = authorization[7:].strip()
    if token not in _TOKENS.values() and not is_token_valid(token):
        raise HTTPException(401, "invalid token")
    return token


@app.get("/health")
def health():
    db_ok = True
    db_error = ""
    try:
        officer_db.ping()
    except Exception as exc:  # pragma: no cover
        db_ok = False
        db_error = str(exc)
    return {
        "ok": True,
        "dashscope": bool(os.getenv("DASHSCOPE_API_KEY", "").strip()),
        "realtime_voice": bool(
            os.getenv("DASHSCOPE_API_KEY", "").strip()
            and os.getenv("DASHSCOPE_WORKSPACE_ID", "").strip()
        ),
        "realtime_model": os.getenv(
            "REALTIME_MODEL", "qwen3.5-omni-flash-realtime"
        ),
        "chat_model": os.getenv("CHAT_MODEL", "qwen-turbo"),
        "vision_model": os.getenv("VISION_MODEL", "agnes-2.0-flash"),
        "trtc": usersig.trtc_configured(),
        "officer_db": officer_db.db_backend_label(),
        "officer_db_ok": db_ok,
        "officer_db_error": db_error,
        "face_engine": face_engine.face_engine_name(),
        "face_match_threshold": face_engine.match_threshold(),
        "face_pipeline": face_engine.pipeline_label(),
    }


@app.post("/auth/login")
def login(req: LoginReq):
    pwd = _USERS.get(req.phone)
    if pwd is None or pwd != req.password:
        raise HTTPException(401, "invalid credentials")
    token = secrets.token_urlsafe(24)
    _TOKENS[req.phone] = token
    return {"token": token, "phone": req.phone}


@app.get("/auth/patrol/status")
def patrol_status(phone: str, device_id: str):
    """查询手机号/执法仪绑定状态（读云端 SQLite 库）。"""
    recorder_db.ensure_recorder(device_id.strip())
    row_device = officer_db.get_by_device(device_id)
    rec = recorder_db.get_recorder(device_id.strip())
    active_on_device = row_device is not None and officer_db.is_active_status(row_device.status)
    pending_on_device = (
        row_device is not None
        and officer_db.is_registering_status(row_device.status)
    )
    by_phone = get_officer(phone) if phone else None
    return {
        "registered": officer_exists(phone) if phone else False,
        "device_id": device_id,
        "device_bound": active_on_device,
        "device_pending": pending_on_device,
        "device_available": row_device is None,
        "bound_officer": (
            {
                "name": row_device.name,
                "employee_id": row_device.employee_id,
                "phone_tail": row_device.phone[-4:] if len(row_device.phone) >= 4 else "",
                "status": row_device.status,
                "status_label": officer_db.status_label(row_device.status),
            }
            if row_device
            else None
        ),
        "profile_in_db": row_device is not None,
        "phone_matches_device": (
            by_phone is not None
            and by_phone.device_id == device_id
            and officer_exists(phone)
        ),
        "binding_rule": (
            "一台执法仪同时仅绑定一名人员；换人须前任在岗人员先注销/离职。"
        ),
        "recorder": recorder_db.recorder_to_dict(rec),
    }


@app.get("/v1/recorders/{device_id}")
def get_recorder_device(device_id: str):
    """查询执法仪台账。"""
    rec = recorder_db.ensure_recorder(device_id.strip())
    return recorder_db.recorder_to_dict(rec)


class RecorderFaultReq(BaseModel):
    is_faulty: bool = True
    fault_note: str = ""


@app.patch("/v1/recorders/{device_id}/fault")
def patch_recorder_fault(device_id: str, req: RecorderFaultReq):
    """标记/解除执法仪故障（运维/管控台）。"""
    recorder_db.ensure_recorder(device_id.strip())
    row = recorder_db.set_faulty(
        device_id.strip(),
        is_faulty=req.is_faulty,
        fault_note=req.fault_note,
    )
    if row is None:
        raise HTTPException(404, "执法仪不存在")
    return recorder_db.recorder_to_dict(row)


@app.get("/v1/dashboard/overview")
def dashboard_overview():
    """智慧控制平台大屏：汇聚 aifieldcam 执法仪 + 巡查员数据。"""
    try:
        return dashboard_api.get_dashboard_overview()
    except Exception as exc:
        raise HTTPException(500, f"dashboard overview error: {exc}") from exc


@app.get("/v1/dashboard/devices")
def dashboard_devices():
    """执法仪列表（含绑定人员）。"""
    try:
        return {"devices": dashboard_api.list_recorders_with_officers()}
    except Exception as exc:
        raise HTTPException(500, f"dashboard devices error: {exc}") from exc


# ── V2 视频连线（HTTP 信令中继，供 AI-screen Web 与设备使用） ──


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


@app.post("/v1/webrtc/call/start")
def webrtc_call_start(req: WebRtcCallStartReq):
    try:
        session = webrtc_signaling.start_call(req.device_id.strip(), req.caller.strip())
        return webrtc_signaling.call_to_dict(session)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc


@app.get("/v1/webrtc/call/{call_id}")
def webrtc_call_status(call_id: str):
    try:
        return webrtc_signaling.call_detail(call_id)
    except KeyError as exc:
        raise HTTPException(404, "呼叫不存在") from exc


@app.post("/v1/webrtc/call/{call_id}/end")
def webrtc_call_end(call_id: str):
    webrtc_signaling.end_call(call_id)
    return {"ok": True}


@app.post("/v1/webrtc/call/{call_id}/offer")
def webrtc_post_offer(call_id: str, req: WebRtcSdpReq):
    try:
        webrtc_signaling.post_offer(call_id, req.sdp)
        return {"ok": True}
    except KeyError as exc:
        raise HTTPException(404, "呼叫不存在") from exc


@app.post("/v1/webrtc/call/{call_id}/answer")
def webrtc_post_answer(call_id: str, req: WebRtcSdpReq):
    try:
        webrtc_signaling.post_answer(call_id, req.sdp)
        return {"ok": True}
    except KeyError as exc:
        raise HTTPException(404, "呼叫不存在") from exc


@app.get("/v1/webrtc/call/{call_id}/answer")
def webrtc_poll_answer(call_id: str):
    sdp = webrtc_signaling.poll_answer(call_id)
    return {"sdp": sdp or ""}


@app.post("/v1/webrtc/call/{call_id}/ice")
def webrtc_post_ice(call_id: str, req: WebRtcIceReq):
    try:
        webrtc_signaling.add_ice(
            call_id, req.role, req.candidate, req.sdpMid, req.sdpMLineIndex,
        )
        return {"ok": True}
    except KeyError as exc:
        raise HTTPException(404, "呼叫不存在") from exc


@app.get("/v1/webrtc/call/{call_id}/ice")
def webrtc_list_ice(call_id: str, since: int = 0):
    return {"ice": webrtc_signaling.list_ice(call_id, since)}


@app.get("/v1/webrtc/device/{device_id}/poll")
def webrtc_device_poll(device_id: str):
    cmd = webrtc_signaling.poll_device(device_id.strip())
    return {"command": cmd}


@app.post("/v1/webrtc/call/{call_id}/frame")
def webrtc_post_frame(call_id: str, req: WebRtcFrameReq):
    try:
        webrtc_signaling.post_frame(call_id, req.frame_b64)
        return {"ok": True}
    except KeyError as exc:
        raise HTTPException(404, "呼叫不存在") from exc


@app.get("/v1/webrtc/call/{call_id}/frame")
def webrtc_get_frame(call_id: str):
    frame = webrtc_signaling.get_frame(call_id)
    return {"frame_b64": frame or "", "has_frame": bool(frame)}


@app.post("/v1/webrtc/call/{call_id}/nal")
def webrtc_post_nal(call_id: str, req: WebRtcNalReq):
    """V2 NAL 单元上报（可选，Web 端 H264 扩展）。"""
    try:
        webrtc_signaling.post_nal(call_id, req.nal_b64)
        return {"ok": True}
    except KeyError as exc:
        raise HTTPException(404, "呼叫不存在") from exc


# ── 指挥连线（TRTC + 自建连线信令；与冻结的 /v1/webrtc/* 并行） ──


class CommandCallStartReq(BaseModel):
    device_id: str
    caller: str = "指挥中心"


@app.post("/v1/command-call/watch/start")
def command_call_watch_start(req: CommandCallStartReq):
    try:
        return command_call_session.start_watch(
            req.device_id.strip(),
            req.caller.strip() or "指挥中心",
        )
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc


@app.post("/v1/command-call/start")
def command_call_start(req: CommandCallStartReq):
    try:
        return command_call_session.start_command_call(
            req.device_id.strip(),
            req.caller.strip() or "指挥中心",
        )
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc


@app.get("/v1/command-call/device/{device_id}/poll")
def command_call_device_poll(device_id: str):
    did = device_id.strip()
    try:
        recorder_db.touch_recorder(did)
    except Exception:  # noqa: BLE001 — 在线刷新失败不影响信令
        pass
    cmd = command_call_session.poll_device(did)
    return {"command": cmd}


@app.get("/v1/command-call/{call_id}")
def command_call_status(call_id: str):
    try:
        return command_call_session.get_call(call_id)
    except KeyError as exc:
        raise HTTPException(404, "连线不存在") from exc


@app.post("/v1/command-call/{call_id}/end")
def command_call_end(call_id: str):
    command_call_session.end_command_call(call_id)
    return {"ok": True}


@app.post("/v1/command-call/{call_id}/watch/end")
def command_call_watch_end(call_id: str):
    command_call_session.end_watch(call_id)
    return {"ok": True}


@app.post("/v1/command-call/{call_id}/watch/heartbeat")
def command_call_watch_heartbeat(call_id: str):
    try:
        return command_call_session.touch_watch_heartbeat(call_id)
    except KeyError as exc:
        raise HTTPException(404, "监看不存在") from exc
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc


@app.post("/v1/command-call/{call_id}/upgrade")
def command_call_upgrade(call_id: str):
    try:
        return command_call_session.upgrade_watch_to_call(call_id)
    except KeyError as exc:
        raise HTTPException(404, "监看不存在") from exc
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc


@app.get("/auth/patrol/employee-id/new")
def patrol_new_employee_id():
    """生成唯一工号（客户端注册向导使用）。"""
    try:
        eid = officer_db.generate_employee_id()
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc
    return {"employee_id": eid}


@app.post("/auth/patrol/step1/profile")
def patrol_step1_profile(req: ProfileStepReq):
    """步骤 1：人员信息验证。"""
    ok, msg, session_id = verify_profile(
        name=req.name,
        gender=req.gender,
        employee_id=req.employee_id,
        department=req.department,
        device_id=req.device_id,
        id_card=req.id_card,
        company=req.company,
        position=req.position,
    )
    if not ok or not session_id:
        raise HTTPException(400, msg)
    return {
        "step": 1,
        "passed": True,
        "session_id": session_id,
        "message": msg,
    }


@app.post("/auth/patrol/step1/org")
def patrol_step1_org(req: ProfileOrgReq):
    """注册向导：补充公司/部门/职位后允许人脸验证。"""
    ok, msg = complete_profile_org(
        req.session_id,
        company=req.company,
        department=req.department,
        position=req.position,
    )
    if not ok:
        raise HTTPException(400, msg)
    return {"step": "org", "passed": True, "message": msg}


@app.post("/auth/patrol/step2/sms/send")
def patrol_step2_sms_send(req: SmsSendReq):
    """步骤 2a：发送手机验证码。"""
    ok, msg, dev_code = send_sms_code(req.session_id, req.phone)
    if not ok:
        raise HTTPException(400, msg)
    return {
        "step": 2,
        "message": msg,
        "dev_code": dev_code,
    }


@app.post("/auth/patrol/step2/sms/verify")
def patrol_step2_sms_verify(req: SmsVerifyReq):
    """步骤 2b：校验手机验证码。"""
    ok, msg, verify_token = verify_sms_code(req.session_id, req.phone, req.code)
    if not ok or not verify_token:
        raise HTTPException(400, msg)
    session = get_verify_session(req.session_id)
    return {
        "step": 2,
        "passed": True,
        "verify_token": verify_token,
        "phone": session.phone if session else req.phone,
        "message": msg,
    }


def _patrol_face_auth(req: PatrolAuthReq, *, register: bool):
    session = peek_verify_token(req.verify_token)
    if session is None:
        raise HTTPException(403, "请先完成步骤1和步骤2验证")
    if session.device_id != req.device_id.strip():
        raise HTTPException(403, "设备与验证会话不一致")

    token = secrets.token_urlsafe(24)
    if register:
        ok, msg, record = register_officer(
            phone=session.phone,
            name=session.name,
            employee_id=session.employee_id,
            department=session.department,
            device_id=session.device_id,
            face_image_b64=req.face_image_base64,
            token=token,
            id_card=session.id_card,
            company=session.company,
            position=session.position,
            gender=session.gender,
        )
    else:
        ok, msg, record = login_officer(
            phone=session.phone,
            device_id=session.device_id,
            face_image_b64=req.face_image_base64,
            token=token,
        )
    if not ok or record is None:
        raise HTTPException(403, msg)
    consume_verify_token(req.verify_token)
    return {
        "step": 3,
        "passed": True,
        "token": token,
        "phone": record.phone,
        "name": record.name,
        "employee_id": record.employee_id,
        "department": record.department,
        "device_id": record.device_id,
        "message": msg,
    }


@app.post("/auth/patrol/register")
def patrol_register(req: PatrolAuthReq):
    return _patrol_face_auth(req, register=True)


@app.post("/auth/mobile/login")
def mobile_login(req: MobileLoginReq):
    """手机 App 登录：在岗人员人脸比对，签发 Bearer token 供扫码绑定使用。"""
    token = secrets.token_urlsafe(24)
    ok, msg, record = login_officer_mobile(
        phone=req.phone,
        face_image_b64=req.face_image_base64,
        token=token,
    )
    if not ok or record is None:
        raise HTTPException(403, msg)
    return {
        "ok": True,
        "token": token,
        "phone": record.phone,
        "name": record.name,
        "employee_id": record.employee_id,
        "department": record.department,
        "message": msg,
    }


@app.post("/auth/mobile/register")
def mobile_register(req: MobileRegisterReq):
    """手机 App 自助注册：人员进入在岗池，不绑定具体执法仪。"""
    token = secrets.token_urlsafe(24)
    ok, msg, record = register_officer_mobile(
        phone=req.phone,
        name=req.name,
        employee_id=req.employee_id,
        department=req.department,
        face_image_b64=req.face_image_base64,
        token=token,
        id_card=req.id_card,
        company=req.company,
        position=req.position,
        gender=req.gender,
    )
    if not ok or record is None:
        raise HTTPException(403, msg)
    return {
        "ok": True,
        "token": token,
        "phone": record.phone,
        "name": record.name,
        "employee_id": record.employee_id,
        "department": record.department,
        "company": req.company,
        "message": msg,
    }


@app.post("/auth/patrol/login")
def patrol_login(req: PatrolAuthReq):
    return _patrol_face_auth(req, register=False)


@app.post("/auth/patrol/face-only-login")
def patrol_face_only_login(req: FaceOnlyLoginReq):
    """已注册执法仪：后续登录仅扫脸，与云端库人脸模板比对。"""
    token = secrets.token_urlsafe(24)
    ok, msg, record = login_officer_by_device(
        device_id=req.device_id,
        face_image_b64=req.face_image_base64,
        token=token,
    )
    if not ok or record is None:
        raise HTTPException(403, msg)
    return {
        "step": "face_login",
        "passed": True,
        "token": token,
        "phone": record.phone,
        "name": record.name,
        "employee_id": record.employee_id,
        "department": record.department,
        "device_id": record.device_id,
        "message": msg,
    }


@app.post("/auth/patrol/offboard")
def patrol_offboard(req: OffboardReq, authorization: str | None = Header(default=None)):
    """执法仪端注销在岗巡查员：设备解绑，云端档案标记离职。

    可不携带 token，仅凭本机 device_id 注销（适用于已退出登录但仍需解绑的场景）。
    """
    token = _optional_bearer_token(authorization)
    employee_id = officer_db.get_employee_id_by_token(token) if token else ""
    ok, msg, record = offboard_officer(
        device_id=req.device_id,
        employee_id=employee_id or "",
        token=token,
    )
    if not ok or record is None:
        raise HTTPException(403, msg)
    return {
        "ok": True,
        "status": record.status,
        "status_label": officer_db.status_label(record.status),
        "gender": record.gender,
        "employee_id": record.employee_id,
        "name": record.name,
        "message": msg,
    }


@app.post("/auth/device/bind/token")
def device_bind_token(req: DeviceBindTokenReq):
    """执法仪申请短期扫码绑定 token。"""
    result = device_bind_store.create_bind_token(req.device_id)
    if not result.get("ok"):
        raise HTTPException(400, result.get("message", "申请失败"))
    return result


@app.get("/auth/device/bind/status")
def device_bind_status(device_id: str, token: str):
    """执法仪轮询扫码绑定状态。"""
    return device_bind_store.get_bind_status(device_id, token)


@app.post("/auth/device/bind/confirm")
def device_bind_confirm(
    req: DeviceBindConfirmReq,
    authorization: str | None = Header(default=None),
):
    """手机 App 扫码确认绑定（须携带手机端 Bearer token）。"""
    mobile = _auth_token(authorization)
    result = device_bind_store.confirm_bind(req.device_id, req.token, mobile)
    if not result.get("ok"):
        raise HTTPException(403, result.get("message", "绑定失败"))
    return result


@app.post("/auth/device/bind/release")
def device_bind_release(req: DeviceBindReleaseReq):
    """解绑：结束设备当前占用，保留使用历史。"""
    result = device_bind_store.release_bind(req.device_id)
    if not result.get("ok"):
        raise HTTPException(400, result.get("message", "解绑失败"))
    return result


@app.post("/auth/device/bind/shutdown")
def device_bind_shutdown(req: DeviceBindReleaseReq):
    """关机：结束设备当前占用（reason=shutdown）。"""
    result = device_bind_store.shutdown_bind(req.device_id)
    if not result.get("ok"):
        raise HTTPException(400, result.get("message", "关机解绑失败"))
    return result


@app.patch("/v1/recorders/{device_id}/company")
def patch_recorder_company(device_id: str, req: RecorderCompanyReq):
    """管理后台：设备入库绑定公司。"""
    result = device_bind_store.set_recorder_company(device_id, req.company)
    if not result.get("ok"):
        raise HTTPException(400, result.get("message", "设置失败"))
    return result


@app.get("/v1/demo/scenarios")
def demo_scenarios_list():
    """智慧工地九大核心业务场景。"""
    return {"scenarios": list_scenarios()}


@app.post("/v1/demo/scenario")
def demo_scenario_run(req: DemoScenarioReq, authorization: str | None = Header(default=None)):
    """运行单个演示场景，返回语音播报、文档与平台同步状态。"""
    _auth_token(authorization)
    result = run_scenario(req.scenario_id, device_id=req.device_id)
    if not result.get("scenario_id"):
        raise HTTPException(404, "未知演示场景")
    return result


@app.post("/v1/expert/session")
def expert_session(req: ExpertSessionReq, authorization: str | None = Header(default=None)):
    """AI 技术专家咨询：可选带图，支持同会话多轮追问。"""
    _auth_token(authorization)
    session = get_session(req.session_id)
    try:
        return consult_expert(
            text=req.text,
            session=session,
            image_base64=req.image_base64,
            device_id=req.device_id,
        )
    except ExpertServiceError as exc:
        raise HTTPException(503, str(exc)) from exc
    except Exception as exc:
        raise HTTPException(500, f"expert error: {exc}") from exc


@app.websocket("/v1/realtime/voice")
async def realtime_voice(websocket: WebSocket):
    """鉴权后代理设备与百炼 Qwen Realtime 的全双工音频。"""
    try:
        _auth_token(websocket.headers.get("authorization"))
    except HTTPException:
        await websocket.close(code=4401, reason="invalid token")
        return
    await websocket.accept()
    try:
        settings = RealtimeSettings.from_env()
        await bridge_realtime_websocket(websocket, settings)
    except WebSocketDisconnect:
        return
    except RuntimeError as exc:
        await websocket.send_json(
            {"type": "error", "code": "configuration_error", "message": str(exc)}
        )
        await websocket.close(code=1011)
    except Exception:
        try:
            await websocket.send_json(
                {
                    "type": "error",
                    "code": "realtime_unavailable",
                    "message": "实时语音服务暂不可用",
                }
            )
            await websocket.close(code=1011)
        except Exception:
            pass


@app.post("/v1/chat")
def chat(req: ChatReq, authorization: str | None = Header(default=None)):
    _auth_token(authorization)
    session = get_session(req.session_id)
    ble_state = 0
    if req.state and "ble_state" in req.state:
        try:
            ble_state = int(req.state["ble_state"])
        except (TypeError, ValueError):
            ble_state = 0
    try:
        result = route_chat(req.text, session, ble_state, req.device_id)
    except Exception as exc:
        raise HTTPException(500, f"chat error: {exc}") from exc
    session.history.append({"role": "user", "content": req.text})
    session.history.append({"role": "assistant", "content": result.get("reply", "")})
    trim_history(session)
    return result


@app.post("/v1/vision")
def vision(req: VisionReq, authorization: str | None = Header(default=None)):
    _auth_token(authorization)
    explanation = vision_explain(req.image_base64)
    session = set_vision_result(req.session_id, explanation)
    session.history.append({"role": "user", "content": "[用户上传了一张照片]"})
    session.history.append({"role": "assistant", "content": f"【识图结果】{explanation}"})
    trim_history(session)
    return {
        "explanation": explanation,
        "last_explanation": session.last_explanation,
        "model": os.getenv("VISION_MODEL", "agnes-2.0-flash"),
    }


@app.post("/v1/video")
def video_analyze(req: VideoReq, authorization: str | None = Header(default=None)):
    _auth_token(authorization)
    fc = req.frame_count if req.frame_count > 0 else len(req.images_base64)
    explanation = video_explain(req.images_base64, fc)
    session = set_vision_result(req.session_id, explanation)
    session.history.append({"role": "user", "content": f"[用户上传了一段视频（{fc}帧）]"})
    session.history.append({"role": "assistant", "content": f"【视频分析】{explanation}"})
    trim_history(session)
    return {
        "explanation": explanation,
        "last_explanation": session.last_explanation,
        "model": os.getenv("VISION_MODEL", "agnes-2.0-flash"),
        "frame_count": fc,
    }
