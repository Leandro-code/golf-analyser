from __future__ import annotations

import json
import time
from pathlib import Path

import cv2
import numpy as np
from fastapi.testclient import TestClient

import api.main as api_main
from analysis.assessment import assess_swing
from analysis.models import (
    AnalysisArtifacts,
    AnalysisContext,
    AnalysisResult,
    LandmarkFrame,
    MetricSet,
    MetricValue,
    SwingPhase,
    VideoMetadata,
)
from analysis.phases import PHASE_NAMES


def test_health():
    assert TestClient(api_main.app).get("/health").json() == {"status": "ok"}


def test_create_analysis_job_with_valid_context(tmp_path, monkeypatch):
    _configure_api(tmp_path, monkeypatch)

    class FakeAnalyser:
        def analyse(self, video_path, output_dir, progress_callback=None, context=None):
            if progress_callback:
                progress_callback("Halfway", 0.5)
            _write_run(Path(output_dir), context=context)
            if progress_callback:
                progress_callback("Complete", 1.0)

    monkeypatch.setattr(api_main, "SwingAnalyser", FakeAnalyser)
    monkeypatch.setattr("api.jobs.SwingAnalyser", FakeAnalyser)
    client = TestClient(api_main.app)

    response = client.post(
        "/analyses",
        files={"video": ("swing.mp4", b"fake", "video/mp4")},
        data={
            "handedness": "right",
            "camera_view": "face_on",
            "club_family": "iron",
            "swing_type": "full_swing",
        },
    )

    assert response.status_code == 202
    run_id = response.json()["run_id"]
    status_payload = _wait_for_status(client, run_id, "completed")
    assert status_payload["progress"] == 1.0
    result = client.get(f"/analyses/{run_id}")
    assert result.status_code == 200
    assert result.json()["context"]["club_family"] == "iron"


def test_create_analysis_rejects_invalid_context(tmp_path, monkeypatch):
    _configure_api(tmp_path, monkeypatch)
    client = TestClient(api_main.app)

    response = client.post(
        "/analyses",
        files={"video": ("swing.mp4", b"fake", "video/mp4")},
        data={
            "handedness": "right",
            "camera_view": "sideways",
            "club_family": "iron",
        },
    )

    assert response.status_code == 422


def test_bearer_token_is_required_when_configured(tmp_path, monkeypatch):
    _configure_api(tmp_path, monkeypatch, token="secret")
    client = TestClient(api_main.app)

    assert client.get("/analyses").status_code == 401
    assert client.get("/analyses", headers={"Authorization": "Bearer secret"}).status_code == 200


def test_failed_job_status(tmp_path, monkeypatch):
    _configure_api(tmp_path, monkeypatch)

    class FailingAnalyser:
        def analyse(self, *args, **kwargs):
            raise RuntimeError("boom")

    monkeypatch.setattr("api.jobs.SwingAnalyser", FailingAnalyser)
    client = TestClient(api_main.app)
    response = client.post(
        "/analyses",
        files={"video": ("swing.mp4", b"fake", "video/mp4")},
        data={
            "handedness": "left",
            "camera_view": "down_the_line",
            "club_family": "wedge",
        },
    )

    payload = _wait_for_status(client, response.json()["run_id"], "failed")
    assert payload["error"] == "boom"


def test_lists_completed_runs_and_loads_legacy_without_assessment(tmp_path, monkeypatch):
    _configure_api(tmp_path, monkeypatch)
    _write_run(tmp_path / "swing_legacy", context=None)
    client = TestClient(api_main.app)

    listing = client.get("/analyses")
    detail = client.get("/analyses/swing_legacy")

    assert listing.status_code == 200
    assert [item["run_id"] for item in listing.json()] == ["swing_legacy"]
    assert detail.status_code == 200
    assert detail.json()["assessment"] is None


def test_artifact_endpoint_whitelists_names_and_blocks_traversal(tmp_path, monkeypatch):
    _configure_api(tmp_path, monkeypatch)
    run = _write_run(tmp_path / "swing_artifacts", context=_context())
    (run / "keyframes" / "address.jpg").write_bytes(b"jpg")
    client = TestClient(api_main.app)

    assert client.get("/analyses/swing_artifacts/artifacts/metrics_json").status_code == 200
    assert client.get("/analyses/swing_artifacts/artifacts/keyframe:address.jpg").status_code == 200
    assert client.get("/analyses/swing_artifacts/artifacts/keyframe:../metrics.json").status_code == 404
    assert client.get("/analyses/swing_artifacts/artifacts/arbitrary").status_code == 404


