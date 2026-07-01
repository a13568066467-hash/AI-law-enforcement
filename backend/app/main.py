"""
AI Field Cam 云端后端 V1
- POST /auth/login
- POST /v1/chat   Agent A/B + ble_cmds
- POST /v1/vision qwen3-vl-8b-instruct（无 Key 时 mock）

运行：cd backend && uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
"""
from __future__ import annotations

import os
import secrets
from typing import Any

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from .agents import route_chat, vision_explain
from .demo_scenarios import list_scenarios, run_scenario
from .expert import ExpertServiceError, consult_expert
from .patrol_store import (
    find_phone_by_employee_id,
    get_officer,
    is_token_valid,
    login_officer,
    login_officer_by_device,
    offboard_officer,
    officer_exists,
    register_officer,
)
from . import officer_db
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

load_dotenv()

officer_db.init_db()

app = FastAPI(title="AI Field Cam API", version="1.0.0")
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


class PatrolAuthReq(BaseModel):
    verify_token: str = Field(min_length=8)
    device_id: str = Field(min_length=4)
    face_image_base64: str = Field(min_length=64)


class FaceOnlyLoginReq(BaseModel):
    device_id: str = Field(min_length=4)
    face_image_base64: str = Field(min_length=64)


class ProfileStepReq(BaseModel):
    name: str = Field(min_length=2)
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
        "chat_model": os.getenv("CHAT_MODEL", "qwen-turbo"),
        "vision_model": os.getenv("VISION_MODEL", "qwen3-vl-8b-instruct"),
        "officer_db": officer_db.db_backend_label(),
        "officer_db_ok": db_ok,
        "officer_db_error": db_error,
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
    row_device = officer_db.get_by_device(device_id)
    active_on_device = row_device is not None and row_device.status == officer_db.STATUS_ACTIVE
    by_phone = get_officer(phone) if phone else None
    return {
        "registered": officer_exists(phone) if phone else False,
        "device_id": device_id,
        "device_bound": active_on_device,
        "bound_officer": (
            {
                "name": row_device.name,
                "employee_id": row_device.employee_id,
                "phone_tail": row_device.phone[-4:] if len(row_device.phone) >= 4 else "",
            }
            if active_on_device and row_device
            else None
        ),
        "profile_in_db": row_device is not None,
        "phone_matches_device": (
            by_phone is not None
            and by_phone.device_id == device_id
            and officer_exists(phone)
        ),
    }


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
        "status": officer_db.STATUS_RESIGNED,
        "employee_id": record.employee_id,
        "name": record.name,
        "message": msg,
    }


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
        "model": os.getenv("VISION_MODEL", "qwen3-vl-8b-instruct"),
    }
