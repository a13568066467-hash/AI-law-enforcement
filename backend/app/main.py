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
from .session_store import get_session, set_vision_result, trim_history

load_dotenv()

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


def _auth_token(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing token")
    token = authorization[7:].strip()
    if token not in _TOKENS.values():
        raise HTTPException(401, "invalid token")
    return token


@app.get("/health")
def health():
    return {
        "ok": True,
        "dashscope": bool(os.getenv("DASHSCOPE_API_KEY", "").strip()),
        "chat_model": os.getenv("CHAT_MODEL", "qwen-turbo"),
        "vision_model": os.getenv("VISION_MODEL", "qwen3-vl-8b-instruct"),
    }


@app.post("/auth/login")
def login(req: LoginReq):
    pwd = _USERS.get(req.phone)
    if pwd is None or pwd != req.password:
        raise HTTPException(401, "invalid credentials")
    token = secrets.token_urlsafe(24)
    _TOKENS[req.phone] = token
    return {"token": token, "phone": req.phone}


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
        result = route_chat(req.text, session, ble_state)
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
