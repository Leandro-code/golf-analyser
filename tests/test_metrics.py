from __future__ import annotations

from analysis.metrics import calculate_metrics
from analysis.models import AnalysisContext, SwingPhase
from analysis.phases import IMPACT_PHASE, TOP_PHASE


def test_calculate_metrics_from_landmarks(frame):
    frames = [
        frame(
            0,
            nose=(0.5, 0.2),
            left_shoulder=(0.42, 0.35),
            right_shoulder=(0.58, 0.35),
            left_elbow=(0.36, 0.48),
            left_wrist=(0.3, 0.6),
            right_wrist=(0.7, 0.6),
            left_hip=(0.45, 0.65),
            right_hip=(0.55, 0.65),
            left_knee=(0.44, 0.82),
            right_knee=(0.56, 0.82),
            left_ankle=(0.43, 0.95),
            right_ankle=(0.57, 0.95),
        ),
        frame(
            15,
            nose=(0.52, 0.22),
            left_shoulder=(0.4, 0.34),
            right_shoulder=(0.56, 0.34),
            left_elbow=(0.33, 0.42),
            left_wrist=(0.26, 0.25),
            right_wrist=(0.64, 0.25),
            left_hip=(0.48, 0.65),
            right_hip=(0.58, 0.65),
            left_knee=(0.47, 0.82),
            right_knee=(0.59, 0.82),
            left_ankle=(0.45, 0.95),
            right_ankle=(0.61, 0.95),
        ),
    ]
    phases = [
        SwingPhase(name="Address", frame_index=0, timestamp_seconds=0.0, confidence=1, detection_method="test"),
        SwingPhase(name=TOP_PHASE, frame_index=15, timestamp_seconds=0.5, confidence=1, detection_method="test"),
        SwingPhase(name=IMPACT_PHASE, frame_index=30, timestamp_seconds=0.75, confidence=1, detection_method="test"),
    ]

    metrics = calculate_metrics(frames, phases)

    assert metrics.quality["frames_total"] == 2
    assert metrics.quality["pose_detection_rate"] == 1.0
    assert metrics.metrics["tempo_ratio"].value == 2.0
    assert metrics.metrics["head_movement"].value is not None
    assert metrics.metrics["wrist_path_trajectory"].value[0]["frame_index"] == 0


def test_lead_arm_metric_uses_handedness_at_top(frame):
    frames = [
        frame(
            0,
            left_shoulder=(0.4, 0.35),
            right_shoulder=(0.6, 0.35),
            left_hip=(0.45, 0.65),
            right_hip=(0.55, 0.65),
        ),
        frame(
            1,
            left_shoulder=(0.4, 0.35),
            left_elbow=(0.32, 0.45),
            left_wrist=(0.24, 0.55),
            right_shoulder=(0.6, 0.35),
            right_elbow=(0.68, 0.45),
            right_wrist=(0.61, 0.43),
            left_hip=(0.45, 0.65),
            right_hip=(0.55, 0.65),
        ),
    ]
    phases = [
        SwingPhase(name="Address", frame_index=0, timestamp_seconds=0.0, confidence=1, detection_method="test"),
        SwingPhase(name=TOP_PHASE, frame_index=1, timestamp_seconds=0.1, confidence=1, detection_method="test"),
    ]
    context = AnalysisContext(
        handedness="left", camera_view="face_on", club_family="iron"
    )

    metrics = calculate_metrics(frames, phases, context)

    assert metrics.metrics["lead_arm_angle"].frame_index == 1
    assert "Right lead" in metrics.metrics["lead_arm_angle"].description
    assert metrics.metrics["lead_arm_angle"].value < 155


def test_phase_scoped_metrics_do_not_use_post_swing_extrema(frame):
    frames = [
        frame(
            0,
            nose=(0.50, 0.2),
            left_shoulder=(0.4, 0.35),
            right_shoulder=(0.6, 0.35),
            left_hip=(0.45, 0.65),
            right_hip=(0.55, 0.65),
        ),
        frame(
            5,
            nose=(0.51, 0.2),
            left_shoulder=(0.4, 0.35),
            right_shoulder=(0.6, 0.35),
            left_hip=(0.47, 0.65),
            right_hip=(0.57, 0.65),
        ),
        frame(
            10,
            nose=(0.54, 0.2),
            left_shoulder=(0.4, 0.35),
            right_shoulder=(0.6, 0.35),
            left_hip=(0.48, 0.65),
            right_hip=(0.58, 0.65),
        ),
        frame(
            20,
            nose=(0.90, 0.2),
            left_shoulder=(0.4, 0.35),
            right_shoulder=(0.6, 0.35),
            left_hip=(0.90, 0.65),
            right_hip=(1.00, 0.65),
        ),
    ]
    phases = [
        SwingPhase(name="Address", frame_index=0, timestamp_seconds=0, confidence=1, detection_method="test"),
        SwingPhase(name=TOP_PHASE, frame_index=5, timestamp_seconds=0.2, confidence=1, detection_method="test"),
        SwingPhase(name=IMPACT_PHASE, frame_index=10, timestamp_seconds=0.3, confidence=1, detection_method="test"),
        SwingPhase(name="Finish", frame_index=20, timestamp_seconds=0.6, confidence=1, detection_method="test"),
    ]

    metrics = calculate_metrics(frames, phases)

    assert metrics.metrics["head_movement"].frame_index == 10
    assert metrics.metrics["hip_sway"].frame_index == 5
    assert metrics.quality["phase_scoped_metrics"] is True
