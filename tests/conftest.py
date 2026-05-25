from __future__ import annotations

import pytest

from analysis.models import LandmarkFrame, LandmarkPoint


def landmark(name: str, x: float, y: float, visibility: float = 1.0) -> LandmarkPoint:
    return LandmarkPoint(
        name=name,
        x=x,
        y=y,
        z=0.0,
        visibility=visibility,
        pixel_x=x * 100,
        pixel_y=y * 100,
    )


def frame(frame_index: int, **points: tuple[float, float]) -> LandmarkFrame:
    landmarks = [landmark(name, x, y) for name, (x, y) in points.items()]
    return LandmarkFrame(
        frame_index=frame_index,
        timestamp_seconds=frame_index / 30,
        pose_detected=bool(landmarks),
        landmarks=landmarks,
    )


@pytest.fixture(name="frame")
def frame_fixture():
    return frame
