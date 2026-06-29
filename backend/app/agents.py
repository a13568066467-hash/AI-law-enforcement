"""Agent A/B 路由与回复（无 API Key 时走规则 mock）。"""

from __future__ import annotations



import json

import os

import re

from typing import Any



from openai import OpenAI



from .session_store import SessionData



CHAT_MODEL = os.getenv("CHAT_MODEL", "qwen-turbo")

VISION_MODEL = os.getenv("VISION_MODEL", "qwen3-vl-8b-instruct")

MAX_HISTORY_TURNS = 24



_CAPTURE_PHRASES = ("识别一下", "拍一张", "拍照", "识图", "拍摄一张", "拍一张看看")

_RE_RECAPTURE = re.compile(r"再拍|重新(拍|识别)|换一张")



INTENT_CMD = {

    "start_recording": 0x01,

    "stop_recording": 0x02,

    "capture_and_explain": 0x03,

    "start_ai_listen": 0x04,

    "stop_ai_listen": 0x05,

}





def _client() -> OpenAI | None:

    key = os.getenv("DASHSCOPE_API_KEY", "").strip()

    if not key:

        return None

    base = os.getenv("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")

    return OpenAI(api_key=key, base_url=base)





def _ble_cmds_for_intent(intent: str) -> list[dict[str, int]]:

    cmd = INTENT_CMD.get(intent)

    if cmd is None:

        return []

    return [{"cmd": cmd}]





def _is_device_control(text: str) -> bool:

    t = text.strip()

    if any(k in t for k in ("开始录像", "开录", "录一下", "停止录像", "停录", "结束录像")):

        return True

    if _RE_RECAPTURE.search(t):

        return True

    return any(p in t for p in _CAPTURE_PHRASES)





def _should_use_agent_b(text: str, session: SessionData) -> bool:

    """有上次识图且非设备控制指令时，沿用照片记忆回答。"""

    return bool(session.last_explanation.strip()) and not _is_device_control(text)





def _parse_reply_json(raw: str) -> tuple[str, str]:

    try:

        data = json.loads(raw)

        if isinstance(data, dict):

            return data.get("intent", "chat"), data.get("reply", raw)

    except json.JSONDecodeError:

        pass

    return "chat", raw





def _mock_route(text: str, session: SessionData) -> dict[str, Any]:

    t = text.strip()

    tl = t.lower()



    if any(k in t for k in ("开始录像", "开录", "录一下")):

        return {"agent": "A", "intent": "start_recording", "reply": "好的，开始录像。", "ble_cmds": _ble_cmds_for_intent("start_recording")}

    if any(k in t for k in ("停止录像", "停录", "结束录像")):

        return {"agent": "A", "intent": "stop_recording", "reply": "录像已停止。", "ble_cmds": _ble_cmds_for_intent("stop_recording")}

    if _is_device_control(t):

        return {"agent": "A", "intent": "capture_and_explain", "reply": "好的，我来拍一张看看。", "ble_cmds": _ble_cmds_for_intent("capture_and_explain")}



    if _should_use_agent_b(t, session):

        return {

            "agent": "B",

            "intent": "chat",

            "reply": f"结合上次识图记忆：\n{session.last_explanation[:500]}\n\n如需更新画面可说「再拍一张」。",

            "ble_cmds": [],

        }



    if any(k in tl for k in ("你好", "hello", "在吗")):

        return {"agent": "A", "intent": "chat", "reply": "我在，可以让我开始录像、停止录像或上传/拍摄照片识别。", "ble_cmds": []}



    return {"agent": "A", "intent": "chat", "reply": "收到。可以说「开始录像」「停止录像」或上传照片识别。", "ble_cmds": []}





def _llm_chat_with_history(

    system: str,

    user: str,

    history: list[dict[str, str]],

    max_tokens: int = 512,

) -> str:

    client = _client()

    if not client:

        return ""

    messages: list[dict[str, str]] = [{"role": "system", "content": system}]

    for item in history[-MAX_HISTORY_TURNS:]:

        role = item.get("role", "")

        content = item.get("content", "")

        if role in ("user", "assistant") and content:

            messages.append({"role": role, "content": content})

    messages.append({"role": "user", "content": user})

    try:

        resp = client.chat.completions.create(

            model=CHAT_MODEL,

            messages=messages,

            max_tokens=max_tokens,

            temperature=0.3,

        )

        return (resp.choices[0].message.content or "").strip()

    except Exception:

        return ""