def test_redetect_and_confirm_phase_markers_regenerate_result(tmp_path, monkeypatch):
    _configure_api(tmp_path, monkeypatch)
    _write_run(tmp_path / "swing_phases", context=_context())

    class FakeAnalyser:
        def redetect_phases(self, result):
            return result.model_copy(
                update={
                    "phases": [
                        phase.model_copy(update={"detection_method": "redetected"})
                        for phase in result.phases
                    ]
                }
            )

        def confirm_phase_markers(self, result, *phase_indices):
            phases = [
                phase.model_copy(
                    update={
                        "frame_index": index,
                        "detection_method": "user_confirmed_marker",
                    }
                )
                for phase, index in zip(result.phases, phase_indices)
            ]
            return result.model_copy(update={"phases": phases})

    monkeypatch.setattr(api_main, "SwingAnalyser", FakeAnalyser)
    client = TestClient(api_main.app)

    redetected = client.post("/analyses/swing_phases/phases/redetect")
    confirmed = client.post(
        "/analyses/swing_phases/phases/confirm",
        json={"frame_indices": list(range(9))},
    )

    assert redetected.status_code == 200
    assert redetected.json()["phases"][0]["detection_method"] == "redetected"
    assert confirmed.status_code == 200
    assert confirmed.json()["phases"][0]["detection_method"] == "user_confirmed_marker"


def test_llm_assessment_eligibility_failure_and_mocked_success(tmp_path, monkeypatch):
    _configure_api(tmp_path, monkeypatch)
    _write_run(tmp_path / "swing_llm", context=_context(), reliable=False)
    client = TestClient(api_main.app)

    failure = client.post("/analyses/swing_llm/llm-assessment")
    assert failure.status_code == 409

    def fake_generate(result):
        return result.model_copy(
            update={
                "metrics": result.metrics.model_copy(
                    update={
                        "quality": {
                            **result.metrics.quality,
                            "phase_quality_issues": [],
                            "phase_scoped_metrics": True,
                        }
                    }
                )
            }
        )

    monkeypatch.setattr(api_main, "generate_llm_assessment", fake_generate)
    success = client.post("/analyses/swing_llm/llm-assessment")
    assert success.status_code == 200


def _configure_api(tmp_path: Path, monkeypatch, token: str | None = None) -> None:
    monkeypatch.setenv("GOLF_ANALYSER_OUTPUTS_DIR", str(tmp_path))
    if token is None:
        monkeypatch.delenv("GOLF_ANALYSER_API_TOKEN", raising=False)
    else:
        monkeypatch.setenv("GOLF_ANALYSER_API_TOKEN", token)
    api_main.get_settings.cache_clear()
    api_main.get_job_manager.cache_clear()


def _wait_for_status(client: TestClient, run_id: str, expected: str) -> dict:
    for _ in range(50):
        response = client.get(f"/analyses/{run_id}/status")
        assert response.status_code == 200
        payload = response.json()
        if payload["status"] == expected:
            return payload
        time.sleep(0.02)
    raise AssertionError(f"Job did not reach {expected}")


def _write_run(
    run_dir: Path,
    *,
    context: AnalysisContext | None,
    reliable: bool = True,
) -> Path:
    run_dir.mkdir(parents=True, exist_ok=True)
    _write_video(run_dir / "original.mp4")
    _write_video(run_dir / "annotated.mp4")
    (run_dir / "keyframes").mkdir(exist_ok=True)
    metadata = VideoMetadata(
        source_path="input.mp4",
        fps=10,
        frame_count=20,
        width=64,
        height=64,
        duration_seconds=2.0,
    )
    phases = [
        SwingPhase(
            name=name,
            frame_index=index,
            timestamp_seconds=index / 10,
            confidence=0.9,
            detection_method="user_confirmed_marker" if reliable else "test",
        )
        for index, name in enumerate(PHASE_NAMES)
    ]
    metrics = MetricSet(
        metrics={
            "tempo_ratio": MetricValue(
                name="Tempo ratio",
                value=2.0,
                unit="backswing:downswing",
                description="test",
                frame_index=3,
            )
        },
        quality={
            "pose_detection_rate": 0.95,
            "frames_total": 20,
            "frames_with_pose": 20,
            "phase_scoped_metrics": reliable,
            "phase_markers_confirmed": reliable,
            "phase_quality_issues": [] if reliable else ["Timing is uncertain."],
        },
    )
    assessment = assess_swing(metrics, phases, context) if context else None
    (run_dir / "landmarks.json").write_text(
        json.dumps({"metadata": metadata.model_dump(mode="json"), "frames": []}),
        encoding="utf-8",
    )
    (run_dir / "metrics.json").write_text(metrics.model_dump_json(), encoding="utf-8")
    (run_dir / "phases.json").write_text(
        json.dumps({"phases": [phase.model_dump(mode="json") for phase in phases]}),
        encoding="utf-8",
    )
    if assessment is not None:
        (run_dir / "assessment.json").write_text(
            assessment.model_dump_json(), encoding="utf-8"
        )
    return run_dir


def _context() -> AnalysisContext:
    return AnalysisContext(
        handedness="right",
        camera_view="face_on",
        club_family="iron",
    )


def _write_video(path: Path) -> None:
    writer = cv2.VideoWriter(
        str(path),
        cv2.VideoWriter_fourcc(*"mp4v"),
        10,
        (64, 64),
    )
    assert writer.isOpened()
    for _ in range(3):
        writer.write(np.full((64, 64, 3), 80, dtype=np.uint8))
    writer.release()
