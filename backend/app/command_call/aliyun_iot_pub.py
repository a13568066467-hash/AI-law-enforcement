"""阿里云 IoT 云端 Pub（指挥连线下行 Topic）。"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import os
import time
import uuid
from typing import Any
from urllib.parse import quote

import httpx

_log = logging.getLogger(__name__)


def _percent_encode(value: str) -> str:
    return quote(str(value), safe="~")


def _sign(secret: str, string_to_sign: str) -> str:
    dig = hmac.new(
        (secret + "&").encode("utf-8"),
        string_to_sign.encode("utf-8"),
        hashlib.sha1,
    ).digest()
    return base64.b64encode(dig).decode("utf-8")


class AliyunIotCommandCallMqttPublisher:
    """
    通过阿里云 IoT OpenAPI `Pub` 向设备 Topic 下发信令。
    未配置完整凭证时不要构造本类（工厂返回 Logging）。
    """

    def __init__(
        self,
        *,
        access_key_id: str,
        access_key_secret: str,
        product_key: str,
        region_id: str = "cn-shanghai",
        iot_instance_id: str = "",
        timeout_s: float = 5.0,
    ) -> None:
        self.access_key_id = access_key_id
        self.access_key_secret = access_key_secret
        self.product_key = product_key
        self.region_id = region_id
        self.iot_instance_id = iot_instance_id
        self.timeout_s = timeout_s
        self.endpoint = f"https://iot.{region_id}.aliyuncs.com/"

    def _device_name(self, device_id: str) -> str:
        # 默认 device_name == device_id；可用 IOT_DEVICE_NAME_PREFIX 加前缀
        prefix = os.getenv("IOT_DEVICE_NAME_PREFIX", "").strip()
        return f"{prefix}{device_id}" if prefix else device_id

    def _topic(self, device_id: str, which: str) -> str:
        dn = self._device_name(device_id)
        suffix = "start" if which == "start" else "end"
        return f"/sys/{self.product_key}/{dn}/thing/service/command_call/{suffix}"

    def publish_start(self, device_id: str, payload: dict[str, Any]) -> None:
        self._pub(device_id, "start", payload)

    def publish_end(self, device_id: str, payload: dict[str, Any]) -> None:
        self._pub(device_id, "end", payload)

    def _pub(self, device_id: str, which: str, payload: dict[str, Any]) -> None:
        topic = self._topic(device_id, which)
        message = base64.b64encode(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        ).decode("ascii")
        params: dict[str, str] = {
            "Format": "JSON",
            "Version": "2018-01-20",
            "AccessKeyId": self.access_key_id,
            "SignatureMethod": "HMAC-SHA1",
            "Timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "SignatureVersion": "1.0",
            "SignatureNonce": uuid.uuid4().hex,
            "Action": "Pub",
            "RegionId": self.region_id,
            "ProductKey": self.product_key,
            "TopicFullName": topic,
            "MessageContent": message,
            "Qos": "1",
        }
        if self.iot_instance_id:
            params["IotInstanceId"] = self.iot_instance_id
        sorted_keys = sorted(params.keys())
        canonical = "&".join(
            f"{_percent_encode(k)}={_percent_encode(params[k])}" for k in sorted_keys
        )
        string_to_sign = f"POST&%2F&{_percent_encode(canonical)}"
        params["Signature"] = _sign(self.access_key_secret, string_to_sign)
        try:
            with httpx.Client(timeout=self.timeout_s) as client:
                resp = client.post(self.endpoint, data=params)
            if resp.status_code >= 400:
                _log.warning(
                    "aliyun iot pub http=%s topic=%s body=%s",
                    resp.status_code,
                    topic,
                    resp.text[:300],
                )
                return
            body = resp.json() if resp.content else {}
            if isinstance(body, dict) and body.get("Success") is False:
                _log.warning("aliyun iot pub failed topic=%s body=%s", topic, body)
            else:
                _log.info(
                    "aliyun iot pub ok topic=%s call_id=%s",
                    topic,
                    payload.get("call_id") or payload.get("room_id"),
                )
        except Exception as exc:  # noqa: BLE001
            _log.warning("aliyun iot pub error topic=%s: %s", topic, exc)


def create_mqtt_publisher_from_env():
    """有完整 IoT 云端凭证则用 Aliyun Pub，否则 Logging。"""
    from app.command_call.mqtt import LoggingCommandCallMqttPublisher

    ak = os.getenv("IOT_ACCESS_KEY_ID", "").strip()
    sk = os.getenv("IOT_ACCESS_KEY_SECRET", "").strip()
    pk = os.getenv("IOT_PRODUCT_KEY", "").strip()
    if not (ak and sk and pk):
        return LoggingCommandCallMqttPublisher()
    region = os.getenv("IOT_REGION_ID", "cn-shanghai").strip() or "cn-shanghai"
    instance = os.getenv("IOT_INSTANCE_ID", "").strip()
    _log.info("command_call MQTT publisher=AliyunIot product_key=%s region=%s", pk, region)
    return AliyunIotCommandCallMqttPublisher(
        access_key_id=ak,
        access_key_secret=sk,
        product_key=pk,
        region_id=region,
        iot_instance_id=instance,
    )
