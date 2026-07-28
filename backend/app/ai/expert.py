"""AI 技术专家咨询（形态 A：云端大模型，无真人坐席）。"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

from .agents import CHAT_MODEL, VISION_MODEL, _client
from .session_store import SessionData, trim_history

MAX_EXPERT_TURNS = 20


class ExpertServiceError(RuntimeError):
    """云端专家不可用（未配置密钥、模型失败等）。"""

_DEFAULT_QUESTION = (
    "现场安全员请求技术专家远程指导，请结合可见信息给出风险研判与可执行处置步骤。"
)

_EXPERT_VISION_PROMPT = (
    "你是工地技术专家助手。分析图片并输出：\n"
    "1.【现场概况】人员、工序、设备、环境；\n"
    "2.【风险点】结构/工艺/设备/防护类问题；\n"
    "3.【需专家重点确认】列出 1～3 条。\n"
    "看不清的不要编造，总共不超过 8 句。"
)

_EXPERT_SYSTEM = (
    "你是「赢筑 AI 技术专家」，面向工地安全员提供远程技术指导。\n"
    "职责：结构/脚手架/临边洞口/动火用电/设备安装/工艺违规等疑难问题的研判与处置建议。\n"
    "要求：\n"
    "- 口语化、分点清晰，先给结论再给步骤；\n"
    "- 仅依据 scene_context 与对话，禁止编造规范号、读数、未给出的尺寸；\n"
    "- 信息不足时明确说「建议近拍/补测/现场确认 XX」；\n"
    "- 涉及立即停工或人身风险时，第一条必须强调现场管控；\n"
    "输出 JSON："
    '{"reply":"给安全员听的完整建议","highlights":["要点1","要点2"],"need_follow_up":false}'
)


def _now_label() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


def _parse_expert_json(raw: str) -> tuple[str, list[str]]:
    try:
        data = json.loads(raw)
        if isinstance(data, dict):
            reply = str(data.get("reply", raw)).strip()
            hl = data.get("highlights", [])
            if isinstance(hl, list):
                highlights = [str(x).strip() for x in hl if str(x).strip()]
            else:
                highlights = []
            return reply, highlights
    except json.JSONDecodeError:
        pass
    return raw.strip(), []


def _expert_vision(image_base64: str) -> str:
    client = _client()
    if not client:
        raise ExpertServiceError("未配置 DASHSCOPE_API_KEY，无法分析现场画面")
    try:
        resp = client.chat.completions.create(
            model=VISION_MODEL,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image_url",
                            "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"},
                        },
                        {"type": "text", "text": _EXPERT_VISION_PROMPT},
                    ],
                }
            ],
            max_tokens=768,
        )
        text = (resp.choices[0].message.content or "").strip()
        if text:
            return text
        raise ExpertServiceError("画面分析无有效回复")
    except ExpertServiceError:
        raise
    except Exception as exc:
        raise ExpertServiceError(f"画面分析失败: {exc}") from exc


def _llm_expert(
    question: str,
    scene_context: str,
    history: list[dict[str, str]],
) -> tuple[str, list[str]]:
    client = _client()
    if not client:
        raise ExpertServiceError("未配置 DASHSCOPE_API_KEY，无法调用 AI 专家")

    user_payload = json.dumps(
        {
            "question": question,
            "scene_context": scene_context or "（暂无现场画面描述）",
        },
        ensure_ascii=False,
    )
    messages: list[dict[str, str]] = [{"role": "system", "content": _EXPERT_SYSTEM}]
    for item in history[-MAX_EXPERT_TURNS:]:
        role = item.get("role", "")
        content = item.get("content", "")
        if role in ("user", "assistant") and content:
            messages.append({"role": role, "content": content})
    messages.append({"role": "user", "content": user_payload})

    try:
        resp = client.chat.completions.create(
            model=CHAT_MODEL,
            messages=messages,
            max_tokens=768,
            temperature=0.35,
        )
        raw = (resp.choices[0].message.content or "").strip()
        if raw:
            return _parse_expert_json(raw)
        raise ExpertServiceError("专家模型无有效回复")
    except ExpertServiceError:
        raise
    except Exception as exc:
        raise ExpertServiceError(f"专家咨询失败: {exc}") from exc


def _build_document(
    *,
    device_id: str,
    question: str,
    reply: str,
    scene_context: str,
    record_id: str,
) -> str:
    return (
        f"《AI技术专家咨询存档》\n"
        f"编号：{record_id}\n"
        f"时间：{_now_label()}\n"
        f"设备：{device_id or 'UNKNOWN'}\n"
        f"模式：赢筑 AI 专家（云端大模型）\n"
        f"—— 咨询问题 ——\n{question}\n"
        f"—— 现场上下文 ——\n{scene_context or '（无）'}\n"
        f"—— 专家建议 ——\n{reply}\n"
        f"状态：已存档（会话内可追溯）"
    )


def consult_expert(
    *,
    text: str,
    session: SessionData,
    image_base64: str = "",
    device_id: str = "",
) -> dict[str, Any]:
    question = (text or "").strip() or _DEFAULT_QUESTION
    scene_context = ""

    if image_base64.strip():
        scene_context = _expert_vision(image_base64.strip())
        session.last_explanation = scene_context
        session.last_vision_at = datetime.now(timezone.utc)
    elif session.last_explanation.strip():
        scene_context = session.last_explanation.strip()

    reply, highlights = _llm_expert(question, scene_context, session.expert_history)

    record_id = f"exp-{int(datetime.now(timezone.utc).timestamp())}"
    document = _build_document(
        device_id=device_id,
        question=question,
        reply=reply,
        scene_context=scene_context,
        record_id=record_id,
    )

    session.expert_history.append({"role": "user", "content": question})
    session.expert_history.append({"role": "assistant", "content": reply})
    if len(session.expert_history) > MAX_EXPERT_TURNS * 2:
        session.expert_history = session.expert_history[-(MAX_EXPERT_TURNS * 2) :]

    session.history.append({"role": "user", "content": f"[专家咨询] {question}"})
    session.history.append({"role": "assistant", "content": f"[专家答复] {reply}"})
    trim_history(session)

    voice = reply.split("\n")[0][:120]
    if len(reply) > 120:
        voice += "…"

    if not highlights:
        highlights = ["AI专家研判", "处置步骤", "会话存档"]

    return {
        "record_id": record_id,
        "scenario_id": "expert_call",
        "title": "AI 技术专家咨询",
        "voice_broadcast": f"专家已回复。{voice}",
        "reply": reply,
        "document": document,
        "highlights": highlights,
        "platform_sync": "已存档至本会话（后续可接管控平台）",
        "ble_cmds": [],
        "alert_level": "info",
        "scene_context": scene_context,
    }


def is_expert_intent(text: str) -> bool:
    t = text.strip()
    if not t:
        return False
    keys = ("专家", "连线", "远程指导", "呼叫技术", "全双工", "技术专家")
    return any(k in t for k in keys)
