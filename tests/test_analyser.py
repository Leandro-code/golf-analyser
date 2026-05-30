from __future__ import annotations

import cv2
import numpy as np
import pytest

from analysis import AnalysisContext, SwingAnalyser, list_analysis_runs, load_analysis_result
from analysis.visualise import browser_playback_video


def test_analyser_creates_expected_outputs(tmp_path):
    video_path = tmp_path / "input.mp4"
    _write_blank_video(video_path)

    try:
        result = SwingAnalyser().analyse(video_path, tmp_path / "run")
    except RuntimeError as exc:
        if "mediapipe is required" in str(exc):
            pytest.skip(str(exc))
        raise

    assert result.artifacts.original_video.exists()
    assert result.artifacts.annotated_video.exists()
    assert result.artifacts.landmarks_json.exists()
    assert result.artifacts.metrics_json.exists()
    assert result.artifacts.phases_json.exists()
    assert result.artifacts.keyframes_dir.exists()
    assert _video_fourcc(result.artifacts.annotated_video) in {"avc1", "h264", "H264"}
    assert len(result.landmarks) == 5
    assert len(result.phases) == 7

    runs = list_analysis_runs(tmp_path)
    assert runs == [tmp_path / "run"]

    loaded = load_analysis_result(runs[0])
    assert loaded.metadata.frame_count == result.metadata.frame_count
    assert loaded.artifacts.annotated_video == result.artifacts.annotated_video
    assert [phase.name for phase in loaded.phases] == [phase.name for phase in result.phases]
    assert loaded.assessment is None


def test_contextual_analysis_writes_assessment_and_reloads_it(tmp_path):
    video_path = tmp_path / "input.mp4"
    _write_blank_video(video_path)
    context = AnalysisContext(
        handedness="right",
        camera_view="face_on",
        club_family="iron",
    )

    result = SwingAnalyser().analyse(video_path, tmp_path / "assessed", context=context)
    loaded = load_analysis_result(tmp_path / "assessed")

    assert result.artifacts.assessment_json.exists()
    assert result.assessment is not None
    assert loaded.assessment is not None
    assert loaded.assessment.context == context
    assert all(
        finding.status == "insufficient_data"
        for finding in loaded.assessment.findings
    )

    corrected = SwingAnalyser().confirm_phase_markers(result, 0, 1, 2, 4)
    reloaded = load_analysis_result(tmp_path / "assessed")

    assert corrected.metrics.quality["phase_markers_confirmed"] is True
    assert reloaded.phases[0].detection_method == "user_confirmed_marker"


def test_list_analysis_runs_excludes_incomplete_directories(tmp_path):
    incomplete = tmp_path / "incomplete"
    incomplete.mkdir()
    (incomplete / "metrics.json").write_text("{}", encoding="utf-8")

    assert list_analysis_runs(tmp_path) == []


def test_browser_playback_transcodes_existing_opencv_video_without_rewriting_source(tmp_path):
    legacy_video = tmp_path / "legacy.mp4"
    _write_blank_video(legacy_video)
    source_bytes = legacy_video.read_bytes()

    playback_video = browser_playback_video(legacy_video)

    assert playback_video != legacy_video
    assert legacy_video.read_bytes() == source_bytes
    assert _video_fourcc(playback_video) in {"avc1", "h264", "H264"}


def _write_blank_video(path):
    writer = cv2.VideoWriter(
        str(path),
        cv2.VideoWriter_fourcc(*"mp4v"),
        5,
        (64, 64),
    )
    assert writer.isOpened()
    for _ in range(5):
        frame = np.full((64, 64, 3), 245, dtype=np.uint8)
        writer.write(frame)
    writer.release()


def _video_fourcc(path):
    cap = cv2.VideoCapture(str(path))
    assert cap.isOpened()
    code = int(cap.get(cv2.CAP_PROP_FOURCC))
    cap.release()
    return "".join(chr((code >> (8 * index)) & 0xFF) for index in range(4))
