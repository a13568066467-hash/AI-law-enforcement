#!/usr/bin/env python3
"""Feedback loop: orphaned mic after Realtime disconnect blocks next PTT press.

Simulates PttCaptureLifecycle + the pre-fix controller bug sequence.
Exit 0 = recovery path works; exit 1 = user symptom (press does nothing).
"""
from __future__ import annotations


def on_release(phase: str) -> tuple[bool, bool]:
    # stop_capture, try_commit — mirrors PttCaptureLifecycle.onRelease
    if phase == "LISTENING":
        return True, True
    if phase == "CONNECTING":
        return True, False  # FIXED (bug was stop_capture=False)
    return False, False


def must_stop_on_disconnect(pressed: bool, phase: str) -> bool:
    return pressed and phase in ("LISTENING", "CONNECTING")


def can_begin(pressed: bool, capturing: bool) -> bool:
    return pressed and not capturing


def should_restart(pressed: bool, capturing: bool) -> bool:
    return pressed and capturing


def simulate_fixed() -> bool:
    capturing = True
    phase = "LISTENING"
    pressed = True

    # mid-listen disconnect
    if must_stop_on_disconnect(pressed, phase):
        capturing = False
    phase = "CONNECTING"

    # release while CONNECTING
    stop, _ = on_release(phase)
    if stop:
        capturing = False
    phase = "IDLE"
    pressed = False

    # next press
    pressed = True
    if should_restart(pressed, capturing):
        capturing = False
    return can_begin(pressed, capturing)


def simulate_buggy() -> bool:
    """Pre-fix: disconnect/release leave capturing=True → next press silent."""
    capturing = True
    phase = "LISTENING"
    pressed = True

    # bug: disconnect only flips phase
    phase = "CONNECTING"
    # bug: CONNECTING release does not stop capture
    phase = "IDLE"
    pressed = False

    pressed = True
    # old beginCapture: if capturing: return  → cannot begin
    return can_begin(pressed, capturing)


def main() -> int:
    assert simulate_buggy() is False, "buggy path should block beginCapture"
    ok = simulate_fixed()
    print(f"buggy_blocks={not simulate_buggy()} fixed_can_begin={ok}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
