"""画面监看会话契约测试（Fake MQTT + TRTC 配置，不接真云）。"""
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
    from app import usersig

    usersig.reload_config()
    yield
    monkeypatch.delenv("TRTC_SDK_APP_ID", raising=False)
    monkeypatch.delenv("TRTC_SECRET_KEY", raising=False)
    usersig.reload_config()


@pytest.fixture
def watch_ready(trtc_env):
    from app import command_call_session as ccs
    from app.command_call_mqtt import FakeCommandCallMqttPublisher

    mqtt = FakeCommandCallMqttPublisher()
    ccs.reset()
    ccs.use_mqtt(mqtt)
    ccs.use_occupancy_checker(lambda _: True)
    ccs.use_online_checker(lambda _: True)
    return ccs, mqtt


def _prime_ready_room(ccs, device_id: str) -> dict:
    ensured = ccs.ensure_occupancy_room(device_id)
    ccs.poll_device(device_id)  # consume occupy_room
    ccs.mark_occupancy_room_ready(device_id)
    return ensured


def test_start_watch_end_to_end(watch_ready):
    ccs, mqtt = watch_ready
    room = _prime_ready_room(ccs, "DSJ-WATCH-001")
    started = ccs.start_watch("DSJ-WATCH-001")
    assert started["kind"] == "watch"
    assert started["status"] == "connecting"
    assert started["room_id"] == room["room_id"]
    assert started["platform"]["user_sig"]
    assert any(e["payload"].get("action") == "watch_start" for e in mqtt.starts)

    cmd = ccs.poll_device("DSJ-WATCH-001")
    assert cmd["action"] == "watch_start"
    assert ccs.get_call(started["call_id"])["status"] == "watching"

    ccs.end_watch(started["call_id"])
    assert any(e["payload"].get("action") == "watch_end" for e in mqtt.ends)
    assert ccs.get_call(started["call_id"])["status"] == "ended"
    end_cmd = ccs.poll_device("DSJ-WATCH-001")
    assert end_cmd["action"] == "watch_end"


def test_watch_rejects_unoccupied_and_offline(trtc_env):
    from app import command_call_session as ccs
    from app.command_call_mqtt import FakeCommandCallMqttPublisher

    ccs.reset()
    ccs.use_mqtt(FakeCommandCallMqttPublisher())
    ccs.use_occupancy_checker(lambda _: False)
    ccs.use_online_checker(lambda _: True)
    with pytest.raises(ValueError, match="not occupied"):
        ccs.start_watch("DSJ-FREE")

    ccs.use_occupancy_checker(lambda _: True)
    ccs.use_online_checker(lambda _: False)
    with pytest.raises(ValueError, match="offline"):
        ccs.start_watch("DSJ-OFF")


def test_device_busy_rejects_second_watch_or_call(watch_ready):
    ccs, _mqtt = watch_ready
    _prime_ready_room(ccs, "DSJ-BUSY")
    first = ccs.start_watch("DSJ-BUSY")
    with pytest.raises(ValueError, match="busy"):
        ccs.start_watch("DSJ-BUSY")
    with pytest.raises(ValueError, match="busy"):
        ccs.start_command_call("DSJ-BUSY")
    ccs.end_watch(first["call_id"])


def test_upgrade_watch_to_call_same_room(watch_ready):
    ccs, mqtt = watch_ready
    _prime_ready_room(ccs, "DSJ-UP")
    started = ccs.start_watch("DSJ-UP")
    room = started["room_id"]
    ccs.poll_device("DSJ-UP")

    upgraded = ccs.upgrade_watch_to_call(started["call_id"])
    assert upgraded["kind"] == "call"
    assert upgraded["status"] == "in_call"
    assert upgraded["room_id"] == room
    assert upgraded["call_id"] == started["call_id"]
    assert any(e["payload"].get("action") == "call_upgrade" for e in mqtt.starts)

    cmd = ccs.poll_device("DSJ-UP")
    assert cmd is not None
    assert cmd["action"] == "call_upgrade"
    assert cmd["call_id"] == started["call_id"]
    assert cmd["room_id"] == room


def test_watch_heartbeat_timeout_ends_session(watch_ready):
    ccs, mqtt = watch_ready
    clock = {"t": 1000.0}
    ccs.use_clock(lambda: clock["t"])

    _prime_ready_room(ccs, "DSJ-HB")
    started = ccs.start_watch("DSJ-HB")
    ccs.poll_device("DSJ-HB")
    ccs.touch_watch_heartbeat(started["call_id"])

    clock["t"] += ccs.WATCH_HEARTBEAT_TIMEOUT_SECONDS + 1
    failed = ccs.sweep_timeouts()
    assert started["call_id"] in failed
    assert ccs.get_call(started["call_id"])["status"] == "ended"
    assert any(e["payload"].get("action") == "watch_end" for e in mqtt.ends)


def test_cold_start_command_call_still_works(watch_ready):
    ccs, mqtt = watch_ready
    _prime_ready_room(ccs, "DSJ-COLD")
    started = ccs.start_command_call("DSJ-COLD")
    assert started["kind"] == "call"
    assert any(e["payload"].get("action") == "call_start" for e in mqtt.starts)
    cmd = ccs.poll_device("DSJ-COLD")
    assert cmd["action"] == "call_start"
    assert ccs.get_call(started["call_id"])["status"] == "in_call"
