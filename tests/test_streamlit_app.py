from __future__ import annotations

import json
from datetime import datetime, timezone

import cv2
import numpy as np
from streamlit.testing.v1 import AppTest

from analysis.assessment import assess_swing
from analysis.llm_assessment import _select_frames, llm_assessment_fingerprint
from analysis.models import (
    AnalysisContext,
    LLMAssessment,
    LLMAssessmentContent,
    LLMPriority,
    MetricSet,
    MetricValue,
    SwingPhase,
)
from analysis.phases import IMPACT_PHASE, P6_PHASE, TOP_PHASE
from analysis.storage import load_analysis_result


def test_new_analysis_requires_capture_context_controls():
    app = AppTest.from_file("app/streamlit_app.py").run(timeout=10)

    assert not app.exception
    assert app.segmented_control[0].value == "New analysis"
    assert [widget.label for widget in app.selectbox] == [
        "Handedness",
        "Camera view",
        "Club family",
    ]


def _write_assessed_run(tmp_path, detection_method: str = "test"):
    run_dir = tmp_path / "swing_20260525_120000"
    run_dir.mkdir()
    phases = [
        SwingPhase(name="Address", frame_index=0, timestamp_seconds=0.0, confidence=0.8, detection_method=detection_method),
        SwingPhase(name="Takeaway", frame_index=1, timestamp_seconds=0.3, confidence=0.8, detection_method=detection_method),
        SwingPhase(name="Lead arm parallel backswing (P3)", frame_index=2, timestamp_seconds=0.6, confidence=0.8, detection_method=detection_method),
        SwingPhase(name=TOP_PHASE, frame_index=3, timestamp_seconds=1.0, confidence=0.8, detection_method=detection_method),
        SwingPhase(name="Lead arm parallel downswing (P5)", frame_index=4, timestamp_seconds=1.1, confidence=0.8, detection_method=detection_method),
        SwingPhase(name=P6_PHASE, frame_index=5, timestamp_seconds=1.15, confidence=0.8, detection_method=detection_method),
        SwingPhase(name=IMPACT_PHASE, frame_index=6, timestamp_seconds=1.2, confidence=0.8, detection_method=detection_method),
        SwingPhase(name="Shaft parallel follow-through (P8)", frame_index=7, timestamp_seconds=1.5, confidence=0.8, detection_method=detection_method),
        SwingPhase(name="Finish", frame_index=8, timestamp_seconds=1.8, confidence=0.8, detection_method=detection_method),
    ]
    metrics = MetricSet(
        metrics={
            "tempo_ratio": MetricValue(name="Tempo ratio", value=5.0, unit="backswing:downswing", description="test", frame_index=1),
        },
        quality={"pose_detection_rate": 0.95, "phase_scoped_metrics": True},
    )
    assessment = assess_swing(
        metrics,
        phases,
        AnalysisContext(handedness="right", camera_view="face_on", club_family="iron"),
    )
    (run_dir / "original.mp4").write_bytes(b"")
    (run_dir / "annotated.mp4").write_bytes(b"")
    (run_dir / "keyframes").mkdir()
    for name in (
        "address.jpg",
        "top_p4.jpg",
        "impact_approximation_p7.jpg",
        "finish.jpg",
    ):
        assert cv2.imwrite(
            str(run_dir / "keyframes" / name),
            np.full((8, 8, 3), 245, dtype=np.uint8),
        )
    (run_dir / "landmarks.json").write_text(
        json.dumps(
            {
                "metadata": {
                    "source_path": "test.mp4",
                    "fps": 30,
                    "frame_count": 9,
                    "width": 64,
                    "height": 64,
                    "duration_seconds": 0.13,
                },
                "frames": [],
            }
        ),
        encoding="utf-8",
    )
    (run_dir / "metrics.json").write_text(metrics.model_dump_json(), encoding="utf-8")
    (run_dir / "phases.json").write_text(
        json.dumps({"phases": [phase.model_dump(mode="json") for phase in phases]}),
        encoding="utf-8",
    )
    (run_dir / "assessment.json").write_text(assessment.model_dump_json(), encoding="utf-8")
    return run_dir


def test_history_renders_measured_pose_data_for_assessed_run(tmp_path, monkeypatch):
    _write_assessed_run(tmp_path)
    monkeypatch.setenv("GOLF_ANALYSER_OUTPUTS_DIR", str(tmp_path))

    app = AppTest.from_file("app/streamlit_app.py").run(timeout=10)
    app.segmented_control[0].set_value("History").run(timeout=10)

    assert not app.exception
    assert any("Measured Pose Data" in expander.label for expander in app.expander)
    assert any(button.label == "Re-run automatic phase detection" for button in app.button)


