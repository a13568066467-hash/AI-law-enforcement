"""公司任务房验收：多人同房 / 跨公司拒绝 / 设备互斥 / 解绑移出 / 点选监看。"""
from __future__ import annotations

import os
import secrets
import sys
import tempfile
from pathlib import Path

import pytest

BACKEND = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND))

os.environ["OFFICER_DB_DRIVER"] = "sqlite"
_db_file = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
_db_file.close()
os.environ["OFFICER_DB_PATH"] = _db_file.name

from app.command_call.mqtt import FakeCommandCallMqttPublisher
from app.device_bind import service as device_bind_store
from app.officers import repository as officer_db
from app.recorders import repository as recorder_db
from app.task_room import service as task_room

COMPANY = "赢筑任务房公司"
OTHER_COMPANY = "跨公司拒绝"
DEVICE_A = "DSJ-TR-A"
DEVICE_B = "DSJ-TR-B"
DEVICE_X = "DSJ-TR-X"
EMP_A = "310001"
EMP_B = "310002"
EMP_X = "310003"


@pytest.fixture(autouse=True)
def _force_sqlite(monkeypatch):
    monkeypatch.setenv("OFFICER_DB_DRIVER", "sqlite")
    monkeypatch.setenv("OFFICER_DB_PATH", _db_file.name)


@pytest.fixture(scope="module", autouse=True)
def _init_db():
    os.environ["OFFICER_DB_DRIVER"] = "sqlite"
    os.environ["OFFICER_DB_PATH"] = _db_file.name
    officer_db.init_db()
    yield
    try:
        os.unlink(_db_file.name)
    except OSError:
        pass


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
def task_ready(trtc_env):
    mqtt = FakeCommandCallMqttPublisher()
    task_room.reset()
    task_room.use_mqtt(mqtt)
    task_room.use_online_checker(lambda _: True)
    # 清掉上一用例残留的任务房成员，避免 UNIQUE(device_id) 串测
    from app.db.connection import _conn, _execute
    from app.db.migrations.task_rooms import ensure_schema

    with _conn() as conn:
        ensure_schema(conn)
        _execute(conn, "DELETE FROM command_task_room_devices")
        _execute(conn, "DELETE FROM command_task_room_seats")
        _execute(conn, "DELETE FROM command_task_rooms")
    return task_room, mqtt


def _seed_officer(employee_id: str, *, name: str, company: str = COMPANY) -> None:
    now = officer_db._utc_now()
    with officer_db._conn() as conn:
        officer_db._execute(
            conn,
            """
            INSERT OR REPLACE INTO officers (
                employee_id, name, gender, department, phone, device_id,
                face_vector, status, created_at, updated_at, company, position
            ) VALUES (?, ?, '男', '测试部', ?, ?, NULL, ?, ?, ?, ?, '巡查员')
            """,
            (
                employee_id,
                name,
                f"139{employee_id}0000",
                f"__pool__{employee_id}",
                officer_db.STATUS_ACTIVE,
                now,
                now,
                company,
            ),
        )


def _bind(device_id: str, employee_id: str, *, company: str = COMPANY) -> str:
    _seed_officer(employee_id, name=f"员{employee_id}", company=company)
    device_bind_store.set_recorder_company(device_id, company)
    token = device_bind_store.create_bind_token(device_id)["token"]
    mobile = secrets.token_urlsafe(12)
    officer_db.save_token(mobile, employee_id, f"139{employee_id}0000")
    confirmed = device_bind_store.confirm_bind(device_id, token, mobile)
    assert confirmed["ok"], confirmed
    return confirmed["session_token"]


