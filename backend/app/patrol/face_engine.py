"""人脸特征提取与 1:1 比对（YOLO+SFace / InsightFace / OpenCV / legacy）。"""
from __future__ import annotations

import base64
import io
import logging
import math
import os
import threading
import urllib.request
from pathlib import Path
from typing import Any

from PIL import Image

from app.patrol import face_yolo

logger = logging.getLogger(__name__)

ENGINE_YOLO = "yolo"
ENGINE_INSIGHTFACE = "insightface"
ENGINE_OPENCV = "opencv"
ENGINE_LEGACY = "legacy"
ENGINE_AUTO = "auto"

EMBEDDING_DIM_INSIGHTFACE = 512
EMBEDDING_DIM_OPENCV = 128
EMBEDDING_DIM_LEGACY = 1024

_MODELS_DIR = Path(__file__).resolve().parent.parent / "models" / "face"
_YUNET_URL = (
    "https://github.com/opencv/opencv_zoo/raw/main/models/"
    "face_detection_yunet/face_detection_yunet_2023mar.onnx"
)
_SFACE_URL = (
    "https://github.com/opencv/opencv_zoo/raw/main/models/"
    "face_recognition_sface/face_recognition_sface_2021dec.onnx"
)

_insightface_lock = threading.Lock()
_insightface_app: Any | None = None
_opencv_lock = threading.Lock()
_opencv_detector: Any | None = None
_opencv_recognizer: Any | None = None
_sface_only_recognizer: Any | None = None
_resolved_engine: str | None = None


def _env_engine() -> str:
    return os.getenv("PATROL_FACE_ENGINE", ENGINE_AUTO).strip().lower()


def _can_import_insightface() -> bool:
    try:
        import insightface  # noqa: F401
        import onnxruntime  # noqa: F401
        return True
    except ImportError:
        return False


def _yunet_path() -> Path:
    return _MODELS_DIR / "face_detection_yunet_2023mar.onnx"


def _sface_path() -> Path:
    return _MODELS_DIR / "face_recognition_sface_2021dec.onnx"


