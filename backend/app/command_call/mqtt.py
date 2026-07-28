"""指挥连线 MQTT 发布接缝（可注入 Fake，测试不连真 broker）。"""
from __future__ import annotations

import logging
from typing import Any, Protocol

_log = logging.getLogger(__name__)


class CommandCallMqttPublisher(Protocol):
    def publish_start(self, device_id: str, payload: dict[str, Any]) -> None: ...

    def publish_end(self, device_id: str, payload: dict[str, Any]) -> None: ...


class FakeCommandCallMqttPublisher:
    """内存记录开始/结束连线信令，供契约测试断言。"""

    def __init__(self) -> None:
        self.starts: list[dict[str, Any]] = []
        self.ends: list[dict[str, Any]] = []

    def publish_start(self, device_id: str, payload: dict[str, Any]) -> None:
        self.starts.append({"device_id": device_id, "payload": dict(payload)})

    def publish_end(self, device_id: str, payload: dict[str, Any]) -> None:
        self.ends.append({"device_id": device_id, "payload": dict(payload)})


class LoggingCommandCallMqttPublisher:
    """
    默认发布器：记录连线信令（真 broker 接线留给后续 Issue）。
    HTTP poll 仍可送达开始/结束；日志便于联调核对载荷。
    """

    def publish_start(self, device_id: str, payload: dict[str, Any]) -> None:
        _log.info(
            "command_call MQTT start device_id=%s call_id=%s",
            device_id,
            payload.get("call_id"),
        )

    def publish_end(self, device_id: str, payload: dict[str, Any]) -> None:
        _log.info(
            "command_call MQTT end device_id=%s call_id=%s",
            device_id,
            payload.get("call_id"),
        )


# 兼容旧名
NullCommandCallMqttPublisher = LoggingCommandCallMqttPublisher
