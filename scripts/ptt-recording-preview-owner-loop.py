#!/usr/bin/env python3
"""Feedback loop: recording + HTTP preview must not steal PTT from AI.

Symptom: while recording (often with dashboard preview/watch), holding PTT
has no AI reaction because isStreaming() includes previewActive and routes
F6 to VIDEO_STREAM talk (silent capture, not Realtime AI).

Exit 0 = fixed policy; exit 1 = buggy (preview steals AI).
"""
from __future__ import annotations


def ptt_owner_buggy(*, command_call: bool, is_streaming_incl_preview: bool) -> str:
    """Mirrors current CommandCallIntercomPolicy + VideoStreamManager.isStreaming()."""
    if command_call:
        return "COMMAND_CALL"
    if is_streaming_incl_preview:
        return "VIDEO_STREAM"
    return "AI_OR_LIGHT"


def ptt_owner_fixed(*, command_call: bool, encoded_stream: bool) -> str:
    """Only real GB28181/WebRTC encoded stream may own PTT talk; preview stays AI."""
    if command_call:
        return "COMMAND_CALL"
    if encoded_stream:
        return "VIDEO_STREAM"
    return "AI_OR_LIGHT"


def main() -> int:
    # User scenario: local recording + HTTP preview / 监看 JPEG, no command call
    recording_with_preview = dict(
        command_call=False,
        is_streaming_incl_preview=True,  # previewActive=True
        encoded_stream=False,  # currentMode=NONE
    )

    buggy = ptt_owner_buggy(
        command_call=recording_with_preview["command_call"],
        is_streaming_incl_preview=recording_with_preview["is_streaming_incl_preview"],
    )
    fixed = ptt_owner_fixed(
        command_call=recording_with_preview["command_call"],
        encoded_stream=recording_with_preview["encoded_stream"],
    )

    print(f"recording+preview buggy_owner={buggy} fixed_owner={fixed}")

    # Red on bug: preview steals AI
    if buggy != "VIDEO_STREAM":
        print("unexpected: buggy path should steal AI")
        return 1
    # Green when fixed: AI kept
    if fixed != "AI_OR_LIGHT":
        print("FAIL: fixed path must keep AI during preview-only recording")
        return 1

    # Encoded stream still steals (intentional)
    assert ptt_owner_fixed(command_call=False, encoded_stream=True) == "VIDEO_STREAM"
    assert ptt_owner_fixed(command_call=True, encoded_stream=True) == "COMMAND_CALL"
    print("OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
