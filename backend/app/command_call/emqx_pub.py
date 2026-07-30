"""EMQX Cloud HTTP Publish（指挥连线下行）。"""
from __future__ import annotations

import base64
import json
import logging
import os
from typing import Any

import httpx

_log = logging.getLogger(__name__)


class EmqxHttpCommandCallMqttPublisher:
    """
    通过 EMQX Cloud REST `POST /api/v5/publish` 下发信令。
    Basic 认证：App ID / App Secret（部署 API Key）。
    """

    def __init__(
        self,
        *,
        api_base: str,
        api_key: str,
        api_secret: str,
        topic_prefix: str = "aifieldcam/command_call",
        timeout_s: float = 5.0,
    ) -> None:
        self.api_base = api_base.rstrip("/")
        self.api_key = api_key
        self.api_secret = api_secret
        self.topic_prefix = topic_prefix.strip().strip("/") or "aifieldcam/command_call"
        self.timeout_s = timeout_s
        token = base64.b64encode(f"{api_key}:{api_secret}".encode("utf-8")).decode("ascii")
        self._auth_header = f"Basic {token}"

    def _topic(self, device_id: str, which: str) -> str:
        did = (device_id or "").strip()
        suffix = "start" if which == "start" else "end"
        return f"{self.topic_prefix}/{did}/{suffix}"

    def publish_start(self, device_id: str, payload: dict[str, Any]) -> None:
        self._pub(device_id, "start", payload)

    def publish_end(self, device_id: str, payload: dict[str, Any]) -> None:
        self._pub(device_id, "end", payload)

    def _pub(self, device_id: str, which: str, payload: dict[str, Any]) -> None:
        topic = self._topic(device_id, which)
        body = {
            "topic": topic,
            "qos": 1,
            "payload": json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
            "retain": False,
        }
        url = f"{self.api_base}/publish"
        try:
            with httpx.Client(timeout=self.timeout_s) as client:
                resp = client.post(
                    url,
                    headers={
                        "Authorization": self._auth_header,
                        "Content-Type": "application/json",
                    },
                    json=body,
                )
            if resp.status_code >= 400:
                _log.warning(
                    "emqx publish http=%s topic=%s body=%s",
                    resp.status_code,
                    topic,
                    resp.text[:300],
                )
                return
            _log.info(
                "emqx publish ok topic=%s call_id=%s",
                topic,
                payload.get("call_id") or payload.get("room_id"),
            )
        except Exception as exc:  # noqa: BLE001
            _log.warning("emqx publish error topic=%s: %s", topic, exc)


def create_emqx_publisher_from_env() -> EmqxHttpCommandCallMqttPublisher | None:
    api_base = os.getenv("EMQX_API_BASE", "").strip()
    api_key = os.getenv("EMQX_API_KEY", "").strip()
    api_secret = os.getenv("EMQX_API_SECRET", "").strip()
    if not (api_base and api_key and api_secret):
        return None
    prefix = os.getenv("MQTT_TOPIC_PREFIX", "aifieldcam/command_call").strip()
    _log.info("command_call MQTT publisher=EMQX api_base=%s", api_base)
    return EmqxHttpCommandCallMqttPublisher(
        api_base=api_base,
        api_key=api_key,
        api_secret=api_secret,
        topic_prefix=prefix,
    )
