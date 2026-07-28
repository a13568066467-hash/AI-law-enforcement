"""Dashboard HTTP routes."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException

from app.dashboard import service as dashboard_api

router = APIRouter(tags=["dashboard"])


@router.get("/v1/dashboard/overview")
def dashboard_overview():
    """智慧控制平台大屏：汇聚 aifieldcam 执法仪 + 巡查员数据。"""
    try:
        return dashboard_api.get_dashboard_overview()
    except Exception as exc:
        raise HTTPException(500, f"dashboard overview error: {exc}") from exc


@router.get("/v1/dashboard/devices")
def dashboard_devices():
    """执法仪列表（含绑定人员）。"""
    try:
        return {"devices": dashboard_api.list_recorders_with_officers()}
    except Exception as exc:
        raise HTTPException(500, f"dashboard devices error: {exc}") from exc
