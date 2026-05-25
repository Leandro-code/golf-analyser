from __future__ import annotations

import json

from streamlit.testing.v1 import AppTest

from analysis.assessment import assess_swing
from analysis.models import AnalysisContext, MetricSet, MetricValue, SwingPhase


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
        SwingPhase(name="Top of backswing", frame_index=1, timestamp_seconds=1.0, confidence=0.8, detection_method=detection_method),
        SwingPhase(name="Impact approximation", frame_index=2, timestamp_seconds=1.2, confidence=0.8, detection_method=detection_method),
        SwingPhase(name="Finish", frame_index=3, timestamp_seconds=1.8, confidence=0.8, detection_method=detection_method),
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
    (run_dir / "landmarks.json").write_text(
        json.dumps(
            {
                "metadata": {
                    "source_path": "test.mp4",
                    "fps": 30,
                    "frame_count": 4,
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


def test_history_renders_improvement_findings_for_assessed_run(tmp_path, monkeypatch):
    _write_assessed_run(tmp_path)
    monkeypatch.setenv("GOLF_ANALYSER_OUTPUTS_DIR", str(tmp_path))

    app = AppTest.from_file("app/streamlit_app.py").run(timeout=10)
    app.segmented_control[0].set_value("History").run(timeout=10)

    assert not app.exception
    assert any("Swing tempo" in warning.value for warning in app.warning)
    assert any(button.label == "Re-run automatic phase detection" for button in app.button)


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
    assert any("capture context was not recorded" in info.value for info in app.info)
