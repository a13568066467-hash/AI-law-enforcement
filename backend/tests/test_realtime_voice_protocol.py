"""RealtimeProtocol: image bridge + instructions."""
from __future__ import annotations

import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND))

from app.realtime_voice import DEFAULT_INSTRUCTIONS, RealtimeProtocol


def test_image_append_shape():
    ev = RealtimeProtocol.image_append("abc123")
    assert ev == {"type": "input_image_buffer.append", "image": "abc123"}


def test_client_control_image():
    events = RealtimeProtocol.client_control({"type": "image", "image": "Zm9v"})
    assert events == [
        {"type": "input_image_buffer.append", "image": "Zm9v"},
    ]


def test_client_control_image_missing_drops():
    assert RealtimeProtocol.client_control({"type": "image"}) == []
    assert RealtimeProtocol.client_control({"type": "image", "image": ""}) == []
    assert RealtimeProtocol.client_control({"type": "image", "image": "   "}) == []


def test_client_control_commit_creates_response():
    events = RealtimeProtocol.client_control({"type": "commit"})
    assert events == [
        {"type": "input_audio_buffer.commit"},
        {"type": "response.create"},
    ]


def test_client_control_commit_input_transcribe_only():
    events = RealtimeProtocol.client_control({"type": "commit_input"})
    assert events == [{"type": "input_audio_buffer.commit"}]
    assert not any(e.get("type") == "response.create" for e in events)


def test_instructions_prefer_streamed_frames():
    assert "看不到摄像头" not in DEFAULT_INSTRUCTIONS
    assert "画面帧" in DEFAULT_INSTRUCTIONS or "连续画面" in DEFAULT_INSTRUCTIONS
    assert "capture_and_explain" in DEFAULT_INSTRUCTIONS


def test_instructions_prefer_streamed_frames():
    assert "看不到摄像头" not in DEFAULT_INSTRUCTIONS
    assert "画面帧" in DEFAULT_INSTRUCTIONS or "连续画面" in DEFAULT_INSTRUCTIONS
    assert "capture_and_explain" in DEFAULT_INSTRUCTIONS
