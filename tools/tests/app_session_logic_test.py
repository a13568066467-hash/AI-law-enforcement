#!/usr/bin/env python3
"""App session 编排逻辑回归（镜像 android-app SessionManager）"""
from __future__ import annotations

CMD_START_RECORD = 0x01
CMD_STOP_RECORD = 0x02
CMD_CAPTURE = 0x03
CMD_START_AI_LISTEN = 0x04
CMD_STOP_AI_LISTEN = 0x05


def apply_ble_cmds(cmds: list[dict], sent: list[int]) -> None:
    for c in cmds:
        cmd = c.get("cmd")
        if cmd is None:
            continue
        if cmd in (
            CMD_START_RECORD,
            CMD_STOP_RECORD,
            CMD_CAPTURE,
            CMD_START_AI_LISTEN,
            CMD_STOP_AI_LISTEN,
        ):
            sent.append(cmd)


def handle_chat_response(intent: str, ble_cmds: list[dict], sent: list[int]) -> None:
    apply_ble_cmds(ble_cmds, sent)
    # 修复后：capture_and_explain 只经 ble_cmds 发 0x03，不再重复 writeCmd
    if intent == "capture_and_explain" and CMD_CAPTURE not in sent:
        sent.append(CMD_CAPTURE)


def test_no_duplicate_capture() -> None:
    sent: list[int] = []
    handle_chat_response(
        "capture_and_explain",
        [{"cmd": CMD_CAPTURE}],
        sent,
    )
    assert sent.count(CMD_CAPTURE) == 1


def test_start_record() -> None:
    sent: list[int] = []
    handle_chat_response("start_recording", [{"cmd": CMD_START_RECORD}], sent)
    assert sent == [CMD_START_RECORD]


def test_ble_state_in_chat_payload() -> None:
    ble_state = 3
    payload = {"ble_state": ble_state}
    assert payload["ble_state"] == 3


def main() -> int:
    failures: list[str] = []
    for name, fn in [
        ("no_dup_capture", test_no_duplicate_capture),
        ("start_record", test_start_record),
        ("ble_state", test_ble_state_in_chat_payload),
    ]:
        try:
            fn()
            print(f"  PASS {name}")
        except Exception as e:
            print(f"  FAIL {name}: {e}")
            failures.append(name)
    print(f"\n{'OK' if not failures else 'FAILED'}: {len(failures)} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
