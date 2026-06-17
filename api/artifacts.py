from __future__ import annotations

from pathlib import Path

from analysis.models import AnalysisResult


STATIC_ARTIFACTS = {
    "original_video": "original_video",
    "annotated_video": "annotated_video",
    "landmarks_json": "landmarks_json",
    "metrics_json": "metrics_json",
    "phases_json": "phases_json",
    "assessment_json": "assessment_json",
    "llm_assessment_json": "llm_assessment_json",
}


def artifact_path(result: AnalysisResult, artifact_name: str) -> Path | None:
    if artifact_name in STATIC_ARTIFACTS:
        path = getattr(result.artifacts, STATIC_ARTIFACTS[artifact_name])
        return path if path is not None and path.exists() else None
    if artifact_name.startswith("keyframe:"):
        return _child_artifact(result.artifacts.keyframes_dir, artifact_name.removeprefix("keyframe:"))
    if artifact_name.startswith("llm_frame:") and result.artifacts.llm_frames_dir:
        return _child_artifact(result.artifacts.llm_frames_dir, artifact_name.removeprefix("llm_frame:"))
    return None


def _child_artifact(directory: Path, filename: str) -> Path | None:
    if not filename or Path(filename).name != filename:
        return None
    candidate = directory / filename
    try:
        candidate.resolve().relative_to(directory.resolve())
    except (OSError, ValueError):
        return None
    return candidate if candidate.exists() and candidate.is_file() else None

