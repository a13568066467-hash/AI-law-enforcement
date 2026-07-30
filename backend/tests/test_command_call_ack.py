"""设备 ack 置 start_delivered / join_delivered。"""
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
def ccs(trtc_env):
    from app.command_call import service as ccs
    from app.command_call.mqtt import FakeCommandCallMqttPublisher

    ccs.reset()
    ccs.use_mqtt(FakeCommandCallMqttPublisher())
    ccs.use_occupancy_checker(lambda _d: True)
    ccs.use_online_checker(lambda _d: True)
    yield ccs
    ccs.reset()


def test_ack_device_start_marks_delivered(ccs):
    room = ccs.ensure_occupancy_room("DSJ-ACK-1")
    ccs.mark_occupancy_room_ready("DSJ-ACK-1")
    # consume occupy via poll so join_delivered
    ccs.poll_device("DSJ-ACK-1")
    session = ccs.start_watch("DSJ-ACK-1", "指挥")
    call_id = session["call_id"]
    assert session["start_delivered"] is False
    acked = ccs.ack_device_start(call_id, "DSJ-ACK-1")
    assert acked["start_delivered"] is True
    assert acked["status"] == "watching"
    # poll 不应再吐 watch_start
    assert ccs.poll_device("DSJ-ACK-1") is None


def test_ack_occupancy_join(ccs):
    ccs.ensure_occupancy_room("DSJ-ACK-2")
    out = ccs.ack_occupancy_join("DSJ-ACK-2")
    assert out["device_id"] == "DSJ-ACK-2"
    # join 已 ack，poll 不再下发 occupy_room
    assert ccs.poll_device("DSJ-ACK-2") is None
