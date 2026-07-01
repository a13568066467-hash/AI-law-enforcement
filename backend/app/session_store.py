"""内存会话（V1）；量产换 Redis/DB。"""

from __future__ import annotations



from dataclasses import dataclass, field

from datetime import datetime, timezone



MAX_HISTORY_LEN = 40





@dataclass

class SessionData:

    last_explanation: str = ""

    last_image_id: str = ""

    last_vision_at: datetime | None = None

    history: list[dict[str, str]] = field(default_factory=list)

    expert_history: list[dict[str, str]] = field(default_factory=list)





_STORE: dict[str, SessionData] = {}





def get_session(session_id: str) -> SessionData:

    if session_id not in _STORE:

        _STORE[session_id] = SessionData()

    return _STORE[session_id]





def set_vision_result(session_id: str, explanation: str, image_id: str = "") -> SessionData:

    s = get_session(session_id)

    s.last_explanation = explanation

    s.last_image_id = image_id or f"img-{int(datetime.now(timezone.utc).timestamp())}"

    s.last_vision_at = datetime.now(timezone.utc)

    return s





def trim_history(session: SessionData, max_len: int = MAX_HISTORY_LEN) -> None:

    if len(session.history) > max_len:

        session.history = session.history[-max_len:]