def _download_model(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    logger.info("下载人脸模型 %s -> %s", url, dest)
    urllib.request.urlretrieve(url, dest)  # noqa: S310


def _ensure_sface_model() -> bool:
    if _sface_path().is_file():
        return True
    try:
        import cv2  # noqa: F401
    except ImportError:
        return False
    try:
        _download_model(_SFACE_URL, _sface_path())
    except Exception as exc:
        logger.warning("SFace 模型下载失败: %s", exc)
        return False
    return _sface_path().is_file()


def _ensure_yolo_pipeline() -> bool:
    return face_yolo.ensure_yolo_model() and _ensure_sface_model()


def _ensure_opencv_models() -> bool:
    try:
        import cv2  # noqa: F401
    except ImportError:
        return False
    for url, path in ((_YUNET_URL, _yunet_path()), (_SFACE_URL, _sface_path())):
        if not path.is_file():
            try:
                _download_model(url, path)
            except Exception as exc:
                logger.warning("OpenCV 人脸模型下载失败 %s: %s", path.name, exc)
                return False
    return _yunet_path().is_file() and _sface_path().is_file()


def _resolve_engine() -> str:
    global _resolved_engine
    if _resolved_engine is not None:
        return _resolved_engine

    pref = _env_engine()
    if pref == ENGINE_LEGACY:
        _resolved_engine = ENGINE_LEGACY
        return _resolved_engine

    candidates: list[str]
    if pref == ENGINE_YOLO:
        candidates = [ENGINE_YOLO, ENGINE_OPENCV, ENGINE_LEGACY]
    elif pref == ENGINE_INSIGHTFACE:
        candidates = [ENGINE_INSIGHTFACE, ENGINE_YOLO, ENGINE_OPENCV, ENGINE_LEGACY]
    elif pref == ENGINE_OPENCV:
        candidates = [ENGINE_OPENCV, ENGINE_LEGACY]
    elif pref == ENGINE_AUTO:
        candidates = [ENGINE_YOLO, ENGINE_INSIGHTFACE, ENGINE_OPENCV, ENGINE_LEGACY]
    else:
        candidates = [ENGINE_OPENCV, ENGINE_LEGACY]

    for engine in candidates:
        if engine == ENGINE_YOLO and _ensure_yolo_pipeline():
            _resolved_engine = ENGINE_YOLO
            logger.info("人脸引擎: YOLO 检测 + SFace 识别 (128维)")
            return _resolved_engine
        if engine == ENGINE_INSIGHTFACE and _can_import_insightface():
            _resolved_engine = ENGINE_INSIGHTFACE
            logger.info("人脸引擎: InsightFace (ArcFace 512维)")
            return _resolved_engine
        if engine == ENGINE_OPENCV and _ensure_opencv_models():
            _resolved_engine = ENGINE_OPENCV
            logger.info("人脸引擎: OpenCV SFace (128维)")
            return _resolved_engine
        if engine == ENGINE_LEGACY:
            _resolved_engine = ENGINE_LEGACY
            logger.warning("人脸引擎: legacy 32×32（建议安装 insightface 或保留 OpenCV 模型）")
            return _resolved_engine

    _resolved_engine = ENGINE_LEGACY
    return _resolved_engine


def face_engine_name() -> str:
    return _resolve_engine()


def pipeline_label() -> str:
    engine = face_engine_name()
    labels = {
        ENGINE_YOLO: "YOLOv8-face → SFace(128d)",
        ENGINE_INSIGHTFACE: "InsightFace ArcFace(512d)",
        ENGINE_OPENCV: "YuNet → SFace(128d)",
        ENGINE_LEGACY: "legacy 32×32",
    }
    return labels.get(engine, engine)


def default_match_threshold() -> float:
    engine = face_engine_name()
    if engine == ENGINE_INSIGHTFACE:
        return 0.42
    if engine in (ENGINE_OPENCV, ENGINE_YOLO):
        return 0.36
    return 0.55


def match_threshold() -> float:
    default = default_match_threshold()
    raw = os.getenv("PATROL_FACE_MATCH_THRESHOLD", str(default)).strip()
    try:
        value = float(raw)
    except ValueError:
        return default
    engine = face_engine_name()
    if engine == ENGINE_INSIGHTFACE:
        return max(0.25, min(0.75, value))
    if engine in (ENGINE_OPENCV, ENGINE_YOLO):
        return max(0.25, min(0.65, value))
    return max(0.35, min(0.95, value))


def _normalize_b64(image_b64: str) -> str:
    raw = image_b64.strip()
    if "," in raw and raw.lower().startswith("data:"):
        raw = raw.split(",", 1)[1]
    return raw.replace("\n", "").replace("\r", "")


def _decode_bgr(image_b64: str) -> Any | None:
    import cv2
    import numpy as np

    try:
        raw = base64.b64decode(_normalize_b64(image_b64), validate=False)
        if len(raw) < 32:
            return None
        arr = np.frombuffer(raw, dtype=np.uint8)
        return cv2.imdecode(arr, cv2.IMREAD_COLOR)
    except Exception:
        return None


def _get_insightface() -> Any:
    global _insightface_app
    if _insightface_app is not None:
        return _insightface_app
    with _insightface_lock:
        if _insightface_app is not None:
            return _insightface_app
        from insightface.app import FaceAnalysis

        model = os.getenv("PATROL_FACE_MODEL", "buffalo_l").strip() or "buffalo_l"
        app = FaceAnalysis(name=model, providers=["CPUExecutionProvider"])
        det_size = int(os.getenv("PATROL_FACE_DET_SIZE", "640"))
        app.prepare(ctx_id=-1, det_size=(det_size, det_size))
        _insightface_app = app
        return _insightface_app


def _get_sface_recognizer() -> Any:
    global _sface_only_recognizer
    if _sface_only_recognizer is not None:
        return _sface_only_recognizer
    with _opencv_lock:
        if _sface_only_recognizer is not None:
            return _sface_only_recognizer
        import cv2

        if not _ensure_sface_model():
            raise RuntimeError("SFace 模型不可用")
        _sface_only_recognizer = cv2.FaceRecognizerSF.create(str(_sface_path()), "")
        return _sface_only_recognizer


def _get_opencv_sf() -> tuple[Any, Any]:
    global _opencv_detector, _opencv_recognizer
    if _opencv_detector is not None and _opencv_recognizer is not None:
        return _opencv_detector, _opencv_recognizer
    with _opencv_lock:
        if _opencv_detector is not None and _opencv_recognizer is not None:
            return _opencv_detector, _opencv_recognizer
        import cv2

        det = cv2.FaceDetectorYN.create(str(_yunet_path()), "", (320, 320))
        rec = cv2.FaceRecognizerSF.create(str(_sface_path()), "")
        _opencv_detector = det
        _opencv_recognizer = rec
        return det, rec


def _largest_face_index(faces: Any) -> int:
    import numpy as np

    areas = (faces[:, 2] - faces[:, 0]) * (faces[:, 3] - faces[:, 1])
    return int(np.argmax(areas))


def _extract_insightface(image_b64: str) -> tuple[list[float] | None, str]:
    img = _decode_bgr(image_b64)
    if img is None:
        return None, "人脸图像无法解析，请重新采集"
    app = _get_insightface()
    faces = app.get(img)
    if not faces:
        return None, "未检测到人脸，请正对摄像头、光线充足后重试"
    face = max(
        faces,
        key=lambda f: float((f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1])),
    )
    emb = face.normed_embedding
    if emb is None or len(emb) == 0:
        return None, "人脸特征提取失败，请重新采集"
    return [float(x) for x in emb], ""