def test_history_offers_primary_opt_in_ai_swing_assessment(tmp_path, monkeypatch):
    _write_assessed_run(tmp_path)
    monkeypatch.setenv("GOLF_ANALYSER_OUTPUTS_DIR", str(tmp_path))
    monkeypatch.setenv("OPENAI_API_KEY", "test-key")

    app = AppTest.from_file("app/streamlit_app.py").run(timeout=10)
    app.segmented_control[0].set_value("History").run(timeout=10)

    assert not app.exception
    assert any(button.label == "Generate AI swing assessment" for button in app.button)
    assert any("Model-generated swing assessment" in item.value for item in app.caption)
    assert any("Measured Pose Data" in expander.label for expander in app.expander)


def test_history_renders_priority_drilldown_for_current_ai_assessment(tmp_path, monkeypatch):
    run_dir = _write_assessed_run(tmp_path)
    result = load_analysis_result(run_dir)
    submitted_frames = _select_frames(result)
    llm_assessment = LLMAssessment(
        schema_version="1.0.0",
        prompt_version="1.0.0",
        model="test-model",
        generated_at=datetime.now(timezone.utc).isoformat(),
        context=result.assessment.context,
        submitted_frames=submitted_frames,
        quality_snapshot=result.metrics.quality,
        evidence_fingerprint=llm_assessment_fingerprint(result, submitted_frames),
        content=LLMAssessmentContent(
            overview="The selected frames show a usable swing sequence.",
            priorities=[
                LLMPriority(
                    title="Keep posture stable",
                    rationale="The top and impact frames show posture movement.",
                    practice_cue="Make slow-motion swings while keeping chest distance stable.",
                    explanation="This means checking whether the torso position holds through transition.",
                    drills=["Pause at the top, then rehearse halfway down."],
                    practice_plan=["Make five slow rehearsals before hitting easy shots."],
                    supporting_frame_ids=[submitted_frames[0].frame_id],
                    related_metric_keys=["tempo_ratio"],
                    confidence=0.8,
                )
            ],
        ),
    )
    (run_dir / "llm_assessment.json").write_text(
        llm_assessment.model_dump_json(), encoding="utf-8"
    )
    monkeypatch.setenv("GOLF_ANALYSER_OUTPUTS_DIR", str(tmp_path))
    monkeypatch.setenv("OPENAI_API_KEY", "test-key")

    app = AppTest.from_file("app/streamlit_app.py").run(timeout=10)
    app.segmented_control[0].set_value("History").run(timeout=10)

    assert not app.exception
    assert any("Explore this priority" in expander.label for expander in app.expander)
    assert any(
        "This means checking whether the torso position holds through transition."
        in markdown.value
        for markdown in app.markdown
    )
    assert any(
        "Pause at the top, then rehearse halfway down." in markdown.value
        for markdown in app.markdown
    )


def test_history_withholds_guidance_from_superseded_phase_detection(tmp_path, monkeypatch):
    _write_assessed_run(tmp_path, detection_method="minimum_wrist_y_coordinate")
    monkeypatch.setenv("GOLF_ANALYSER_OUTPUTS_DIR", str(tmp_path))

    app = AppTest.from_file("app/streamlit_app.py").run(timeout=10)
    app.segmented_control[0].set_value("History").run(timeout=10)

    assert not app.exception
    assert any("superseded phase timing or evidence" in warning.value for warning in app.warning)


def test_history_explains_legacy_run_without_assessment(tmp_path, monkeypatch):
    run_dir = tmp_path / "swing_20260525_120001"
    run_dir.mkdir()
    (run_dir / "original.mp4").write_bytes(b"")
    (run_dir / "annotated.mp4").write_bytes(b"")
    (run_dir / "keyframes").mkdir()
    (run_dir / "landmarks.json").write_text(
        json.dumps(
            {
                "metadata": {
                    "source_path": "test.mp4",
                    "fps": 30,
                    "frame_count": 0,
                    "width": 64,
                    "height": 64,
                    "duration_seconds": 0,
                },
                "frames": [],
            }
        ),
        encoding="utf-8",
    )
    (run_dir / "metrics.json").write_text('{"metrics": {}, "quality": {}}', encoding="utf-8")
    (run_dir / "phases.json").write_text('{"phases": []}', encoding="utf-8")
    monkeypatch.setenv("GOLF_ANALYSER_OUTPUTS_DIR", str(tmp_path))

    app = AppTest.from_file("app/streamlit_app.py").run(timeout=10)
    app.segmented_control[0].set_value("History").run(timeout=10)

    assert not app.exception
    assert any("requires a contextual analysis" in info.value for info in app.info)
