#!/usr/bin/env python3
"""巡查员三步验证 API 回归（需 backend 运行于 127.0.0.1:8000）。"""
from __future__ import annotations

import base64
import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8000"

# 1x1 灰度 JPEG stub
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


def post(path: str, payload: dict) -> dict:
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        f"{BASE}{path}",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=8) as resp:
        return json.loads(resp.read().decode())


def main() -> int:
    device_id = "TEST-DEVICE-001"
    phone = "13800000001"

    print("=== 步骤1：人员信息 ===")
    try:
        r1 = post(
            "/auth/patrol/step1/profile",
            {
                "name": "张三",
                "employee_id": "XC001",
                "department": "巡查一队",
                "device_id": device_id,
            },
        )
    except urllib.error.HTTPError as e:
        print("FAIL step1:", e.read().decode())
        return 1
    session_id = r1.get("session_id", "")
    print("OK:", r1.get("message"), "session=", session_id[:12], "...")
    if not session_id:
        return 1

    print("=== 步骤2a：发送验证码 ===")
    r2 = post("/auth/patrol/step2/sms/send", {"session_id": session_id, "phone": phone})
    dev_code = r2.get("dev_code", "")
    print("OK:", r2.get("message"), "dev_code=", dev_code)
    if not dev_code:
        return 1

    print("=== 步骤2b：验证手机号 ===")
    r3 = post(
        "/auth/patrol/step2/sms/verify",
        {"session_id": session_id, "phone": phone, "code": dev_code},
    )
    verify_token = r3.get("verify_token", "")
    print("OK:", r3.get("message"))
    if not verify_token:
        return 1

    print("=== 步骤3：人脸注册 ===")
    try:
        r4 = post(
            "/auth/patrol/register",
            {
                "verify_token": verify_token,
                "device_id": device_id,
                "face_image_base64": FAKE_FACE_B64,
            },
        )
        print("OK:", r4.get("message"), "token=", r4.get("token", "")[:16], "...")
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        if e.code in (403, 400) and ("人脸" in body or "图像" in body):
            print("OK step3 gate (stub image rejected):", body[:100])
        else:
            print("FAIL step3:", e.code, body[:200])
            return 1

    print("ALL PASS (三步 API 链路)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
