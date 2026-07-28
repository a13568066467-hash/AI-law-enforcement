"""Recorder HTTP routes."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException

from app.recorders import repository as recorder_db
from app.recorders.schemas import RecorderFaultReq

router = APIRouter(tags=["recorders"])


@router.get("/v1/recorders/{device_id}")
def get_recorder_device(device_id: str):
    """查询执法仪台账。"""
    rec = recorder_db.ensure_recorder(device_id.strip())
    return recorder_db.recorder_to_dict(rec)


@router.patch("/v1/recorders/{device_id}/fault")
def patch_recorder_fault(device_id: str, req: RecorderFaultReq):
    """标记/解除执法仪故障（运维/管控台）。"""
    recorder_db.ensure_recorder(device_id.strip())
    row = recorder_db.set_faulty(
        device_id.strip(),
        is_faulty=req.is_faulty,
        fault_note=req.fault_note,
    )
    if row is None:
        raise HTTPException(404, "执法仪不存在")
    return recorder_db.recorder_to_dict(row)
