#!/usr/bin/env python3
"""App BLE 图像拼包逻辑回归（镜像 apptext/services/ble.uts）"""
from __future__ import annotations

import base64
import struct
from dataclasses import dataclass, field


def uuid_suffix(uuid: str) -> str:
    u = uuid.upper().replace("-", "")
    if len(u) >= 8:
        return u[4:8]
    return u


def char_id_matches(candidate: str, expected_suffix: str) -> bool:
    return uuid_suffix(candidate) == expected_suffix.upper()


@dataclass
class ImageAssembler:
    pending_size: int = 0
    buffers: dict[int, bytearray] = field(default_factory=dict)
    totals: dict[int, int] = field(default_factory=dict)
    received: dict[int, set[int]] = field(default_factory=dict)
    completed: list[tuple[bytes, int]] = field(default_factory=list)

    def reset(self) -> None:
        self.pending_size = 0
        self.buffers.clear()
        self.totals.clear()
        self.received.clear()

    def on_cmd_notify(self, payload: bytes) -> None:
        if not payload:
            return
        evt = payload[0]
        if evt == 0x83:
            self.reset()
            if len(payload) >= 5:
                self.pending_size = struct.unpack(">I", payload[1:5])[0]

    def on_image_chunk(self, payload: bytes) -> bool:
        if len(payload) < 6:
            return False
        msg_id, seq, total = struct.unpack(">HHH", payload[:6])
        data = payload[6:]
        if msg_id not in self.totals:
            self.totals[msg_id] = total
            self.buffers[msg_id] = bytearray(total * 512)
            self.received[msg_id] = set()
        self.buffers[msg_id][seq * 512 : seq * 512 + len(data)] = data
        self.received[msg_id].add(seq)
        if len(self.received[msg_id]) < total:
            return False
        out_len = self.pending_size if self.pending_size > 0 else len(self.buffers[msg_id])
        blob = bytes(self.buffers[msg_id][:out_len])
        self.completed.append((blob, out_len))
        del self.buffers[msg_id]
        del self.totals[msg_id]
        del self.received[msg_id]
        return True


def test_uuid_suffix() -> None:
    assert uuid_suffix("0000A006-0000-1000-8000-00805F9B34FB") == "A006"
    assert uuid_suffix("A006") == "A006"
    assert char_id_matches("0000A003-0000-1000-8000-00805F9B34FB", "A003")


def test_image_assembly() -> None:
    asm = ImageAssembler()
    size = 1000
    asm.on_cmd_notify(bytes([0x83]) + struct.pack(">I", size))
    total = (size + 511) // 512
    msg_id = 1
    offset = 0
    for seq in range(total):
        chunk_len = min(512, size - offset)
        hdr = struct.pack(">HHH", msg_id, seq, total)
        asm.on_image_chunk(hdr + b"X" * chunk_len)
        offset += chunk_len
    assert len(asm.completed) == 1
    blob, out_len = asm.completed[0]
    assert out_len == size
    assert len(blob) == size
    b64 = base64.b64encode(blob).decode()
    assert len(b64) > 0


def main() -> int:
    failures: list[str] = []
    for name, fn in [("uuid", test_uuid_suffix), ("image", test_image_assembly)]:
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
