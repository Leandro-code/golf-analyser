from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import cv2
import numpy as np
import pytest

from analysis.assessment import assess_swing
from analysis.llm_assessment import (
    LLMAssessmentError,
    generate_llm_assessment,
    llm_assessment_is_current,
)
from analysis.models import (
    AnalysisArtifacts,
    AnalysisContext,
    AnalysisResult,
    LandmarkFrame,
    LandmarkPoint,
    LLMAssessmentContent,
    LLMObservation,
    LLMPriority,
    MetricSet,
    MetricValue,
    SwingPhase,
    VideoMetadata,
)
from analysis.storage import load_analysis_result


class FakeResponses:
    def __init__(self):
        self.calls = []

    def parse(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(
            output_parsed=LLMAssessmentContent(
                overview="The selected frames show a usable swing sequence.",
                strengths=["The finish frame shows a held pose."],
                observations=[
                    LLMObservation(
                        title="Top position",
                        observation="The top frame gives a clear view of arm structure.",
                        supporting_frame_ids=["frame_000020"],
                        related_metric_keys=["lead_arm_angle"],
                        confidence=0.82,
                    )
                ],
                priorities=[
                    LLMPriority(
                        title="Improve transition control",
                        rationale="The transition frames suggest a quick change of direction.",
                        practice_cue="Rehearse a brief pause at the top before starting down.",
                        supporting_frame_ids=["frame_000020", "frame_000040"],
                        related_metric_keys=["tempo_ratio"],
                        confidence=0.76,
                        support_type="ai_generated",
                    )
                ],
                limitations=["Still frames cannot establish strike quality."],
            )
        )


class FakeClient:
    def __init__(self):
        self.responses = FakeResponses()


def _result(tmp_path: Path, camera_view: str = "face_on") -> AnalysisResult:
    run_dir = tmp_path / "run"
    run_dir.mkdir()
    original = run_dir / "original.mp4"
    annotated = run_dir / "annotated.mp4"
    _write_video(original)
    _write_video(annotated)
    phases = [
        SwingPhase(name="Address", frame_index=0, timestamp_seconds=0.0, confidence=0.9, detection_method="test"),
        SwingPhase(name="Takeaway", frame_index=10, timestamp_seconds=1.0, confidence=0.9, detection_method="test"),
        SwingPhase(name="Top of backswing", frame_index=20, timestamp_seconds=2.0, confidence=0.9, detection_method="test"),
        SwingPhase(name="Downswing", frame_index=30, timestamp_seconds=3.0, confidence=0.9, detection_method="test"),
        SwingPhase(name="Impact approximation", frame_index=40, timestamp_seconds=4.0, confidence=0.9, detection_method="test"),
        SwingPhase(name="Follow-through", frame_index=50, timestamp_seconds=5.0, confidence=0.9, detection_method="test"),
        SwingPhase(name="Finish", frame_index=60, timestamp_seconds=6.0, confidence=0.9, detection_method="test"),
    ]
    metrics = MetricSet(
        metrics={
            "tempo_ratio": MetricValue(name="Tempo ratio", value=2.5, unit="backswing:downswing", description="test", frame_index=20),
            "lead_arm_angle": MetricValue(name="Lead arm angle", value=150.0, unit="degrees", description="test", frame_index=20),
            "posture_retention": MetricValue(name="Posture retention", value=10.0, unit="degrees change", description="test", frame_index=30),
        },
        quality={
            "pose_detection_rate": 0.95,
            "frames_total": 70,
            "frames_with_pose": 70,
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
    return AnalysisResult(
        metadata=VideoMetadata(
            source_path="input.mp4",
            fps=10,
            frame_count=70,
            width=128,
            height=128,
            duration_seconds=7.0,
        ),
        landmarks=_landmark_frames(),
        phases=phases,
        metrics=metrics,
        artifacts=AnalysisArtifacts(
            output_dir=run_dir,
            original_video=original,
            annotated_video=annotated,
            landmarks_json=run_dir / "landmarks.json",
            metrics_json=run_dir / "metrics.json",
            phases_json=run_dir / "phases.json",
            keyframes_dir=run_dir / "keyframes",
            assessment_json=run_dir / "assessment.json",
            llm_assessment_json=run_dir / "llm_assessment.json",
            llm_frames_dir=run_dir / "llm_frames",
        ),
        assessment=assess_swing(metrics, phases, context),
    )


def test_generate_llm_assessment_sends_neighbor_frames_and_persists(tmp_path, monkeypatch):
    client = FakeClient()
    monkeypatch.setenv("GOLF_ANALYSER_OPENAI_MODEL", "configured-model")

    updated = generate_llm_assessment(_result(tmp_path), client=client)

    assert updated.llm_assessment is not None
    assert updated.llm_assessment.model == "configured-model"
    assert updated.artifacts.llm_assessment_json.exists()
    assert llm_assessment_is_current(updated) is True
    call = client.responses.calls[0]
    assert call["store"] is False
    assert call["text_format"] is LLMAssessmentContent
    payload_text = call["input"][0]["content"][0]["text"]
    assert "measurements" in payload_text
    assert "reference_checks" not in payload_text
    assert "expected" not in payload_text
    assert "within_reference" not in payload_text
    images = [
        item for item in call["input"][0]["content"] if item["type"] == "input_image"
    ]
    assert len(images) == 9
    frame_ids = {frame.frame_id for frame in updated.llm_assessment.submitted_frames}
    assert {"frame_000000", "frame_000019", "frame_000020", "frame_000021"}.issubset(frame_ids)
    assert (updated.artifacts.llm_frames_dir / "frame_000020.jpg").exists()
    evidence_image = cv2.imread(
        str(updated.artifacts.llm_frames_dir / "frame_000020.jpg")
    )
    assert evidence_image is not None
    yellow_pose_pixels = (
        (evidence_image[:, :, 0] < 80)
        & (evidence_image[:, :, 1] > 160)
        & (evidence_image[:, :, 2] > 160)
    )
    assert np.count_nonzero(yellow_pose_pixels) > 0


def test_down_the_line_llm_assessment_includes_downswing_neighbors(tmp_path):
    client = FakeClient()

    updated = generate_llm_assessment(
        _result(tmp_path, camera_view="down_the_line"), client=client
    )

    relations = {
        relation
        for frame in updated.llm_assessment.submitted_frames
        for relation in frame.phase_relations
    }
    assert "Downswing anchor" in relations
    assert any(relation.startswith("Downswing -") for relation in relations)
    assert any(relation.startswith("Downswing +") for relation in relations)


def test_llm_assessment_blocks_unreliable_inputs_and_unknown_frame_ids(tmp_path):
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
        }
    )
    with pytest.raises(LLMAssessmentError):
        generate_llm_assessment(unreliable, client=FakeClient())

    class BadResponses(FakeResponses):
        def parse(self, **kwargs):
            response = super().parse(**kwargs)
            response.output_parsed.priorities[0].supporting_frame_ids = ["unknown"]
            return response

    bad_client = FakeClient()
    bad_client.responses = BadResponses()
    with pytest.raises(LLMAssessmentError):
        generate_llm_assessment(result, client=bad_client)


def test_saved_llm_assessment_reloads_and_becomes_stale_after_phase_change(tmp_path):
    result = _result(tmp_path)
    result.artifacts.keyframes_dir.mkdir()
    result.artifacts.landmarks_json.write_text(
        json.dumps({"metadata": result.metadata.model_dump(mode="json"), "frames": []}),
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

    generated = generate_llm_assessment(result, client=FakeClient(), model="test-model")
    loaded = load_analysis_result(generated.artifacts.output_dir)

    assert loaded.llm_assessment is not None
    assert loaded.llm_assessment.model == "test-model"
    assert llm_assessment_is_current(loaded) is True

    changed = loaded.model_copy(
        update={
            "phases": [
                phase.model_copy(update={"frame_index": phase.frame_index + 1})
                if phase.name == "Top of backswing"
                else phase
                for phase in loaded.phases
            ]
        }
    )
    assert llm_assessment_is_current(changed) is False


def test_saved_llm_assessment_reloads_legacy_sourced_reference_support_type(tmp_path):
    result = _result(tmp_path)
    result.artifacts.keyframes_dir.mkdir()
    result.artifacts.landmarks_json.write_text(
        json.dumps({"metadata": result.metadata.model_dump(mode="json"), "frames": []}),
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
    generated = generate_llm_assessment(result, client=FakeClient(), model="test-model")
    payload = json.loads(generated.artifacts.llm_assessment_json.read_text(encoding="utf-8"))
    for priority in payload["content"]["priorities"]:
        priority["support_type"] = "sourced_reference"
    generated.artifacts.llm_assessment_json.write_text(
        json.dumps(payload), encoding="utf-8"
    )

    loaded = load_analysis_result(generated.artifacts.output_dir)

    assert loaded.llm_assessment is not None
    assert {
        priority.support_type for priority in loaded.llm_assessment.content.priorities
    } == {"ai_generated"}


def _write_video(path: Path) -> None:
    writer = cv2.VideoWriter(
        str(path),
        cv2.VideoWriter_fourcc(*"mp4v"),
        10,
        (128, 128),
    )
    assert writer.isOpened()
    for index in range(70):
        frame = np.full((128, 128, 3), 20 + index, dtype=np.uint8)
        writer.write(frame)
    writer.release()


def _landmark_frames() -> list[LandmarkFrame]:
    return [
        LandmarkFrame(
            frame_index=index,
            timestamp_seconds=index / 10,
            pose_detected=True,
            landmarks=[
                LandmarkPoint(
                    name="left_shoulder",
                    x=20,
                    y=80,
                    pixel_x=20,
                    pixel_y=80,
                    visibility=1.0,
                ),
                LandmarkPoint(
                    name="left_elbow",
                    x=30,
                    y=90,
                    pixel_x=30,
                    pixel_y=90,
                    visibility=1.0,
                ),
                LandmarkPoint(
                    name="left_wrist",
                    x=40,
                    y=100,
                    pixel_x=40,
                    pixel_y=100,
                    visibility=1.0,
                ),
            ],
        )
        for index in (0, 19, 20, 21, 39, 40, 41, 59, 60)
    ]
