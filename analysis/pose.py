from __future__ import annotations

import importlib
import os
import urllib.request
from pathlib import Path

import cv2

from analysis.models import LandmarkFrame, LandmarkPoint

MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
    "pose_landmarker_lite/float16/latest/pose_landmarker_lite.task"
)
MODEL_PATH_ENV = "GOLF_ANALYSER_POSE_MODEL"


class MediaPipePoseEstimator:
    """Small wrapper around MediaPipe Pose with repository-native models."""

    def __init__(self, image_width: int, image_height: int) -> None:
        try:
            import mediapipe as mp
        except ImportError as exc:  # pragma: no cover - exercised only in broken envs
            raise RuntimeError(
                "mediapipe is required for pose extraction. Install dependencies in .venv."
            ) from exc

        self._mp = mp
        self._vision = importlib.import_module("mediapipe.tasks.python.vision")
        self._image_width = image_width
        self._image_height = image_height
        model_path = _ensure_pose_model()
        options = self._vision.PoseLandmarkerOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=str(model_path)),
            running_mode=self._vision.RunningMode.VIDEO,
            num_poses=1,
            min_pose_detection_confidence=0.5,
            min_pose_presence_confidence=0.5,
            min_tracking_confidence=0.5,
            output_segmentation_masks=False,
        )
        self._landmarker = self._vision.PoseLandmarker.create_from_options(options)

    def close(self) -> None:
        self._landmarker.close()

    def __enter__(self) -> "MediaPipePoseEstimator":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

    def extract_frame(
        self, frame_bgr, frame_index: int, timestamp_seconds: float
    ) -> LandmarkFrame:
        frame_rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        image = self._mp.Image(image_format=self._mp.ImageFormat.SRGB, data=frame_rgb)
        results = self._landmarker.detect_for_video(
            image, int(round(timestamp_seconds * 1000))
        )
        if not results.pose_landmarks:
            return LandmarkFrame(
                frame_index=frame_index,
                timestamp_seconds=timestamp_seconds,
                pose_detected=False,
            )

        landmarks = []
        pose_landmarks = results.pose_landmarks[0]
        for landmark_enum, landmark in zip(
            self._vision.PoseLandmark, pose_landmarks
        ):
            landmarks.append(
                LandmarkPoint(
                    name=landmark_enum.name.lower(),
                    x=float(landmark.x),
                    y=float(landmark.y),
                    z=float(landmark.z) if landmark.z is not None else None,
                    visibility=float(getattr(landmark, "visibility", 1.0)),
                    pixel_x=float(landmark.x * self._image_width),
                    pixel_y=float(landmark.y * self._image_height),
                )
            )

        return LandmarkFrame(
            frame_index=frame_index,
            timestamp_seconds=timestamp_seconds,
            pose_detected=True,
            landmarks=landmarks,
        )


def _ensure_pose_model() -> Path:
    configured = os.environ.get(MODEL_PATH_ENV)
    if configured:
        model_path = Path(configured).expanduser()
        if not model_path.exists():
            raise RuntimeError(f"{MODEL_PATH_ENV} points to a missing file: {model_path}")
        return model_path

    project_root = Path(__file__).resolve().parents[1]
    model_path = project_root / ".cache" / "mediapipe" / "pose_landmarker_lite.task"
    if model_path.exists():
        return model_path

    model_path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = model_path.with_suffix(".download")
    try:
        urllib.request.urlretrieve(MODEL_URL, temp_path)
        temp_path.replace(model_path)
    except Exception as exc:  # pragma: no cover - depends on network availability
        temp_path.unlink(missing_ok=True)
        raise RuntimeError(
            "Unable to download the MediaPipe pose model. "
            f"Set {MODEL_PATH_ENV} to a local .task model file and retry."
        ) from exc
    return model_path
