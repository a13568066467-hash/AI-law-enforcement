"""Red-capable loop: after session.update, tools must stick; scene question should tool-call.

Exit 0 = green (tools present in session.updated AND capture_and_explain invoked
for a front-of-camera style user question). Exit 1 = red.
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
from pathlib import Path

from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
load_dotenv(ROOT / ".env", override=True)

from app.ai.realtime_voice import DEFAULT_INSTRUCTIONS, RealtimeProtocol, RealtimeSettings  # noqa: E402


async def main() -> int:
    import websockets

    settings = RealtimeSettings.from_env()
    update = RealtimeProtocol.session_update(DEFAULT_INSTRUCTIONS, settings.voice)
    print("SEND_TOOLS_SHAPE", json.dumps(update["session"]["tools"][0], ensure_ascii=False)[:200])
    print("HAS_TOOL_CHOICE", "tool_choice" in update["session"])

    events: list[dict] = []
    async with websockets.connect(
        settings.url,
        additional_headers={"Authorization": f"Bearer {settings.api_key}"},
        open_timeout=15,
        max_size=2 * 1024 * 1024,
    ) as ws:
        await ws.send(json.dumps(update, ensure_ascii=False))

        # Collect until session.updated or error (or timeout)
        session_updated = None
        deadline = asyncio.get_event_loop().time() + 12
        while asyncio.get_event_loop().time() < deadline:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=3)
            except asyncio.TimeoutError:
                break
            if isinstance(raw, (bytes, bytearray)):
                continue
            ev = json.loads(raw)
            events.append(ev)
            print("EV", ev.get("type"), list(ev.keys()))
            if ev.get("type") == "session.updated":
                session_updated = ev
                break
            if ev.get("type") == "error":
                print("ERROR", json.dumps(ev, ensure_ascii=False)[:500])
                break

        tools_in_session = []
        if session_updated:
            sess = session_updated.get("session") or {}
            tools_in_session = sess.get("tools") or []
            print("TOOLS_COUNT", len(tools_in_session))
            print("TOOLS", json.dumps(tools_in_session, ensure_ascii=False)[:800])
        else:
            print("NO_SESSION_UPDATED")

        # Inject text user turn asking about what's in front
        await ws.send(
            json.dumps(
                {
                    "type": "conversation.item.create",
                    "item": {
                        "type": "message",
                        "role": "user",
                        "content": [
                            {
                                "type": "input_text",
                                "text": "我面前的设备是什么？请根据摄像头画面告诉我。",
                            }
                        ],
                    },
                },
                ensure_ascii=False,
            )
        )
        await ws.send(json.dumps({"type": "response.create"}))

        got_tool = False
        transcript_bits: list[str] = []
        deadline = asyncio.get_event_loop().time() + 25
        while asyncio.get_event_loop().time() < deadline:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=5)
            except asyncio.TimeoutError:
                break
            if isinstance(raw, (bytes, bytearray)):
                continue
            ev = json.loads(raw)
            kind = ev.get("type")
            if kind == "response.function_call_arguments.done":
                print("TOOL_DONE", ev.get("name"), ev.get("arguments"))
                if ev.get("name") == "capture_and_explain":
                    got_tool = True
                    break
            elif kind == "response.audio_transcript.done":
                transcript_bits.append(str(ev.get("transcript", "")))
                print("ASSISTANT", ev.get("transcript"))
            elif kind == "response.done":
                print("RESPONSE_DONE")
                # keep reading briefly for late tool events
            elif kind == "error":
                print("ERROR", json.dumps(ev, ensure_ascii=False)[:500])
                break
            else:
                if kind and "function" in kind:
                    print("FN_EV", kind, json.dumps(ev, ensure_ascii=False)[:300])

    tools_ok = any(
        (t.get("name") == "capture_and_explain")
        or ((t.get("function") or {}).get("name") == "capture_and_explain")
        for t in tools_in_session
    )
    print("ASSERT tools_registered ->", tools_ok)
    print("ASSERT capture_and_explain_called ->", got_tool)
    print("ASSISTANT_TEXT", " | ".join(transcript_bits)[:300])
    return 0 if (tools_ok and got_tool) else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
