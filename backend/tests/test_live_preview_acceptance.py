"""切片 6 — 画面监看端到端验收剧本（假 TRTC/MQTT）。

剧本：监看 → 升级 → 结束；互斥拒绝；HTTP 契约。
"""
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
def session_stack(trtc_env):
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
    ccs.poll_device(device_id)
    ccs.mark_occupancy_room_ready(device_id)
    return ensured


def test_acceptance_watch_upgrade_end_mutex(session_stack):
    """主验收：点选监看 → 同房升级 → 结束；第二路 busy。"""
    ccs, mqtt = session_stack

    room = _prime_ready_room(ccs, "DSJ-ACC-1")
    watch = ccs.start_watch("DSJ-ACC-1")
    assert watch["kind"] == "watch"
    assert watch["room_id"] == room["room_id"]
    assert ccs.poll_device("DSJ-ACC-1")["action"] == "watch_start"
    assert ccs.get_call(watch["call_id"])["status"] == "watching"

    with pytest.raises(ValueError, match="busy"):
        ccs.start_watch("DSJ-ACC-1")
    with pytest.raises(ValueError, match="busy"):
        ccs.start_command_call("DSJ-ACC-1")

    call = ccs.upgrade_watch_to_call(watch["call_id"])
    assert call["kind"] == "call"
    assert call["room_id"] == watch["room_id"]
    assert call["call_id"] == watch["call_id"]
    assert ccs.poll_device("DSJ-ACC-1")["action"] == "call_upgrade"

    ccs.end_command_call(call["call_id"])
    assert ccs.get_call(call["call_id"])["status"] == "ended"
    assert ccs.poll_device("DSJ-ACC-1")["action"] == "call_end"
    assert mqtt.ends[-1]["payload"]["action"] == "call_end"

    # 结束后可再监看（占用房仍在）
    again = ccs.start_watch("DSJ-ACC-1")
    assert again["call_id"] != watch["call_id"]
    assert again["kind"] == "watch"
    assert again["room_id"] == room["room_id"]


def test_acceptance_http_watch_lifecycle(trtc_env):
    """HTTP：ensure/ready → watch/start → heartbeat → upgrade → end。"""
    from fastapi.testclient import TestClient

    from app import command_call_session as ccs
    from app.command_call_mqtt import FakeCommandCallMqttPublisher
    from app.main import app

    mqtt = FakeCommandCallMqttPublisher()
    ccs.reset()
    ccs.use_mqtt(mqtt)
    ccs.use_occupancy_checker(lambda _: True)
    ccs.use_online_checker(lambda _: True)

    client = TestClient(app)
    ens = client.post("/v1/command-call/occupancy-room/ensure", json={"device_id": "DSJ-HTTP-1"})
    assert ens.status_code == 200, ens.text
    ready = client.post("/v1/command-call/device/DSJ-HTTP-1/occupancy-room/ready")
    assert ready.status_code == 200
    assert ready.json()["room_ready"] is True

    r = client.post("/v1/command-call/watch/start", json={"device_id": "DSJ-HTTP-1"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["kind"] == "watch"
    call_id = body["call_id"]

    hb = client.post(f"/v1/command-call/{call_id}/watch/heartbeat")
    assert hb.status_code == 200

    up = client.post(f"/v1/command-call/{call_id}/upgrade")
    assert up.status_code == 200
    assert up.json()["kind"] == "call"

    # 升级后心跳应拒绝
    hb2 = client.post(f"/v1/command-call/{call_id}/watch/heartbeat")
    assert hb2.status_code == 400

    end = client.post(f"/v1/command-call/{call_id}/end")
    assert end.status_code == 200
    assert client.get(f"/v1/command-call/{call_id}").json()["status"] == "ended"


def test_acceptance_http_busy_and_offline(trtc_env):
    from fastapi.testclient import TestClient

    from app import command_call_session as ccs
    from app.command_call_mqtt import FakeCommandCallMqttPublisher
    from app.main import app

    ccs.reset()
    ccs.use_mqtt(FakeCommandCallMqttPublisher())
    ccs.use_occupancy_checker(lambda _: True)
    ccs.use_online_checker(lambda _: True)
    client = TestClient(app)

    assert client.post(
        "/v1/command-call/occupancy-room/ensure", json={"device_id": "DSJ-B1"}
    ).status_code == 200
    assert client.post("/v1/command-call/device/DSJ-B1/occupancy-room/ready").status_code == 200
    assert client.post("/v1/command-call/watch/start", json={"device_id": "DSJ-B1"}).status_code == 200
    busy = client.post("/v1/command-call/watch/start", json={"device_id": "DSJ-B1"})
    assert busy.status_code == 400
    assert "busy" in busy.json()["detail"]

    ccs.use_online_checker(lambda _: False)
    offline = client.post("/v1/command-call/watch/start", json={"device_id": "DSJ-OFF"})
    assert offline.status_code == 400
    assert "offline" in offline.json()["detail"]


def test_defect_upgrade_ended_watch_rejected(session_stack):
    ccs, _ = session_stack
    _prime_ready_room(ccs, "DSJ-DEAD")
    w = ccs.start_watch("DSJ-DEAD")
    ccs.end_watch(w["call_id"])
    with pytest.raises(ValueError, match="not an active watch"):
        ccs.upgrade_watch_to_call(w["call_id"])


def test_defect_end_watch_after_upgrade_is_noop(session_stack):
    """升级后 end_watch 不应拆掉已是指挥连线的会话。"""
    ccs, _ = session_stack
    _prime_ready_room(ccs, "DSJ-NOOP")
    w = ccs.start_watch("DSJ-NOOP")
    ccs.poll_device("DSJ-NOOP")
    ccs.upgrade_watch_to_call(w["call_id"])
    ccs.end_watch(w["call_id"])
    assert ccs.get_call(w["call_id"])["status"] == "in_call"
    assert ccs.get_call(w["call_id"])["kind"] == "call"
