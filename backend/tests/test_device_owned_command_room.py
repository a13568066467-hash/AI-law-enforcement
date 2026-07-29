"""占用侧持房：ensure / 就绪 / 监看挂已有房（假 MQTT + TRTC 配置）。"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

BACKEND = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND))


@pytest.fixture
def trtc_env(monkeypatch):
    monkeypatch.setenv("TRTC_SDK_APP_ID", "1600152450")
    monkeypatch.setenv("TRTC_SECRET_KEY", "test-secret-key-for-unit")
    from app.command_call import usersig

    usersig.reload_config()
    yield
    monkeypatch.delenv("TRTC_SDK_APP_ID", raising=False)
    monkeypatch.delenv("TRTC_SECRET_KEY", raising=False)
    usersig.reload_config()


@pytest.fixture
def room_ready(trtc_env):
    from app.command_call import service as ccs
    from app.command_call.mqtt import FakeCommandCallMqttPublisher

    mqtt = FakeCommandCallMqttPublisher()
    ccs.reset()
    ccs.use_mqtt(mqtt)
    ccs.use_occupancy_checker(lambda _: True)
    ccs.use_online_checker(lambda _: True)
    return ccs, mqtt


def test_ensure_occupancy_room_then_ready_then_watch_reuses_room(room_ready):
    ccs, mqtt = room_ready
    ensured = ccs.ensure_occupancy_room("DSJ-OWN-001")
    assert ensured["room_id"]
    assert ensured["room_ready"] is False
    assert ensured["device"]["user_sig"]
    assert ensured["device"]["room_id"] == ensured["room_id"]

    join = ccs.poll_device("DSJ-OWN-001")
    assert join is not None
    assert join["action"] == "occupy_room"
    assert join["user_sig"]
    assert join["room_id"] == ensured["room_id"]

    ready = ccs.mark_occupancy_room_ready("DSJ-OWN-001")
    assert ready["room_ready"] is True

    started = ccs.start_watch("DSJ-OWN-001")
    assert started["kind"] == "watch"
    assert started["room_id"] == ensured["room_id"]
    assert started["status"] == "connecting"
    assert started["platform"]["room_id"] == ensured["room_id"]
    assert any(e["payload"].get("action") == "watch_start" for e in mqtt.starts)

    cmd = ccs.poll_device("DSJ-OWN-001")
    assert cmd["action"] == "watch_start"
    assert cmd["room_id"] == ensured["room_id"]
    assert ccs.get_call(started["call_id"])["status"] == "watching"

    ccs.end_watch(started["call_id"])
    end_cmd = ccs.poll_device("DSJ-OWN-001")
    assert end_cmd["action"] == "watch_end"

    still = ccs.get_occupancy_room("DSJ-OWN-001")
    assert still is not None
    assert still["room_id"] == ensured["room_id"]
    assert still["room_ready"] is True


def test_start_watch_rejects_when_room_not_ready(room_ready):
    ccs, _mqtt = room_ready
    ccs.ensure_occupancy_room("DSJ-OWN-NR")
    with pytest.raises(ValueError, match="room not ready"):
        ccs.start_watch("DSJ-OWN-NR")
    with pytest.raises(ValueError, match="room not ready"):
        ccs.start_command_call("DSJ-OWN-NR")


def test_release_occupancy_room_ends_session_and_clears_room(room_ready):
    ccs, mqtt = room_ready
    ensured = ccs.ensure_occupancy_room("DSJ-OWN-REL")
    ccs.mark_occupancy_room_ready("DSJ-OWN-REL")
    started = ccs.start_watch("DSJ-OWN-REL")
    ccs.poll_device("DSJ-OWN-REL")

    ccs.release_occupancy_room("DSJ-OWN-REL")
    assert ccs.get_occupancy_room("DSJ-OWN-REL") is None
    assert ccs.get_call(started["call_id"])["status"] == "ended"
    assert any(e["payload"].get("action") == "occupy_room_end" for e in mqtt.ends)

    end_or_release = ccs.poll_device("DSJ-OWN-REL")
    assert end_or_release is not None
    assert end_or_release["action"] in ("watch_end", "occupy_room_end")

    ccs.ensure_occupancy_room("DSJ-OWN-REL")
    again = ccs.get_occupancy_room("DSJ-OWN-REL")
    assert again is not None
    assert again["room_id"] != ensured["room_id"]
