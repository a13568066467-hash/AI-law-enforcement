"""YOLO 人脸检测（仅负责找脸，识别由 SFace 完成）。"""
from __future__ import annotations

import logging
import os
import threading
import urllib.request
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

_MODELS_DIR = Path(__file__).resolve().parent.parent / "models" / "face"
_DEFAULT_YOLO_URL = (
    "https://github.com/lindevs/yolov8-face/releases/download/1.0.0/"
    "yolov8n-face-lindevs.pt"
)

_yolo_lock = threading.Lock()
_yolo_model: Any | None = None


def yolo_model_path() -> Path:
    custom = os.getenv("PATROL_YOLO_MODEL", "").strip()
    if custom:
        return Path(custom)
    return _MODELS_DIR / "yolov8n-face-lindevs.pt"


def _download_yolo(dest: Path) -> None:
    url = os.getenv("PATROL_YOLO_MODEL_URL", _DEFAULT_YOLO_URL).strip() or _DEFAULT_YOLO_URL
    dest.parent.mkdir(parents=True, exist_ok=True)
    logger.info("下载 YOLO 人脸模型 %s -> %s", url, dest)
    urllib.request.urlretrieve(url, dest)  # noqa: S310


def ensure_yolo_model() -> bool:
    try:
        from ultralytics import YOLO  # noqa: F401
    except (ImportError, OSError) as exc:
        logger.warning(
            "YOLO 不可用（需 pip install ultralytics，且 torch 能正常加载）: %s",
            exc,
        )
        return False
    path = yolo_model_path()
    if not path.is_file():
        try:
            _download_yolo(path)
        except Exception as exc:
            logger.warning("YOLO 人脸模型下载失败: %s", exc)
            return False
    return path.is_file()


def _get_yolo() -> Any:
    global _yolo_model
    if _yolo_model is not None:
        return _yolo_model
    with _yolo_lock:
        if _yolo_model is not None:
            return _yolo_model
        from ultralytics import YOLO

        path = yolo_model_path()
        if not path.is_file():
            raise FileNotFoundError(f"YOLO 模型不存在: {path}")
        _yolo_model = YOLO(str(path))
        logger.info("YOLO 人脸检测已加载: %s", path.name)
        return _yolo_model


def _conf_threshold() -> float:
    raw = os.getenv("PATROL_YOLO_CONF", "0.4").strip()
    try:
        return max(0.1, min(0.95, float(raw)))
    except ValueError:
        return 0.4


def detect_largest_face_bgr(img: Any) -> tuple[int, int, int, int] | None:
    """
    检测最大人脸，返回 (x1, y1, x2, y2) 像素坐标。
    img 为 OpenCV BGR ndarray。
    """
    import numpy as np

    model = _get_yolo()
    results = model.predict(
        source=img,
        conf=_conf_threshold(),
        verbose=False,
        device="cpu",
    )
    if not results:
        return None
    boxes = results[0].boxes
    if boxes is None or len(boxes) == 0:
        return None

    h, w = img.shape[:2]
    best: tuple[int, int, int, int] | None = None
    best_area = 0.0
    xyxy = boxes.xyxy.cpu().numpy()
    confs = boxes.conf.cpu().numpy()
    min_conf = _conf_threshold()
    for i, row in enumerate(xyxy):
        if confs[i] < min_conf:
            continue
        x1, y1, x2, y2 = [int(v) for v in row[:4]]
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(w, x2), min(h, y2)
        area = float(max(0, x2 - x1) * max(0, y2 - y1))
        if area > best_area:
            best_area = area
            best = (x1, y1, x2, y2)
    return best


def align_face_for_sface(img: Any, box: tuple[int, int, int, int]) -> Any:
    """将 YOLO 框裁剪并缩放到 SFace 输入 112×112。"""
    import cv2

    x1, y1, x2, y2 = box
    fw, fh = x2 - x1, y2 - y1
    if fw < 8 or fh < 8:
        raise ValueError("face area too small")
    pad_ratio = float(os.getenv("PATROL_YOLO_FACE_PADDING", "0.25"))
    side = int(max(fw, fh) * (1.0 + pad_ratio))
    cx = (x1 + x2) // 2
    cy = (y1 + y2) // 2
    half = side // 2
    h, w = img.shape[:2]
    tx = max(0, cx - half)
    ty = max(0, cy - half)
    bx = min(w, cx + half)
    by = min(h, cy + half)
    crop = img[ty:by, tx:bx]
    if crop.size == 0:
        raise ValueError("empty crop")
    return cv2.resize(crop, (112, 112), interpolation=cv2.INTER_LINEAR)
