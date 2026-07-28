"""Shared Bearer auth helpers and demo login tokens."""
from __future__ import annotations

from fastapi import HTTPException

# 演示账号（V1）
DEMO_USERS = {"13800000000": "demo"}
DEMO_TOKENS: dict[str, str] = {}


def optional_bearer_token(authorization: str | None) -> str:
    """解析 Authorization 头；注销接口允许无 token，仅凭 device_id 操作。"""
    if not authorization or not authorization.startswith("Bearer "):
        return ""
    return authorization[7:].strip()


def require_auth_token(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing token")
    token = authorization[7:].strip()
    from app.patrol.store import is_token_valid

    if token not in DEMO_TOKENS.values() and not is_token_valid(token):
        raise HTTPException(401, "invalid token")
    return token
