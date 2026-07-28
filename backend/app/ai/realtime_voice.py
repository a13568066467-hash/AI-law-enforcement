"""Qwen Realtime protocol adapter and authenticated WebSocket bridge."""
from __future__ import annotations

import base64
import asyncio
import json
import os
from dataclasses import dataclass
from typing import Any


ALLOWED_TOOLS = {
    "start_recording",
    "stop_recording",
    "capture_and_explain",
}

DEFAULT_INSTRUCTIONS = (
    "你是赢筑 AI 现场助手。使用简短、口语化中文回答。"
    "用户按住说话期间，你会收到与语音时间轴对齐的连续画面帧；"
    "回答眼前/现场/型号/铭牌/仪表/隐患等问题时，优先依据这些画面帧。"
    "仅当画面缺失、模糊、被遮挡或仍无法判断时，再调用 capture_and_explain"
    "（把用户原话放入 question），等工具返回后再根据 explanation 回答；"
    "禁止在未看过画面帧且未调用该工具前说「看不到」「无法查看」「我没有视觉」之类的话。"
    "开始/停止录像仅在用户明确要求时分别调用 start_recording / stop_recording。"
)


@dataclass(frozen=True)
class RealtimeSettings:
    api_key: str
    workspace_id: str
    model: str = "qwen3.5-omni-flash-realtime"
    voice: str = "Tina"
    region: str = "cn-beijing"
    base_url: str = ""

    @property
    def url(self) -> str:
        root = self.base_url.strip().rstrip("/")
        if not root:
            root = (
                f"wss://{self.workspace_id}.{self.region}.maas.aliyuncs.com"
                "/api-ws/v1/realtime"
            )
        return f"{root}?model={self.model}"

    @classmethod
    def from_env(cls) -> "RealtimeSettings":
        api_key = os.getenv("DASHSCOPE_API_KEY", "").strip()
        workspace_id = os.getenv("DASHSCOPE_WORKSPACE_ID", "").strip()
        if not api_key or not workspace_id:
            raise RuntimeError("未配置 DASHSCOPE_API_KEY 或 DASHSCOPE_WORKSPACE_ID")
        return cls(
            api_key=api_key,
            workspace_id=workspace_id,
            model=os.getenv(
                "REALTIME_MODEL",
                "qwen3.5-omni-flash-realtime",
            ).strip(),
            voice=os.getenv("REALTIME_VOICE", "Tina").strip(),
            region=os.getenv("DASHSCOPE_REALTIME_REGION", "cn-beijing").strip(),
            base_url=os.getenv("DASHSCOPE_REALTIME_BASE_URL", "").strip(),
        )