def test_multi_device_multi_seat_same_trtc_room(task_ready):
    trs, mqtt = task_ready
    _bind(DEVICE_A, EMP_A)
    _bind(DEVICE_B, EMP_B)

    room = trs.create_room(company=COMPANY, title="验收房")
    assert room["trtc_room_id"].startswith("room-task-")
    assert room["company"] == COMPANY

    trs.add_device(room["id"], DEVICE_A)
    trs.add_device(room["id"], DEVICE_B)
    seat1 = trs.join_seat(room["id"], display_name="座席甲", employee_id="s1")
    seat2 = trs.join_seat(room["id"], display_name="座席乙", employee_id="s2")

    public = trs.get_room(room["id"])
    assert set(public["devices"]) == {DEVICE_A, DEVICE_B}
    assert len(public["seats"]) == 2
    assert seat1["room_id"] == room["trtc_room_id"]
    assert seat2["room_id"] == room["trtc_room_id"]
    assert seat1["platform"]["room_id"] == seat2["platform"]["room_id"] == room["trtc_room_id"]
    assert seat1["call_id"].startswith("seat-")
    assert seat1["platform"]["user_id"].startswith("seat-")
    assert seat2["platform"]["user_id"] != seat1["platform"]["user_id"]

    actions = [e["payload"].get("action") for e in mqtt.starts]
    assert actions.count("task_room_join") >= 2
    join_a = trs.poll_device(DEVICE_A)
    assert join_a is not None
    assert join_a["action"] == "task_room_join"
    assert join_a["room_id"] == room["trtc_room_id"]
    assert join_a["user_id"].startswith("device-")


def test_cross_company_device_rejected(task_ready):
    trs, _mqtt = task_ready
    _bind(DEVICE_A, EMP_A, company=COMPANY)
    _bind(DEVICE_X, EMP_X, company=OTHER_COMPANY)

    room = trs.create_room(company=COMPANY, title="本公司房")
    with pytest.raises(ValueError, match="company"):
        trs.add_device(room["id"], DEVICE_X)


def test_device_cannot_join_second_task_room(task_ready):
    trs, _mqtt = task_ready
    _bind(DEVICE_A, EMP_A)

    room_a = trs.create_room(company=COMPANY, title="房A")
    room_b = trs.create_room(company=COMPANY, title="房B")
    trs.add_device(room_a["id"], DEVICE_A)
    with pytest.raises(ValueError, match="already in another"):
        trs.add_device(room_b["id"], DEVICE_A)


def test_unbind_removes_device_and_signals_leave(task_ready):
    trs, mqtt = task_ready
    _bind(DEVICE_A, EMP_A)
    room = trs.create_room(company=COMPANY, title="解绑房")
    trs.add_device(room["id"], DEVICE_A)
    assert DEVICE_A in trs.get_room(room["id"])["devices"]

    device_bind_store.release_bind(DEVICE_A)
    trs.remove_device_on_unbind(DEVICE_A)

    from app.task_room import repository as repo

    assert repo.find_room_id_for_device(DEVICE_A) is None
    assert any(e["payload"].get("action") == "task_room_leave" for e in mqtt.ends)
    leave = trs.poll_device(DEVICE_A)
    assert leave is not None
    assert leave["action"] == "task_room_leave"
    # 无成员空房已关闭
    with pytest.raises(KeyError):
        trs.get_room(room["id"])


def test_watch_via_temp_task_room(task_ready):
    trs, mqtt = task_ready
    _bind(DEVICE_A, EMP_A)

    session = trs.watch_device_via_task_room(DEVICE_A, caller="指挥中心")
    assert session["kind"] == "watch"
    assert session["status"] == "watching"
    assert session["call_id"].startswith("seat-")
    assert session["device_id"] == DEVICE_A
    assert session["platform"]["room_id"].startswith("room-task-")
    assert session["task_room_id"]

    room = trs.get_room(session["task_room_id"])
    assert DEVICE_A in room["devices"]
    assert any(e["payload"].get("action") == "task_room_join" for e in mqtt.starts)

    # 第二座席可同进同一临时房（不再一座席互斥）
    seat2 = trs.join_seat(session["task_room_id"], display_name="旁听座席")
    assert seat2["platform"]["room_id"] == session["platform"]["room_id"]
    assert len(trs.get_room(session["task_room_id"])["seats"]) == 2
