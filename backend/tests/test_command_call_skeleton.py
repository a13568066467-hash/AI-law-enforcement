"""指挥连线呼叫骨架契约测试（Fake MQTT + Fake UserSig 配置，不接真 TRTC）。"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

BACKEND = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND))


@pytest.fixture
def trtc_env(monkeypatch):
    monkeypatch.setenv("TRTC_SDK_APP_ID", "1600152450")
    monkeypatch.setenv("TRTC_SECRET_KEY", "test-secret-key-for-unit")
    from app import usersig

    usersig.reload_config()
    yield
    monkeypatch.delenv("TRTC_SDK_APP_ID", raising=False)
    monkeypatch.delenv("TRTC_SECRET_KEY", raising=False)
    usersig.reload_config()


def test_trtc_configured_false_without_env(monkeypatch):
    monkeypatch.delenv("TRTC_SDK_APP_ID", raising=False)
    monkeypatch.delenv("TRTC_SECRET_KEY", raising=False)
    from app import usersig

    usersig.reload_config()
    assert usersig.trtc_configured() is False


def test_issue_user_sig_requires_config(monkeypatch):
    monkeypatch.delenv("TRTC_SDK_APP_ID", raising=False)
    monkeypatch.delenv("TRTC_SECRET_KEY", raising=False)
    from app import usersig

    usersig.reload_config()
    with pytest.raises(RuntimeError, match="TRTC"):
        usersig.issue_user_sig("platform-1")


def test_issue_user_sig_returns_nonempty(trtc_env):
    from app import usersig

    assert usersig.trtc_configured() is True
    sig = usersig.issue_user_sig("device-DSJ-1")
    assert isinstance(sig, str) and len(sig) > 10
    assert usersig.sdk_app_id() == 1600152450


def test_command_call_skeleton_end_to_end(trtc_env):
    from app import command_call_session as ccs
    from app.command_call_mqtt import FakeCommandCallMqttPublisher

    mqtt = FakeCommandCallMqttPublisher()
    ccs.reset()
    ccs.use_mqtt(mqtt)
    ccs.use_occupancy_checker(lambda _device_id: True)

    ccs.ensure_occupancy_room("DSJ-E2E-001")
    ccs.poll_device("DSJ-E2E-001")
    ccs.mark_occupancy_room_ready("DSJ-E2E-001")

    started = ccs.start_command_call("DSJ-E2E-001")
    assert started["status"] == "connecting"
    assert started["room_id"]
    assert started["platform"]["user_sig"]
    assert started["platform"]["sdk_app_id"] == 1600152450
    assert "secret" not in str(started).lower()
    assert any(e["payload"].get("action") == "call_start" for e in mqtt.starts)

    # 设备经 HTTP 兜底拉到开始（推流/连线态）；客户端用 Fake 进房。
    cmd = ccs.poll_device("DSJ-E2E-001")
    assert cmd is not None
    assert cmd["action"] == "call_start"
    assert cmd["user_sig"]
    assert ccs.get_call(started["call_id"])["status"] == "in_call"

    ccs.end_command_call(started["call_id"])
    assert any(e["payload"].get("action") == "call_end" for e in mqtt.ends)
    assert ccs.get_call(started["call_id"])["status"] == "ended"
    end_cmd = ccs.poll_device("DSJ-E2E-001")
    assert end_cmd is not None
    assert end_cmd["action"] == "call_end"


def test_start_rejects_unoccupied_device(trtc_env):
    from app import command_call_session as ccs
    from app.command_call_mqtt import FakeCommandCallMqttPublisher

    ccs.reset()
    ccs.use_mqtt(FakeCommandCallMqttPublisher())
    ccs.use_occupancy_checker(lambda _device_id: False)

    with pytest.raises(ValueError, match="not occupied"):
        ccs.start_command_call("DSJ-FREE-001")


def test_health_includes_trtc(trtc_env):
    from app.main import health

    body = health()
    assert "trtc" in body
    assert body["trtc"] is True


def test_device_does_not_need_answer_busy_hangup_fields(trtc_env):
    """连线信令仅有 call_start / call_end；无 answer/busy/hangup 字段要求。"""
    from app import command_call_session as ccs
    from app.command_call_mqtt import FakeCommandCallMqttPublisher

    mqtt = FakeCommandCallMqttPublisher()
    ccs.reset()
    ccs.use_mqtt(mqtt)
    ccs.use_occupancy_checker(lambda _: True)

    ccs.ensure_occupancy_room("DSJ-NO-UPLINK")
    ccs.poll_device("DSJ-NO-UPLINK")
    ccs.mark_occupancy_room_ready("DSJ-NO-UPLINK")
    started = ccs.start_command_call("DSJ-NO-UPLINK")
    payload = next(e["payload"] for e in mqtt.starts if e["payload"].get("action") == "call_start")
    for forbidden in ("answer", "busy", "hangup"):
        assert forbidden not in payload
    ccs.end_command_call(started["call_id"])
    end_payload = next(e["payload"] for e in mqtt.ends if e["payload"].get("action") == "call_end")
    assert set(end_payload.keys()) <= {"action", "call_id"}


def test_second_call_while_busy_is_rejected(trtc_env):
    """同一设备已有活跃会话时拒绝第二路（严格互斥）。"""
    from app import command_call_session as ccs
    from app.command_call_mqtt import FakeCommandCallMqttPublisher

    ccs.reset()
    ccs.use_mqtt(FakeCommandCallMqttPublisher())
    ccs.use_occupancy_checker(lambda _: True)

    ccs.ensure_occupancy_room("DSJ-REPLACE")
    ccs.poll_device("DSJ-REPLACE")
    ccs.mark_occupancy_room_ready("DSJ-REPLACE")
    first = ccs.start_command_call("DSJ-REPLACE")
    with pytest.raises(ValueError, match="busy"):
        ccs.start_command_call("DSJ-REPLACE")
    cmd = ccs.poll_device("DSJ-REPLACE")
    assert cmd is not None
    assert cmd["action"] == "call_start"
    assert cmd["call_id"] == first["call_id"]