def _sface_feature_from_aligned(aligned: Any) -> list[float]:
    recognizer = _get_sface_recognizer()
    feature = recognizer.feature(aligned)
    vec = [float(x) for x in feature.flatten()]
    if not vec:
        raise ValueError("empty feature")
    return vec


def _extract_yolo_sface(image_b64: str) -> tuple[list[float] | None, str]:
    img = _decode_bgr(image_b64)
    if img is None:
        return None, "人脸图像无法解析，请重新采集"
    try:
        box = face_yolo.detect_largest_face_bgr(img)
        if box is None:
            return None, "未检测到人脸，请正对摄像头、光线充足后重试"
        aligned = face_yolo.align_face_for_sface(img, box)
        vec = _sface_feature_from_aligned(aligned)
        return vec, ""
    except Exception as exc:
        logger.debug("YOLO+SFace 提取失败: %s", exc)
        return None, "人脸特征提取失败，请重新采集"


def _extract_opencv(image_b64: str) -> tuple[list[float] | None, str]:
    import cv2

    img = _decode_bgr(image_b64)
    if img is None:
        return None, "人脸图像无法解析，请重新采集"
    detector, recognizer = _get_opencv_sf()
    h, w = img.shape[:2]
    detector.setInputSize((w, h))
    _, faces = detector.detect(img)
    if faces is None or len(faces) == 0:
        return None, "未检测到人脸，请正对摄像头、光线充足后重试"
    idx = _largest_face_index(faces)
    aligned = recognizer.alignCrop(img, faces[idx])
    feature = recognizer.feature(aligned)
    vec = [float(x) for x in feature.flatten()]
    if not vec:
        return None, "人脸特征提取失败，请重新采集"
    return vec, ""


def _center_crop_square(img: Image.Image) -> Image.Image:
    w, h = img.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    return img.crop((left, top, left + side, top + side))


def _extract_legacy(image_b64: str) -> tuple[list[float] | None, str]:
    try:
        raw = base64.b64decode(_normalize_b64(image_b64), validate=False)
        if len(raw) < 32:
            return None, "人脸图像无效"
        img = Image.open(io.BytesIO(raw)).convert("L")
        img = _center_crop_square(img).resize((32, 32), Image.Resampling.LANCZOS)
    except Exception:
        return None, "人脸图像无法解析，请重新采集"
    pixels = list(img.getdata())
    mean = sum(pixels) / len(pixels)
    std = math.sqrt(sum((p - mean) ** 2 for p in pixels) / len(pixels)) or 1.0
    vec = [(p - mean) / std for p in pixels]
    if all(abs(x) < 1e-9 for x in vec):
        return None, "图像过于单一，请重新采集"
    return vec, ""


def extract_embedding(image_b64: str) -> tuple[list[float] | None, str]:
    if len(image_b64.strip()) < 64:
        return None, "人脸图像无效"
    engine = face_engine_name()
    if engine == ENGINE_INSIGHTFACE:
        return _extract_insightface(image_b64)
    if engine == ENGINE_YOLO:
        return _extract_yolo_sface(image_b64)
    if engine == ENGINE_OPENCV:
        return _extract_opencv(image_b64)
    return _extract_legacy(image_b64)


def _demo_relax_enabled() -> bool:
    return os.getenv("PATROL_DEMO_RELAX_FACE", "").strip().lower() in ("1", "true", "yes")


def extract_or_demo(image_b64: str) -> tuple[list[float] | None, str]:
    vector, err = extract_embedding(image_b64)
    if vector is not None:
        return vector, ""
    if _demo_relax_enabled() and face_engine_name() == ENGINE_LEGACY:
        return [0.0] * EMBEDDING_DIM_LEGACY, ""
    return None, err or "人脸特征提取失败"


def cosine_similarity(a: list[float], b: list[float]) -> float:
    if len(a) != len(b) or not a:
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def compare_embeddings(
    stored: list[float],
    captured: list[float],
) -> tuple[float, str | None]:
    if not stored:
        return 0.0, "云端无人脸模板，请重新完成人脸注册"
    if len(stored) != len(captured):
        return (
            0.0,
            "人脸模板已升级为新算法，请重新做人脸注册（我的 → 人员信息 → 人脸验证）",
        )
    return cosine_similarity(stored, captured), None


def _engine_label() -> str:
    engine = face_engine_name()
    if engine == ENGINE_INSIGHTFACE:
        return "InsightFace"
    if engine == ENGINE_YOLO:
        return "YOLO+SFace"
    if engine == ENGINE_OPENCV:
        return "SFace"
    return "legacy"


def verify_match(stored: list[float], captured: list[float]) -> tuple[bool, float, str]:
    score, mismatch = compare_embeddings(stored, captured)
    if mismatch:
        return False, score, mismatch
    threshold = match_threshold()
    if score < threshold:
        return (
            False,
            score,
            f"人脸验证未通过（{_engine_label()} 相似度 {score:.0%}，要求 ≥ {threshold:.0%}）",
        )
    return True, score, "人脸验证通过"
