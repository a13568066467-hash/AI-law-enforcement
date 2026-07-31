"""
赢筑AI 云端后端 V1 — 组装入口

运行：cd backend && uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
"""
from __future__ import annotations

import os

from dotenv import load_dotenv

# 必须在读取 CHAT_MODEL / VISION_MODEL 等模块常量的 import 之前加载
load_dotenv(override=True)

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.command_call import service as command_call_session
from app.command_call import usersig
from app.field_events import service as field_event_ticket_store
from app.officers import repository as officer_db
from app.recorders import repository as recorder_db
from app.ai.router import router as ai_router
from app.command_call.router import router as command_call_router
from app.dashboard.router import router as dashboard_router
from app.db.migrations import run_all_migrations
from app.device_bind.router import router as device_bind_router
from app.field_events.router import router as field_events_router
from app.officers.router import router as officers_router
from app.patrol import face_engine
from app.patrol.router import router as patrol_router
from app.recorders.router import router as recorders_router
from app.webrtc.router import router as webrtc_router

run_all_migrations()
field_event_ticket_store.install_default_body_organizer()


def _command_call_device_online(device_id: str) -> bool:
    """与大屏一致：按 recorders.last_seen 推导是否在线。"""
    from app.recorders.service import derive_status

    rec = recorder_db.get_recorder(device_id)
    if rec is None:
        return False
    return derive_status(rec) != "offline"


from app.command_call.mqtt import create_mqtt_publisher_from_env
from app.task_room import service as task_room_session
from app.task_room.router import router as task_room_router

command_call_session.use_online_checker(_command_call_device_online)
_mqtt_pub = create_mqtt_publisher_from_env()
command_call_session.use_mqtt(_mqtt_pub)
task_room_session.use_online_checker(_command_call_device_online)
task_room_session.use_mqtt(_mqtt_pub)

app = FastAPI(title="赢筑AI API", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(officers_router)
app.include_router(patrol_router)
app.include_router(device_bind_router)
app.include_router(recorders_router)
app.include_router(dashboard_router)
app.include_router(webrtc_router)
app.include_router(command_call_router)
app.include_router(task_room_router)
app.include_router(ai_router)
app.include_router(field_events_router)


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
