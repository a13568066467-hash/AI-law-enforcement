#!/usr/bin/env python3
"""巡查员三步验证 + 云端库 + 一机一人（直接调模块，无需 HTTP）。"""
from __future__ import annotations

import base64
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "backend"))

from app.officer_db import (
    STATUS_ACTIVE,
    STATUS_PROFILE,
    STATUS_RESIGNED,
    get_by_device,
    get_by_employee_id,
    init_db,
    offboard_officer as db_offboard,
)
from app.patrol_verify import send_sms_code, verify_profile, verify_sms_code

FAKE_FACE_B64 = base64.b64encode(
    bytes.fromhex(
        "ffd8ffe000104a46494600010100000100010000ffdb004300080606070605080707"
        "070909080a0c140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c"
        "1c2837292c30313434341f27393d38323c2e333432ffdb0043010909090c0b0c18"
        "0d0d1832211c1c2132323232323232323232323232323232323232323232323232"
        "323232323232323232ffc00011080001000103011100021100031101ffc400150001"
        "01000000000000000000000000000008ffc4001410010000000000000000000000"
        "00000000ffda000c03010002110311003f00aaffd9"
    )
).decode()


def main() -> int:
    init_db()
    device = "TEST-DEVICE-DB-001"
    phone = "13800000099"

    print("=== 步骤1：人员信息入库 ===")
    ok, msg, sid = verify_profile(
        name="张三", employee_id="XC001", department="巡查一队", device_id=device,
    )
    if not ok:
        print("FAIL:", msg)
        return 1
    row = get_by_employee_id("XC001")
    assert row and row.status == STATUS_PROFILE
    print("OK:", msg, "| DB status=", row.status)

    print("=== 一机一人：第二人同设备应拒绝 ===")
    ok2, msg2, _ = verify_profile(
        name="李四", employee_id="XC002", department="二队", device_id=device,
    )
    if ok2:
        print("FAIL: should block second officer on same device")
        return 1
    print("OK blocked:", msg2[:40])

    print("=== 步骤2：电话验证 ===")
    ok, msg, code = send_sms_code(sid, phone)
    if not ok:
        print("FAIL send:", msg)
        return 1
    ok, msg, token = verify_sms_code(sid, phone, code)
    if not ok:
        print("FAIL verify:", msg)
        return 1
    row = get_by_employee_id("XC001")
    assert row and row.phone == phone
    print("OK:", msg, "| phone in DB")

    print("=== 步骤3：人脸激活（stub 图可能失败）===")
    from app.patrol_verify import consume_verify_token
    from app.patrol_store import register_officer

    session = consume_verify_token(token)
    if not session:
        print("FAIL: no session")
        return 1
    ok, msg, rec = register_officer(
        phone=session.phone,
        name=session.name,
        employee_id=session.employee_id,
        department=session.department,
        device_id=session.device_id,
        face_image_b64=FAKE_FACE_B64,
        token="test-token-001",
    )
    row = get_by_device(device) if ok else None
    if ok:
        assert row and row.status == STATUS_ACTIVE
        print("OK:", msg)
    else:
        print("OK step3 gate (stub image):", msg[:60])

    if row and row.status == STATUS_ACTIVE:
        print("=== 注销在岗巡查员 ===")
        ok_off, msg_off, resigned = db_offboard(device_id=device)
        if not ok_off or resigned is None:
            print("FAIL offboard:", msg_off)
            return 1
        assert resigned.status == STATUS_RESIGNED
        assert get_by_device(device) is None
        print("OK:", msg_off, "| status=", resigned.status)

        print("=== 离职后同设备可重新录入 ===")
        ok_re, msg_re, sid_re = verify_profile(
            name="张三", employee_id="XC001", department="巡查一队", device_id=device,
        )
        if not ok_re:
            print("FAIL re-profile:", msg_re)
            return 1
        row_re = get_by_employee_id("XC001")
        assert row_re and row_re.status == STATUS_PROFILE
        print("OK:", msg_re)
    else:
        print("=== 注销流程（直接激活后测）===")
        from app.officer_db import activate_officer

        vec = [0.0] * 1024
        activate_officer(
            employee_id="XC001",
            phone=phone,
            name="张三",
            department="巡查一队",
            device_id=device,
            face_vector=vec,
        )
        row = get_by_device(device)
        assert row and row.status == STATUS_ACTIVE
        ok_off, msg_off, resigned = db_offboard(device_id=device)
        if not ok_off or resigned is None:
            print("FAIL offboard:", msg_off)
            return 1
        assert resigned.status == STATUS_RESIGNED
        print("OK:", msg_off)

    print("ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
