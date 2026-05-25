from __future__ import annotations

from analysis.assessment import assess_swing
from analysis.models import AnalysisContext, MetricSet, MetricValue, SwingPhase


def metric(name: str, value: float, unit: str, frame_index: int = 1) -> MetricValue:
    return MetricValue(
        name=name,
        value=value,
        unit=unit,
        description="test metric",
        frame_index=frame_index,
    )


def phases() -> list[SwingPhase]:
    return [
        SwingPhase(name="Address", frame_index=0, timestamp_seconds=0, confidence=0.8, detection_method="test"),
        SwingPhase(name="Top of backswing", frame_index=1, timestamp_seconds=1, confidence=0.8, detection_method="test"),
        SwingPhase(name="Downswing", frame_index=2, timestamp_seconds=1.1, confidence=0.8, detection_method="test"),
        SwingPhase(name="Impact approximation", frame_index=3, timestamp_seconds=1.3, confidence=0.8, detection_method="test"),
        SwingPhase(name="Finish", frame_index=4, timestamp_seconds=2, confidence=0.8, detection_method="test"),
    ]


def test_assessment_applies_face_on_rubric_and_prioritises_attention():
    metrics = MetricSet(
        metrics={
            "tempo_ratio": metric("Tempo ratio", 5.0, "backswing:downswing"),
            "head_movement": metric("Head movement", 0.2, "shoulder widths"),
            "hip_sway": metric("Hip sway", 1.1, "shoulder widths"),
            "lead_arm_angle": metric("Lead arm angle", 175.0, "degrees"),
            "finish_stability": metric("Finish stability", 0.2, "shoulder widths"),
        },
        quality={"pose_detection_rate": 0.95},
    )
    context = AnalysisContext(handedness="right", camera_view="face_on", club_family="driver")

    assessment = assess_swing(metrics, phases(), context)

    assert assessment.rubric_version == "1.0.0"
    assert assessment.findings[0].reference.id == "tempo_ratio"
    assert assessment.findings[0].status == "needs_attention"
    assert any(finding.reference.id == "face_on_hip_sway" for finding in assessment.findings)
    assert not any(finding.reference.id.startswith("dtl_") for finding in assessment.findings)
    assert assessment.findings[0].reference.source_url


def test_assessment_gates_guidance_when_pose_coverage_is_low():
    metrics = MetricSet(
        metrics={"tempo_ratio": metric("Tempo ratio", 5.0, "backswing:downswing")},
        quality={"pose_detection_rate": 0.2},
    )
    context = AnalysisContext(handedness="right", camera_view="down_the_line", club_family="iron")

    assessment = assess_swing(metrics, phases(), context)

    assert assessment.quality_limitations
    assert all(finding.status == "insufficient_data" for finding in assessment.findings)


def test_assessment_withholds_guidance_when_phase_timing_is_unreliable():
    metrics = MetricSet(
        metrics={"tempo_ratio": metric("Tempo ratio", 0.5, "backswing:downswing")},
        quality={
            "pose_detection_rate": 0.95,
            "phase_quality_issues": ["Automatic phase timing needs confirmation."],
        },
    )
    context = AnalysisContext(handedness="right", camera_view="down_the_line", club_family="iron")

    assessment = assess_swing(metrics, phases(), context)

    assert assessment.quality_limitations == ["Automatic phase timing needs confirmation."]
    assert all(finding.status == "insufficient_data" for finding in assessment.findings)
