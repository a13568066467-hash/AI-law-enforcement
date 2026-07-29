"""Render architecture .mmd files to PNG via mermaid.ink."""
from __future__ import annotations

import base64
import json
import urllib.request
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
FILES = [
    "01-系统总体架构",
    "02-后端MVC请求链路",
    "03-业务域职责",
    "04-扫码绑定时序",
]


def pako_deflate(data: bytes) -> bytes:
    compress = zlib.compressobj(
        9, zlib.DEFLATED, 15, 8, zlib.Z_DEFAULT_STRATEGY
    )
    return compress.compress(data) + compress.flush()


def to_mermaid_ink_url(src: str) -> str:
    state = {"code": src, "mermaid": {"theme": "default"}}
    compressed = pako_deflate(json.dumps(state, ensure_ascii=False).encode("utf-8"))
    encoded = base64.urlsafe_b64encode(compressed).decode("ascii").rstrip("=")
    return f"https://mermaid.ink/img/pako:{encoded}?type=png"


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        return resp.read()


def main() -> None:
    for name in FILES:
        mmd_path = ROOT / f"{name}.mmd"
        src = mmd_path.read_text(encoding="utf-8")
        url = to_mermaid_ink_url(src)
        print(f"fetch {name} ...")
        png = fetch(url)
        if not png.startswith(b"\x89PNG"):
            # jpeg fallback?
            if png[:2] == b"\xff\xd8":
                out = ROOT / f"{name}.jpg"
                out.write_bytes(png)
                print(f"wrote {out.name} ({len(png)} bytes jpeg)")
                continue
            raise SystemExit(f"{name}: unexpected payload {png[:40]!r}")
        out = ROOT / f"{name}.png"
        out.write_bytes(png)
        print(f"wrote {out.name} ({len(png)} bytes)")


if __name__ == "__main__":
    main()
