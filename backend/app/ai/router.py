"""AI chat / vision / video / realtime / demo / expert routes."""
from __future__ import annotations

import os

from fastapi import APIRouter, Header, HTTPException, WebSocket, WebSocketDisconnect

from app.ai.agents import route_chat, video_explain, vision_explain
from app.ai.demo_scenarios import list_scenarios, run_scenario
from app.ai.expert import ExpertServiceError, consult_expert
from app.ai.realtime_voice import RealtimeSettings, bridge_realtime_websocket
from app.ai.schemas import ChatReq, DemoScenarioReq, ExpertSessionReq, VideoReq, VisionReq
from app.ai.session_store import get_session, set_vision_result, trim_history
from app.core.auth import require_auth_token

router = APIRouter(tags=["ai"])


@router.get("/v1/demo/scenarios")
def demo_scenarios_list():
    """智慧工地九大核心业务场景。"""
    return {"scenarios": list_scenarios()}


@router.post("/v1/demo/scenario")
def demo_scenario_run(req: DemoScenarioReq, authorization: str | None = Header(default=None)):
    """运行单个演示场景，返回语音播报、文档与平台同步状态。"""
    require_auth_token(authorization)
    result = run_scenario(req.scenario_id, device_id=req.device_id)
    if not result.get("scenario_id"):
        raise HTTPException(404, "未知演示场景")
    return result


@router.post("/v1/expert/session")
def expert_session(req: ExpertSessionReq, authorization: str | None = Header(default=None)):
    """AI 技术专家咨询：可选带图，支持同会话多轮追问。"""
    require_auth_token(authorization)
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


@router.websocket("/v1/realtime/voice")
async def realtime_voice(websocket: WebSocket):
    """鉴权后代理设备与百炼 Qwen Realtime 的全双工音频。"""
    try:
        require_auth_token(websocket.headers.get("authorization"))
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
    except Exception as exc:
        import logging

        logging.getLogger("uvicorn.error").exception(
            "realtime upstream failed: %s", exc
        )
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


@router.post("/v1/chat")
def chat(req: ChatReq, authorization: str | None = Header(default=None)):
    require_auth_token(authorization)
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


@router.post("/v1/vision")
def vision(req: VisionReq, authorization: str | None = Header(default=None)):
    require_auth_token(authorization)
    explanation = vision_explain(req.image_base64, question=req.question)
    session = set_vision_result(req.session_id, explanation)
    session.history.append({"role": "user", "content": "[用户上传了一张照片]"})
    session.history.append({"role": "assistant", "content": f"【识图结果】{explanation}"})
    trim_history(session)
    return {
        "explanation": explanation,
        "last_explanation": session.last_explanation,
        "model": os.getenv("VISION_MODEL", "agnes-2.0-flash"),
    }


@router.post("/v1/video")
def video_analyze(req: VideoReq, authorization: str | None = Header(default=None)):
    require_auth_token(authorization)
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