def _route_agent_b(text: str, session: SessionData) -> dict[str, Any]:

    system = (

        "你是工地现场安全巡检助手 Agent-B。用户曾上传/拍摄照片，识图结果在 last_explanation。"

        "请结合 last_explanation 与对话历史，回答用户关于该照片的任何追问"

        "（识别内容、安全隐患、设备型号、整改建议等）。"

        "在新照片上传前，必须沿用这份识图记忆，不要要求用户重新上传或重新提供照片。"

        "仅当用户明确说「再拍一张」「重新识别」时才提示可以换一张。"

        "输出 JSON：{\"intent\":\"chat\",\"reply\":\"口语回答，可分段\"}"

    )

    user = json.dumps(

        {"last_explanation": session.last_explanation, "user_question": text},

        ensure_ascii=False,

    )

    raw = _llm_chat_with_history(system, user, session.history, max_tokens=512)

    if not raw:

        return _mock_route(text, session)

    _, reply = _parse_reply_json(raw)

    return {"agent": "B", "intent": "chat", "reply": reply or "好的。", "ble_cmds": []}





def _route_agent_a(text: str, session: SessionData, ble_state: int) -> dict[str, Any]:

    system = (

        "你是工地实地相机 Agent-A。口语简洁。"

        "控制类输出 JSON："

        '{"intent":"start_recording|stop_recording|capture_and_explain|chat|none","reply":"短句","confidence":0.9}'

        "录像中(ble_state=3)不要 start_recording。"

        "若 last_explanation 非空，普通闲聊可简要提及「仍记得上次照片」但不要重复整段识图。"

    )

    user = json.dumps(

        {

            "text": text,

            "ble_state": ble_state,

            "last_explanation": session.last_explanation or "",

        },

        ensure_ascii=False,

    )

    raw = _llm_chat_with_history(system, user, session.history, max_tokens=384)

    if not raw:

        return _mock_route(text, session)

    intent, reply = _parse_reply_json(raw)

    if intent not in INTENT_CMD and intent not in ("chat", "none"):

        intent = "chat"

    ble_cmds = _ble_cmds_for_intent(intent) if intent in INTENT_CMD else []

    return {"agent": "A", "intent": intent, "reply": reply or "好的。", "ble_cmds": ble_cmds}





def route_chat(text: str, session: SessionData, ble_state: int = 0) -> dict[str, Any]:

    """Router → Agent A 或 B。"""

    if not _client():

        return _mock_route(text, session)

    if _should_use_agent_b(text, session):

        return _route_agent_b(text, session)

    return _route_agent_a(text, session, ble_state)





_MOCK_VISION_REPLY = (

    "【识别内容】可见施工区域、机械设备与作业面，局部有铭牌/仪表区域。\n"

    "【安全隐患】模拟分析：临边作业区护栏不明显，部分人员未清晰佩戴安全帽；"

    "未见明显消防器材。建议现场复核并整改。"

)





def vision_explain(image_base64: str) -> str:

    client = _client()

    if not client:

        return _MOCK_VISION_REPLY



    prompt = (

        "你是工地现场安全巡检助手。分析图片并用口语输出两段：\n"

        "1.【识别内容】说明图中设备、人员、环境、铭牌仪表等可见内容；\n"

        "2.【安全隐患】列出可见隐患（个体防护、临边洞口、用电、消防、违章作业等）；"

        "若看不清或无明显隐患，写「未发现明显隐患」或「该部分画面不清晰，建议近拍复核」。\n"

        "禁止编造看不清的读数或细节，总共不超过6句。"

    )

    try:

        resp = client.chat.completions.create(

            model=VISION_MODEL,

            messages=[

                {

                    "role": "user",

                    "content": [

                        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}},

                        {"type": "text", "text": prompt},

                    ],

                }

            ],

            max_tokens=768,

        )

        text = (resp.choices[0].message.content or "").strip()

        if text:

            return text

    except Exception:

        pass

    return _MOCK_VISION_REPLY


