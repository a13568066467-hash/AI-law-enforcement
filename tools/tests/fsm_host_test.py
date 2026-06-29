#!/usr/bin/env python3
"""
FSM 逻辑主机侧回归（无需 ESP-IDF）
运行: python tools/tests/fsm_host_test.py
"""
from __future__ import annotations

from enum import IntEnum
from dataclasses import dataclass


class S(IntEnum):
    IDLE = 0
    AI = 1
    CAPTURE = 2
    RECORD = 3


class E(IntEnum):
    PWR_LONG = 0
    AI_DBL = 2
    V_REC_START = 3
    V_REC_STOP = 4
    V_CAP = 5
    GLASSES = 6
    LOW_BAT = 7
    SHUTTER_LONG = 8
    BLE_REC_START = 0x01
    BLE_REC_STOP = 0x02
    BLE_CAP = 0x03
    BLE_AI_ON = 0x04
    BLE_AI_OFF = 0x05


@dataclass
class Fsm:
    state: S = S.IDLE
    powered: bool = True
    cap_return: S = S.IDLE
    stub_instant_capture: bool = True

    def capture_begin(self) -> bool:
        if not self.powered or self.state in (S.RECORD, S.CAPTURE):
            return False
        self.cap_return = S.AI if self.state == S.AI else S.IDLE
        self.state = S.CAPTURE
        if self.stub_instant_capture:
            self.capture_done()
        return True

    def capture_done(self) -> None:
        if self.state != S.CAPTURE:
            return
        self.state = self.cap_return

    def capture_abort(self) -> None:
        if self.state == S.CAPTURE:
            self.capture_done()

    def start_record(self) -> bool:
        if not self.powered or self.state == S.CAPTURE:
            return False
        if self.state == S.RECORD:
            return True
        if self.state == S.AI:
            self.state = S.IDLE  # leave_ai
        self.state = S.RECORD
        return True

    def stop_record(self) -> bool:
        if self.state != S.RECORD:
            return False
        self.state = S.IDLE
        return True

    def start_ai(self) -> bool:
        if not self.powered or self.state in (S.RECORD, S.CAPTURE):
            return False
        if self.state == S.AI:
            return True
        self.state = S.AI
        return True

    def stop_ai(self) -> bool:
        if self.state != S.AI:
            return False
        self.state = S.IDLE
        return True

    def force_idle(self) -> None:
        if self.state == S.CAPTURE:
            self.capture_abort()
        if self.state == S.RECORD:
            self.stop_record()
        if self.state == S.AI:
            self.stop_ai()
        self.state = S.IDLE

    def on_ble(self, cmd: int) -> None:
        if cmd == E.BLE_REC_START:
            self.start_record()
        elif cmd == E.BLE_REC_STOP:
            self.stop_record()
        elif cmd == E.BLE_CAP:
            self.capture_begin()
        elif cmd == E.BLE_AI_ON:
            self.start_ai()
        elif cmd == E.BLE_AI_OFF:
            self.stop_ai()

    def on_event(self, evt: E) -> None:
        if evt == E.PWR_LONG:
            self.powered = not self.powered
            if not self.powered:
                self.force_idle()
        elif evt == E.AI_DBL:
            if not self.powered:
                return
            if self.state == S.CAPTURE:
                self.capture_abort()
            elif self.state == S.RECORD:
                pass
            elif self.state == S.AI:
                self.stop_ai()
            else:
                self.start_ai()
        elif evt == E.SHUTTER_LONG:
            if self.powered and self.state == S.RECORD:
                self.stop_record()
        elif evt == E.V_REC_START:
            self.start_record()
        elif evt == E.V_REC_STOP:
            self.stop_record()
        elif evt == E.V_CAP:
            self.capture_begin()
        elif evt in (E.GLASSES, E.LOW_BAT):
            if evt == E.LOW_BAT and self.state == S.RECORD:
                self.stop_record()
            if evt == E.LOW_BAT and self.state == S.AI:
                self.stop_ai()
            self.force_idle()


def check(name: str, cond: bool, failures: list) -> None:
    if cond:
        print(f"  PASS {name}")
    else:
        print(f"  FAIL {name}")
        failures.append(name)


def main() -> int:
    failures: list[str] = []
    f = Fsm()
    print("FSM host test")

    check("init idle", f.state == S.IDLE and f.powered, failures)
    f.on_ble(E.BLE_REC_START)
    check("ble record", f.state == S.RECORD, failures)
    f.on_event(E.AI_DBL)
    check("AI blocked in record", f.state == S.RECORD, failures)
    f.on_ble(E.BLE_REC_STOP)
    check("ble stop", f.state == S.IDLE, failures)

    f.on_event(E.AI_DBL)
    check("AI on", f.state == S.AI, failures)
    f.on_event(E.AI_DBL)
    check("AI off", f.state == S.IDLE, failures)

    f.capture_begin()
    check("capture to idle", f.state == S.IDLE, failures)

    f.on_event(E.AI_DBL)
    f.capture_begin()
    check("capture in AI returns AI", f.state == S.AI, failures)
    f.on_event(E.AI_DBL)

    f.on_event(E.PWR_LONG)
    check("power off", not f.powered and f.state == S.IDLE, failures)
    f.capture_begin()
    check("capture blocked off", f.state == S.IDLE, failures)
    f.on_event(E.PWR_LONG)
    check("power on", f.powered, failures)

    f.on_ble(E.BLE_REC_START)
    f.on_event(E.SHUTTER_LONG)
    check("shutter long stop rec", f.state == S.IDLE, failures)

    f.on_event(E.AI_DBL)
    f.state = S.CAPTURE
    f.cap_return = S.AI
    f.stub_instant_capture = False
    f.on_event(E.AI_DBL)
    check("dbl-tap abort capture", f.state == S.AI, failures)

    f.on_ble(E.BLE_REC_START)
    f.on_event(E.LOW_BAT)
    check("low bat stops record", f.state == S.IDLE, failures)

    f.on_event(E.AI_DBL)
    f.on_event(E.GLASSES)
    check("glasses off stops AI", f.state == S.IDLE, failures)

    f = Fsm()
    f.on_ble(E.BLE_REC_START)
    check("capture blocked in record", not f.capture_begin() and f.state == S.RECORD, failures)

    f = Fsm()
    f.powered = False
    check("capture blocked powered off", not f.capture_begin(), failures)

    print(f"\n{'OK' if not failures else 'FAILED'}: {len(failures)} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
