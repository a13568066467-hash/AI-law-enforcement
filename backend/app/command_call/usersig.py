"""腾讯云 TRTC UserSig 签发（SecretKey 仅后端）。"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
import zlib
from typing import Any

_sdk_app_id:1600152450
_secret_key:cf9d61dca9b05f66b9f60e04e1ded392b73343ac29edd952eea59021b4852bea


def reload_config() -> None:
    """从环境变量重载 TRTC 配置（测试可调用）。"""
    global _sdk_app_id, _secret_key
    raw_id = os.getenv("TRTC_SDK_APP_ID", "").strip()
    _secret_key = os.getenv("TRTC_SECRET_KEY", "").strip()
    if raw_id.isdigit() and _secret_key:
        _sdk_app_id = int(raw_id)
    else:
        _sdk_app_id = None
        if not raw_id:
            _secret_key = ""


def trtc_configured() -> bool:
    if _sdk_app_id is None and not _secret_key:
        # 懒加载：进程启动后首次调用时读 env
        reload_config()
    return _sdk_app_id is not None and bool(_secret_key)


def sdk_app_id() -> int:
    if not trtc_configured() or _sdk_app_id is None:
        raise RuntimeError("TRTC not configured: set TRTC_SDK_APP_ID and TRTC_SECRET_KEY")
    return _sdk_app_id


def issue_user_sig(user_id: str, *, expire_seconds: int = 86400) -> str:
    """签发短时 UserSig（TLSSigAPIv2 兼容）。"""
    user_id = (user_id or "").strip()
    if not user_id:
        raise ValueError("user_id required")
    app_id = sdk_app_id()
    return _gen_user_sig(app_id, _secret_key, user_id, expire_seconds)


def _base64_encode_url(data: bytes) -> str:
    encoded = base64.b64encode(data).decode("utf-8")
    return encoded.replace("+", "*").replace("/", "-").replace("=", "_")


def _gen_user_sig(sdkappid: int, key: str, identifier: str, expire: int) -> str:
    curr_time = int(time.time())
    raw_content = (
        f"TLS.identifier:{identifier}\n"
        f"TLS.sdkappid:{sdkappid}\n"
        f"TLS.time:{curr_time}\n"
        f"TLS.expire:{expire}\n"
    )
    sig_field = base64.b64encode(
        hmac.new(key.encode("utf-8"), raw_content.encode("utf-8"), hashlib.sha256).digest()
    ).decode("utf-8")
    doc: dict[str, Any] = {
        "TLS.ver": "2.0",
        "TLS.identifier": str(identifier),
        "TLS.sdkappid": int(sdkappid),
        "TLS.expire": int(expire),
        "TLS.time": int(curr_time),
        "TLS.sig": sig_field,
    }
    compressed = zlib.compress(json.dumps(doc).encode("utf-8"))
    return _base64_encode_url(compressed)


# 模块导入时尝试读一次 env
reload_config()
