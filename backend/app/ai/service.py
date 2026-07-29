"""AI chat / vision / video / expert session orchestration."""
from __future__ import annotations

import os
from typing import Any

from app.ai.agents import route_chat, video_explain, vision_explain
from app.ai.demo_scenarios import list_scenarios, run_scenario
from app.ai.expert import ExpertServiceError, consult_expert
from app.ai.session_store import get_session, set_vision_result, trim_history


def chat(*, session_id: str, text: str, device_id: str = "", state: dict[str, Any] | None = None) -> dict[str, Any]:
    session = get_session(session_id)
    ble_state = 0
    if state and "ble_state" in state:
        try:
            ble_state = int(state["ble_state"])
        except (TypeError, ValueError):
            ble_state = 0
    result = route_chat(text, session, ble_state, device_id)
    session.history.append({"role": "user", "content": text})
    session.history.append({"role": "assistant", "content": result.get("reply", "")})
    trim_history(session)
    return result


def vision(*, session_id: str, image_base64: str, question: str = "") -> dict[str, Any]:
    explanation = vision_explain(image_base64, question=question)
    session = set_vision_result(session_id, explanation)
    session.history.append({"role": "user", "content": "[用户上传了一张照片]"})
    session.history.append({"role": "assistant", "content": f"【识图结果】{explanation}"})
    trim_history(session)
    return {
        "explanation": explanation,
        "last_explanation": session.last_explanation,
        "model": os.getenv("VISION_MODEL", "agnes-2.0-flash"),
    }


def video(*, session_id: str, images_base64: list[str], frame_count: int = 0) -> dict[str, Any]:
    fc = frame_count if frame_count > 0 else len(images_base64)
    explanation = video_explain(images_base64, fc)
    session = set_vision_result(session_id, explanation)
    session.history.append({"role": "user", "content": f"[用户上传了一段视频（{fc}帧）]"})
    session.history.append({"role": "assistant", "content": f"【视频分析】{explanation}"})
    trim_history(session)
    return {
        "explanation": explanation,
        "last_explanation": session.last_explanation,
        "model": os.getenv("VISION_MODEL", "agnes-2.0-flash"),
        "frame_count": fc,
    }


def expert(*, session_id: str, text: str = "", image_base64: str = "", device_id: str = "") -> dict[str, Any]:
    session = get_session(session_id)
    return consult_expert(
        text=text,
        session=session,
        image_base64=image_base64,
        device_id=device_id,
    )


def demo_list() -> dict[str, Any]:
    return {"scenarios": list_scenarios()}


def demo_run(*, scenario_id: str, device_id: str = "") -> dict[str, Any]:
    result = run_scenario(scenario_id, device_id=device_id)
    if not result.get("scenario_id"):
        raise KeyError("未知演示场景")
    return result


__all__ = [
    "ExpertServiceError",
    "chat",
    "demo_list",
    "demo_run",
    "expert",
    "video",
    "vision",
]
