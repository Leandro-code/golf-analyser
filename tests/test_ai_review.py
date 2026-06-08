from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from analysis.ai_review import (
    AIReviewError,
    ai_review_is_current,
    generate_ai_review,
)
from analysis.assessment import assess_swing
from analysis.models import (
    AIReviewContent,
    AIReviewObservation,
    AIReviewPriority,
    AnalysisArtifacts,
    AnalysisContext,
    AnalysisResult,
    MetricSet,
    MetricValue,
    SwingPhase,
    VideoMetadata,
)
from analysis.phases import IMPACT_PHASE, P6_PHASE, TOP_PHASE
from analysis.storage import load_analysis_result


class FakeResponses:
    def __init__(self):
        self.calls = []

    def parse(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(
            output_parsed=AIReviewContent(
                summary="The supplied stills show an observable posture pattern.",
                observations=[
                    AIReviewObservation(
                        phase_name=TOP_PHASE,
                        observation="Lead arm position is visible at the labelled top.",
                        evidence_visible="The annotated arm line remains in view.",
                        confidence=0.8,
                        related_metric_key="lead_arm_angle",
                    )
                ],
                priorities=[
                    AIReviewPriority(
                        focus="Maintain width",
                        practice_cue="Rehearse the top position slowly.",
                        supporting_phases=[TOP_PHASE],
                    )
                ],
                limitations=["Still frames cannot establish strike outcome."],
            )
        )


class FakeClient:
    def __init__(self):
        self.responses = FakeResponses()


def _result(tmp_path: Path, camera_view: str = "face_on") -> AnalysisResult:
    phases = [
        SwingPhase(name="Address", frame_index=0, timestamp_seconds=0.0, confidence=0.8, detection_method="test"),
        SwingPhase(name="Takeaway", frame_index=1, timestamp_seconds=0.1, confidence=0.8, detection_method="test"),
        SwingPhase(name="Lead arm parallel backswing (P3)", frame_index=2, timestamp_seconds=0.2, confidence=0.8, detection_method="test"),
        SwingPhase(name=TOP_PHASE, frame_index=3, timestamp_seconds=0.3, confidence=0.8, detection_method="test"),
        SwingPhase(name="Lead arm parallel downswing (P5)", frame_index=4, timestamp_seconds=0.4, confidence=0.8, detection_method="test"),
        SwingPhase(name=P6_PHASE, frame_index=5, timestamp_seconds=0.5, confidence=0.8, detection_method="test"),
        SwingPhase(name=IMPACT_PHASE, frame_index=6, timestamp_seconds=0.6, confidence=0.8, detection_method="test"),
        SwingPhase(name="Shaft parallel follow-through (P8)", frame_index=7, timestamp_seconds=0.7, confidence=0.8, detection_method="test"),
        SwingPhase(name="Finish", frame_index=8, timestamp_seconds=0.8, confidence=0.8, detection_method="test"),
    ]
    metrics = MetricSet(
        metrics={
            "tempo_ratio": MetricValue(
                name="Tempo ratio",
                value=3.0,
                unit="backswing:downswing",
                description="test",
                frame_index=2,
            ),
            "posture_retention": MetricValue(
                name="Posture retention",
                value=8.0,
                unit="degrees change",
                description="test",
                frame_index=3,
            ),
        },
        quality={
            "pose_detection_rate": 0.95,
            "frames_total": 7,
            "frames_with_pose": 7,
            "phase_scoped_metrics": True,
            "phase_markers_confirmed": True,
            "phase_quality_issues": [],
        },
    )
    context = AnalysisContext(
        handedness="right",
        camera_view=camera_view,
        club_family="iron",
    )
    run_dir = tmp_path / "run"
    keyframes = run_dir / "keyframes"
    keyframes.mkdir(parents=True)
    for name in (
        "address.jpg",
            "top_p4.jpg",
            "shaft_parallel_downswing_p6.jpg",
            "impact_approximation_p7.jpg",
            "finish.jpg",
    ):
        (keyframes / name).write_bytes(b"jpeg")
    return AnalysisResult(
        metadata=VideoMetadata(
            source_path="input.mp4",
            fps=10,
            frame_count=9,
            width=64,
            height=64,
            duration_seconds=0.9,
        ),
        landmarks=[],
        phases=phases,
        metrics=metrics,
        artifacts=AnalysisArtifacts(
            output_dir=run_dir,
            original_video=run_dir / "original.mp4",
            annotated_video=run_dir / "annotated.mp4",
            landmarks_json=run_dir / "landmarks.json",
            metrics_json=run_dir / "metrics.json",
            phases_json=run_dir / "phases.json",
            keyframes_dir=keyframes,
            assessment_json=run_dir / "assessment.json",
            ai_review_json=run_dir / "ai_review.json",
        ),
        assessment=assess_swing(metrics, phases, context),
    )


def test_generate_ai_review_sends_anchor_images_and_persists_structured_output(
    tmp_path, monkeypatch
):
    result = _result(tmp_path)
    client = FakeClient()
    monkeypatch.setenv("GOLF_ANALYSER_OPENAI_MODEL", "configured-model")

    updated = generate_ai_review(result, client=client)

    assert updated.ai_review is not None
    assert updated.ai_review.model == "configured-model"
    assert updated.artifacts.ai_review_json.exists()
    assert ai_review_is_current(updated) is True
    call = client.responses.calls[0]
    assert call["store"] is False
    assert call["text_format"] is AIReviewContent
    images = [
        item
        for item in call["input"][0]["content"]
        if item["type"] == "input_image"
    ]
    assert len(images) == 4
    assert all(item["image_url"].startswith("data:image/jpeg;base64,") for item in images)


def test_down_the_line_review_includes_downswing_image(tmp_path):
    client = FakeClient()

    generate_ai_review(_result(tmp_path, camera_view="down_the_line"), client=client)

    text_items = [
        item["text"]
        for item in client.responses.calls[0]["input"][0]["content"]
        if item["type"] == "input_text"
    ]
    assert any(f"Phase image: {P6_PHASE}" in text for text in text_items)


def test_saved_ai_review_reloads_with_analysis_history(tmp_path):
    result = _result(tmp_path)
    result.artifacts.original_video.write_bytes(b"")
    result.artifacts.annotated_video.write_bytes(b"")
    result.artifacts.landmarks_json.write_text(
        json.dumps(
            {
                "metadata": result.metadata.model_dump(mode="json"),
                "frames": [],
            }
        ),
        encoding="utf-8",
    )
    result.artifacts.metrics_json.write_text(
        result.metrics.model_dump_json(), encoding="utf-8"
    )
    result.artifacts.phases_json.write_text(
        json.dumps({"phases": [phase.model_dump(mode="json") for phase in result.phases]}),
        encoding="utf-8",
    )
    result.artifacts.assessment_json.write_text(
        result.assessment.model_dump_json(), encoding="utf-8"
    )

    generated = generate_ai_review(result, client=FakeClient(), model="test-model")
    loaded = load_analysis_result(generated.artifacts.output_dir)

    assert loaded.ai_review is not None
    assert loaded.ai_review.model == "test-model"
    assert ai_review_is_current(loaded) is True


def test_ai_review_is_blocked_for_unreliable_or_changed_evidence(tmp_path):
    client = FakeClient()
    result = _result(tmp_path)
    unreliable = result.model_copy(
        update={
            "metrics": result.metrics.model_copy(
                update={
                    "quality": {
                        **result.metrics.quality,
                        "phase_quality_issues": ["Timing is uncertain."],
                    }
                }
            ),
            "assessment": result.assessment.model_copy(
                update={"quality_limitations": ["Timing is uncertain."]}
            ),
        }
    )

    with pytest.raises(AIReviewError):
        generate_ai_review(unreliable, client=client)
    assert client.responses.calls == []

    generated = generate_ai_review(result, client=client)
    changed_phases = [
        phase.model_copy(update={"frame_index": phase.frame_index + 1})
        if phase.name == TOP_PHASE
        else phase
        for phase in generated.phases
    ]
    stale = generated.model_copy(update={"phases": changed_phases})
    assert ai_review_is_current(stale) is False
