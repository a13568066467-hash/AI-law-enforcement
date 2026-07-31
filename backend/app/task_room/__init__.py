"""Company task rooms (multi-device + multi-seat TRTC)."""

from app.task_room import service as session
from app.task_room.router import router

__all__ = ["router", "session"]
