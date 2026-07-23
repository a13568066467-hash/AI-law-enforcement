"""现场事件工单：创建与按公司查阅（SQLite 临时库）。"""
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

from app import device_bind_store, field_event_ticket_store, officer_db, recorder_db  # noqa: E402

DEVICE = "DSJ-FET-001"
COMPANY = "赢筑测试公司"
OTHER_COMPANY = "其他公司"
EMP = "200001"


@pytest.fixture(scope="module", autouse=True)
def _init_db():
    officer_db.init_db()
    yield
    try:
        os.unlink(_db_file.name)
    except OSError:
        pass


@pytest.fixture(autouse=True)
def _reset_organizer():
    field_event_ticket_store.use_body_organizer(None)
    yield
    field_event_ticket_store.use_body_organizer(None)


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


def _bind_session() -> str:
    _seed_officer(EMP, name="王五")
    device_bind_store.set_recorder_company(DEVICE, COMPANY)
    token = device_bind_store.create_bind_token(DEVICE)["token"]
    mobile = secrets.token_urlsafe(12)
    officer_db.save_token(mobile, EMP, f"139{EMP}0000")
    confirmed = device_bind_store.confirm_bind(DEVICE, token, mobile)
    assert confirmed["ok"]
    return confirmed["session_token"]


def test_create_rejects_blank_transcript():
    session = _bind_session()
    result = field_event_ticket_store.create_from_session(
        session_token=session,
        transcript="   ",
    )
    assert not result["ok"]
    assert result["status_code"] == 400
    listed = field_event_ticket_store.list_by_company(COMPANY)
    assert listed == []


def test_create_happy_path_lists_for_company_only():
    session = _bind_session()
    field_event_ticket_store.use_body_organizer(lambda t: f"整理：{t}")
    result = field_event_ticket_store.create_from_session(
        session_token=session,
        transcript="脚手架未设防护",
    )
    assert result["ok"]
    ticket = result["ticket"]
    assert ticket["body"] == "整理：脚手架未设防护"
    assert ticket["device_id"] == DEVICE
    assert ticket["employee_id"] == EMP
    assert ticket["officer_name"] == "王五"
    assert ticket["company"] == COMPANY
    assert ticket["status"] == "pending"
    assert ticket["created_at"]

    assert len(field_event_ticket_store.list_by_company(COMPANY)) == 1
    assert field_event_ticket_store.list_by_company(OTHER_COMPANY) == []
    got = field_event_ticket_store.get_by_id(ticket["id"])
    assert got is not None
    assert got["body"] == ticket["body"]


def test_http_create_and_company_scoped_read():
    # main.load_dotenv(override=True) 会冲掉测试里的 sqlite 设置，导入后立即恢复。
    from fastapi.testclient import TestClient

    from app.main import app

    os.environ["OFFICER_DB_DRIVER"] = "sqlite"
    os.environ["OFFICER_DB_PATH"] = _db_file.name

    session = _bind_session()
    field_event_ticket_store.use_body_organizer(lambda t: t)
    client = TestClient(app)

    bad = client.post(
        "/v1/field-event-tickets",
        headers={"Authorization": f"Bearer {session}"},
        json={"transcript": ""},
    )
    assert bad.status_code == 400

    ok = client.post(
        "/v1/field-event-tickets",
        headers={"Authorization": f"Bearer {session}"},
        json={"transcript": "现场发现堆料堵塞消防通道"},
    )
    assert ok.status_code == 200, ok.text
    ticket = ok.json()["ticket"]
    tid = ticket["id"]

    listed = client.get("/v1/field-event-tickets", params={"company": COMPANY})
    assert listed.status_code == 200
    assert any(t["id"] == tid for t in listed.json()["tickets"])

    other = client.get("/v1/field-event-tickets", params={"company": OTHER_COMPANY})
    assert other.status_code == 200
    assert other.json()["tickets"] == []

    detail = client.get(
        f"/v1/field-event-tickets/{tid}",
        params={"company": COMPANY},
    )
    assert detail.status_code == 200
    assert detail.json()["ticket"]["officer_name"] == "王五"

    cross = client.get(
        f"/v1/field-event-tickets/{tid}",
        params={"company": OTHER_COMPANY},
    )
    assert cross.status_code == 404


def test_update_status_company_scoped():
    session = _bind_session()
    created = field_event_ticket_store.create_from_session(
        session_token=session,
        transcript="需要支援",
    )
    assert created["ok"]
    tid = created["ticket"]["id"]

    bad_co = field_event_ticket_store.update_status(
        ticket_id=tid,
        company=OTHER_COMPANY,
        status="in_progress",
    )
    assert not bad_co["ok"]
    assert bad_co["status_code"] == 404

    bad_st = field_event_ticket_store.update_status(
        ticket_id=tid,
        company=COMPANY,
        status="flying",
    )
    assert not bad_st["ok"]
    assert bad_st["status_code"] == 400

    ok = field_event_ticket_store.update_status(
        ticket_id=tid,
        company=COMPANY,
        status="in_progress",
    )
    assert ok["ok"]
    assert ok["ticket"]["status"] == "in_progress"

    closed = field_event_ticket_store.update_status(
        ticket_id=tid,
        company=COMPANY,
        status="closed",
    )
    assert closed["ok"]
    assert closed["ticket"]["status"] == "closed"
