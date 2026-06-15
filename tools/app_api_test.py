#!/usr/bin/env python3
"""后端 API 冒烟测试（可选，需 backend 运行中）"""
from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8000"


def post(path: str, data: dict, token: str | None = None) -> tuple[int, dict]:
    body = json.dumps(data).encode()
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(BASE + path, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=5) as resp:
        return resp.status, json.loads(resp.read().decode())


def main() -> int:
    failures: list[str] = []
    try:
        with urllib.request.urlopen(BASE + "/health", timeout=3) as resp:
            if resp.status != 200:
                failures.append("health")
            else:
                print("  PASS health")
    except Exception as e:
        print(f"  SKIP backend offline: {e}")
        return 0

    try:
        code, data = post("/auth/login", {"phone": "13800000000", "password": "demo"})
        token = data.get("token", "")
        if code == 200 and token:
            print("  PASS login")
        else:
            failures.append("login")
    except Exception as e:
        failures.append(f"login:{e}")

    if not failures:
        try:
            code, data = post(
                "/v1/chat",
                {"session_id": "test", "device_id": "dev", "text": "开始录像", "state": {"ble_state": 0}},
                token,
            )
            if code == 200 and data.get("intent") == "start_recording":
                print("  PASS chat")
            else:
                failures.append("chat")
        except Exception as e:
            failures.append(f"chat:{e}")

    print(f"\n{'OK' if not failures else 'FAILED'}: {len(failures)} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
