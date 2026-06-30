"""「我的」注册流程 API 冒烟测试（需 MySQL 与 backend/.env）。"""
from __future__ import annotations

import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import officer_db
from app.patrol_verify import (
    complete_profile_org,
    send_sms_code,
    verify_profile,
    verify_sms_code,
)


def test_register_flow_writes_id_card_and_org() -> None:
    officer_db.init_db()
    eid = officer_db.generate_employee_id()
    assert len(eid) == 6 and eid.isdigit()

    ok, msg, sid = verify_profile(
        name="菜单测试",
        employee_id=eid,
        department="待完善",
        device_id="DSJ-menuflow01",
        id_card="320102198803031234",
    )
    assert ok, msg
    assert sid

    row = officer_db.get_by_employee_id(eid)
    assert row is not None
    assert row.id_card == "320102198803031234"

    phone = f"131{random.randint(10000000, 99999999)}"
    ok2, _, code = send_sms_code(sid, phone)
    assert ok2, code
    ok3, _, token = verify_sms_code(sid, phone, code or "")
    assert ok3 and token

    ok4, msg4 = complete_profile_org(
        sid,
        company="测试公司",
        department="巡查部",
        position="巡查员",
    )
    assert ok4, msg4

    row2 = officer_db.get_by_employee_id(eid)
    assert row2 is not None
    assert row2.company == "测试公司"
    assert row2.department == "巡查部"
    assert row2.position == "巡查员"
    assert row2.status == officer_db.STATUS_PHONE

    # 清理测试数据
    with officer_db._conn() as conn:  # noqa: SLF001
        officer_db._execute(conn, "DELETE FROM officers WHERE employee_id = ?", (eid,))  # noqa: SLF001


if __name__ == "__main__":
    test_register_flow_writes_id_card_and_org()
    print("OK: me menu register flow")