class RealtimeProtocol:
    """Translate the app's compact protocol to Qwen Realtime events."""

    @staticmethod
    def audio_append(pcm: bytes) -> dict[str, str]:
        return {
            "type": "input_audio_buffer.append",
            "audio": base64.b64encode(pcm).decode("ascii"),
        }

    @staticmethod
    def image_append(image_b64: str) -> dict[str, str]:
        return {
            "type": "input_image_buffer.append",
            "image": image_b64,
        }

    @staticmethod
    def session_update(instructions: str, voice: str) -> dict[str, Any]:
        tools = [
            {
                "type": "function",
                "name": "start_recording",
                "description": "用户明确要求开始录像时调用",
                "parameters": {"type": "object", "properties": {}},
            },
            {
                "type": "function",
                "name": "stop_recording",
                "description": "用户明确要求停止录像时调用",
                "parameters": {"type": "object", "properties": {}},
            },
            {
                "type": "function",
                "name": "capture_and_explain",
                "description": "用户要求观察现场、识别物体或读取仪表时调用",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "question": {
                            "type": "string",
                            "description": "用户希望根据现场画面回答的问题",
                        }
                    },
                },
            },
        ]
        return {
            "type": "session.update",
            "session": {
                "modalities": ["text", "audio"],
                "voice": voice,
                "input_audio_format": "pcm",
                "output_audio_format": "pcm",
                "input_audio_transcription": {"model": "qwen3-asr-flash-realtime"},
                "turn_detection": None,
                "instructions": instructions,
                "tools": tools,
                # Qwen Omni Realtime 不支持 tool_choice，传入会被忽略或干扰工具注册
            },
        }

    @staticmethod
    def client_control(message: dict[str, Any]) -> list[dict[str, Any]]:
        kind = message.get("type")
        if kind == "commit":
            return [
                {"type": "input_audio_buffer.commit"},
                {"type": "response.create"},
            ]
        if kind == "commit_input":
            # 仅提交音频以触发转写，不创建助手回复（现场事件工单 SOS）
            return [
                {"type": "input_audio_buffer.commit"},
            ]
        if kind == "cancel":
            return [
                {"type": "response.cancel"},
                {"type": "input_audio_buffer.clear"},
            ]
        if kind == "tool_result":
            call_id = str(message.get("call_id", "")).strip()
            output = message.get("output", {})
            return [
                {
                    "type": "conversation.item.create",
                    "item": {
                        "type": "function_call_output",
                        "call_id": call_id,
                        "output": json.dumps(output, ensure_ascii=False),
                    },
                },
                {"type": "response.create"},
            ]
        if kind in {"session.start", "ping"}:
            return []
        if kind == "image":
            image = str(message.get("image", "")).strip()
            if not image:
                return []
            # 约 256KB Base64 上限；过大丢弃，不断会话
            if len(image) > 256 * 1024:
                return []
            return [RealtimeProtocol.image_append(image)]
        raise ValueError(f"未知客户端事件: {kind}")

    @staticmethod
    def server_event(event: dict[str, Any]) -> list[bytes | dict[str, Any]]:
        kind = event.get("type")
        if kind == "response.audio.delta":
            try:
                return [base64.b64decode(event.get("delta", ""), validate=True)]
            except (ValueError, TypeError):
                return [
                    {
                        "type": "error",
                        "code": "invalid_audio",
                        "message": "模型返回了无效音频",
                    }
                ]
        if kind == "conversation.item.input_audio_transcription.delta":
            return [
                {
                    "type": "user_transcript_delta",
                    "text": str(event.get("text", "")) + str(event.get("stash", "")),
                }
            ]
        if kind == "conversation.item.input_audio_transcription.completed":
            return [
                {
                    "type": "user_transcript",
                    "text": str(event.get("transcript") or event.get("text") or ""),
                }
            ]
        if kind == "response.audio_transcript.delta":
            return [{"type": "assistant_transcript_delta", "text": str(event.get("delta", ""))}]
        if kind == "response.audio_transcript.done":
            return [
                {
                    "type": "assistant_transcript",
                    "text": str(event.get("transcript", "")),
                }
            ]
        if kind == "response.created":
            return [{"type": "response_started"}]
        if kind == "response.done":
            return [{"type": "response_done"}]
        if kind == "response.function_call_arguments.done":
            name = str(event.get("name", ""))
            call_id = str(event.get("call_id", ""))
            if name not in ALLOWED_TOOLS:
                return [
                    {
                        "type": "error",
                        "code": "tool_not_allowed",
                        "message": f"不允许的工具: {name}",
                    }
                ]
            raw_arguments = event.get("arguments", "{}")
            try:
                arguments = (
                    json.loads(raw_arguments)
                    if isinstance(raw_arguments, str)
                    else dict(raw_arguments)
                )
            except (ValueError, TypeError):
                arguments = {}
            return [
                {
                    "type": "tool_call",
                    "call_id": call_id,
                    "name": name,
                    "arguments": arguments,
                }
            ]
        if kind == "error":
            detail = event.get("error")
            if isinstance(detail, dict):
                code = str(detail.get("code", "upstream_error"))
                message = str(detail.get("message", "实时语音服务异常"))
            else:
                code = "upstream_error"
                message = str(detail or "实时语音服务异常")
            return [{"type": "error", "code": code, "message": message}]
        return []


async def bridge_realtime_websocket(
    client: Any,
    settings: RealtimeSettings,
    connector: Any = None,
) -> None:
    """Bridge one authenticated app WebSocket to one Qwen WebSocket."""
    if connector is None:
        import websockets

        connector = websockets.connect

    async with connector(
        settings.url,
        additional_headers={"Authorization": f"Bearer {settings.api_key}"},
        open_timeout=8,
        close_timeout=3,
        max_size=2 * 1024 * 1024,
    ) as upstream:
        await upstream.send(
            json.dumps(
                RealtimeProtocol.session_update(DEFAULT_INSTRUCTIONS, settings.voice),
                ensure_ascii=False,
            )
        )
        await client.send_json({"type": "ready"})

        async def app_to_qwen() -> None:
            while True:
                message = await client.receive()
                if message.get("type") == "websocket.disconnect":
                    return
                pcm = message.get("bytes")
                text = message.get("text")
                events: list[dict[str, Any]]
                if pcm is not None:
                    events = [RealtimeProtocol.audio_append(pcm)]
                elif text is not None:
                    events = RealtimeProtocol.client_control(json.loads(text))
                else:
                    continue
                for event in events:
                    await upstream.send(json.dumps(event, ensure_ascii=False))

        async def qwen_to_app() -> None:
            async for raw in upstream:
                if isinstance(raw, bytes):
                    continue
                event = json.loads(raw)
                for outgoing in RealtimeProtocol.server_event(event):
                    if isinstance(outgoing, bytes):
                        await client.send_bytes(outgoing)
                    else:
                        await client.send_json(outgoing)

        tasks = [
            asyncio.create_task(app_to_qwen()),
            asyncio.create_task(qwen_to_app()),
        ]
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in pending:
            task.cancel()
        for task in done:
            task.result()
