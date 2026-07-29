"""AI chat / vision / video / realtime / demo / expert routes."""
from __future__ import annotations

from fastapi import APIRouter, Header, HTTPException, WebSocket, WebSocketDisconnect

from app.ai import service as ai_service
from app.ai.realtime_voice import RealtimeSettings, bridge_realtime_websocket
from app.ai.schemas import ChatReq, DemoScenarioReq, ExpertSessionReq, VideoReq, VisionReq
from app.core.auth import require_auth_token

router = APIRouter(tags=["ai"])


@router.get("/v1/demo/scenarios")
def demo_scenarios_list():
    """智慧工地九大核心业务场景。"""
    return ai_service.demo_list()


@router.post("/v1/demo/scenario")
def demo_scenario_run(req: DemoScenarioReq, authorization: str | None = Header(default=None)):
    """运行单个演示场景，返回语音播报、文档与平台同步状态。"""
    require_auth_token(authorization)
    try:
        return ai_service.demo_run(scenario_id=req.scenario_id, device_id=req.device_id)
    except KeyError:
        raise HTTPException(404, "未知演示场景") from None


@router.post("/v1/expert/session")
def expert_session(req: ExpertSessionReq, authorization: str | None = Header(default=None)):
    """AI 技术专家咨询：可选带图，支持同会话多轮追问。"""
    require_auth_token(authorization)
    try:
        return ai_service.expert(
            session_id=req.session_id,
            text=req.text,
            image_base64=req.image_base64,
            device_id=req.device_id,
        )
    except ai_service.ExpertServiceError as exc:
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
    try:
        return ai_service.chat(
            session_id=req.session_id,
            text=req.text,
            device_id=req.device_id,
            state=req.state,
        )
    except Exception as exc:
        raise HTTPException(500, f"chat error: {exc}") from exc


@router.post("/v1/vision")
def vision(req: VisionReq, authorization: str | None = Header(default=None)):
    require_auth_token(authorization)
    return ai_service.vision(
        session_id=req.session_id,
        image_base64=req.image_base64,
        question=req.question,
    )


@router.post("/v1/video")
def video_analyze(req: VideoReq, authorization: str | None = Header(default=None)):
    require_auth_token(authorization)
    return ai_service.video(
        session_id=req.session_id,
        images_base64=req.images_base64,
        frame_count=req.frame_count,
    )
