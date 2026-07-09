"""智慧控制平台大屏 — 执法仪汇聚 API。"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from . import officer_db
from . import recorder_db

# 绑定阶段 → 大屏设备状态
PHASE_IDLE = recorder_db.PHASE_IDLE
PHASE_REGISTERING = recorder_db.PHASE_REGISTERING
PHASE_ACTIVE = recorder_db.PHASE_ACTIVE

# 默认分组（无部门信息时）
DEFAULT_GROUP = {"id": 1, "name": "未分组", "code": "DEF", "site": "全部工地"}


def _parse_ts(raw: str | None) -> datetime | None:
    if not raw:
        return None
    s = str(raw).strip()
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%dT%H:%M:%S"):
        try:
            dt = datetime.strptime(s.replace("Z", ""), fmt.replace("Z", ""))
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt
        except ValueError:
            continue
    return None


def _format_last_seen(raw: str | None) -> str:
    dt = _parse_ts(raw)
    if dt is None:
        return "--"
    now = datetime.now(timezone.utc)
    sec = int((now - dt).total_seconds())
    if sec < 60:
        return "刚刚"
    if sec < 3600:
        return f"{sec // 60}分钟前"
    if sec < 86400:
        return f"{sec // 3600}小时前"
    return f"{sec // 86400}天前"


def _is_recently_seen(raw: str | None, minutes: int = 30) -> bool:
    dt = _parse_ts(raw)
    if dt is None:
        return False
    now = datetime.now(timezone.utc)
    return (now - dt).total_seconds() <= minutes * 60


def _derive_status(rec: recorder_db.RecorderRow) -> str:
    """online | recording | offline"""
    if rec.is_faulty:
        return "offline"
    if rec.binding_phase == PHASE_ACTIVE and _is_recently_seen(rec.last_seen_at):
        # 暂无 MQTT 录像状态，在岗且在线视为 online
        return "online"
    if rec.binding_phase == PHASE_REGISTERING:
        return "online"
    if _is_recently_seen(rec.last_seen_at, minutes=10):
        return "online"
    return "offline"


def _stable_battery(device_id: str, status: str) -> int:
    if status == "offline":
        return 0
    h = sum(ord(c) for c in device_id)
    return 35 + (h % 66)


def _stable_gps(device_id: str) -> tuple[float, float]:
    h = sum(ord(c) for c in device_id)
    return round(30.0 + (h % 1000) / 10000, 4), round(104.0 + (h % 1000) / 10000, 4)


def _department_group_id(dept: str) -> int:
    dept = (dept or DEFAULT_GROUP["name"]).strip() or DEFAULT_GROUP["name"]
    return abs(hash(dept)) % 9000 + 1


def list_recorders_with_officers() -> list[dict[str, Any]]:
    """查询全部执法仪并关联巡查员档案。"""
    with officer_db._conn() as conn:
        rows = officer_db._execute(
            conn,
            """
            SELECT
                r.device_id, r.device_name, r.model, r.in_use, r.employee_id,
                r.is_faulty, r.fault_note, r.binding_phase,
                r.bound_at, r.unbound_at, r.last_seen_at, r.remark,
                r.created_at, r.updated_at,
                o.name AS officer_name, o.department, o.company, o.position, o.status AS officer_status
            FROM recorders r
            LEFT JOIN officers o ON r.employee_id = o.employee_id
            ORDER BY r.updated_at DESC, r.device_id ASC
            """,
            (),
        ).fetchall()

    result: list[dict[str, Any]] = []
    for row in rows:
        rec = recorder_db._row_to_recorder(row)
        if rec is None:
            continue
        officer_name = officer_db._row_get(row, "officer_name") or ""
        department = officer_db._row_get(row, "department") or ""
        company = officer_db._row_get(row, "company") or ""
        position = officer_db._row_get(row, "position") or ""
        status = _derive_status(rec)
        lat, lng = _stable_gps(rec.device_id)
        gid = _department_group_id(department or company)
        result.append({
            "id": rec.device_id,
            "name": rec.device_name,
            "model": rec.model,
            "groupId": gid,
            "groupName": department or company or DEFAULT_GROUP["name"],
            "officer": officer_name or (f"工号 {rec.employee_id}" if rec.employee_id else "未绑定"),
            "employeeId": rec.employee_id or None,
            "role": "leader" if "队长" in position or "主管" in position or "负责" in position else "member",
            "status": status,
            "battery": _stable_battery(rec.device_id, status),
            "gpsLat": lat,
            "gpsLng": lng,
            "lastSeen": _format_last_seen(rec.last_seen_at),
            "hasAlert": rec.is_faulty,
            "faultNote": rec.fault_note or None,
            "bindingPhase": rec.binding_phase,
            "bindingPhaseLabel": recorder_db.phase_label(rec.binding_phase),
            "inUse": rec.in_use,
            "department": department,
            "company": company,
            "officerStatus": officer_db._row_get(row, "officer_status"),
        })
    return result


def build_groups(devices: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """按部门聚合设备组。"""
    buckets: dict[int, dict[str, Any]] = {}
    for d in devices:
        gid = int(d["groupId"])
        if gid not in buckets:
            dept = d.get("groupName") or DEFAULT_GROUP["name"]
            buckets[gid] = {
                "id": gid,
                "name": dept,
                "code": f"G{gid:04d}",
                "site": d.get("company") or dept,
                "leaderId": "",
                "leaderName": "",
                "onlineCount": 0,
                "totalCount": 0,
            }
        buckets[gid]["totalCount"] += 1
        if d["status"] != "offline":
            buckets[gid]["onlineCount"] += 1
        if d["role"] == "leader" and d.get("officer"):
            buckets[gid]["leaderName"] = d["officer"]
            buckets[gid]["leaderId"] = d.get("employeeId") or ""
    return sorted(buckets.values(), key=lambda g: g["name"])


def build_alerts(devices: list[dict[str, Any]]) -> list[dict[str, Any]]:
    alerts: list[dict[str, Any]] = []
    aid = 1
    for d in devices:
        if d.get("hasAlert"):
            note = d.get("faultNote") or "设备故障"
            alerts.append({
                "id": aid,
                "deviceId": d["id"],
                "description": f"设备故障：{note}",
                "time": d.get("lastSeen") or "--",
                "critical": True,
            })
            aid += 1
        elif d["status"] == "offline" and d.get("inUse"):
            alerts.append({
                "id": aid,
                "deviceId": d["id"],
                "description": f"{d.get('officer', '绑定人员')} 的设备已离线",
                "time": d.get("lastSeen") or "--",
                "critical": False,
            })
            aid += 1
    return alerts


def build_work_orders(devices: list[dict[str, Any]]) -> list[dict[str, Any]]:
    orders: list[dict[str, Any]] = []
    oid = 1
    for d in devices:
        if d.get("hasAlert"):
            orders.append({
                "id": oid,
                "type": "设备报障",
                "urgent": True,
                "deviceId": d["id"],
                "officer": d.get("officer") or "",
                "groupName": d.get("groupName") or "",
                "description": d.get("faultNote") or "执法仪故障待处理",
                "time": d.get("lastSeen") or "--",
                "status": "pending",
            })
            oid += 1
        elif d["status"] == "offline" and d.get("employeeId"):
            orders.append({
                "id": oid,
                "type": "离线提醒",
                "urgent": False,
                "deviceId": d["id"],
                "officer": d.get("officer") or "",
                "groupName": d.get("groupName") or "",
                "description": "绑定设备长时间未上线",
                "time": d.get("lastSeen") or "--",
                "status": "pending",
            })
            oid += 1
    return orders


def get_dashboard_overview() -> dict[str, Any]:
    devices = list_recorders_with_officers()
    groups = build_groups(devices)
    alerts = build_alerts(devices)
    work_orders = build_work_orders(devices)
    total = len(devices)
    online = sum(1 for d in devices if d["status"] != "offline")
    recording = sum(1 for d in devices if d["status"] == "recording")
    offline = sum(1 for d in devices if d["status"] == "offline")
    alert_count = sum(1 for d in devices if d.get("hasAlert"))
    return {
        "source": "text1/aifieldcam",
        "devices": devices,
        "groups": groups,
        "alerts": alerts,
        "workOrders": work_orders,
        "kpi": {
            "total": total,
            "online": online,
            "recording": recording,
            "offline": offline,
            "alerts": alert_count,
            "onlineRate": round((online / total) * 100) if total else 0,
            "groupCount": len(groups),
        },
    }
