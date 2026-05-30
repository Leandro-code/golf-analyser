from __future__ import annotations

import json
from pathlib import Path

from analysis.models import (
    AnalysisArtifacts,
    AnalysisResult,
    AIVisualReview,
    LLMAssessment,
    LandmarkFrame,
    MetricSet,
    SwingAssessment,
    SwingPhase,
    VideoMetadata,
)


def list_analysis_runs(outputs_dir: Path) -> list[Path]:
    outputs_dir = Path(outputs_dir)
    if not outputs_dir.exists():
        return []
    return sorted(
        (
            directory
            for directory in outputs_dir.iterdir()
            if directory.is_dir() and _is_complete_run(directory)
        ),
        key=lambda directory: directory.stat().st_mtime,
        reverse=True,
    )


def load_analysis_result(output_dir: Path) -> AnalysisResult:
    output_dir = Path(output_dir)
    artifacts = _artifacts(output_dir)
    missing = [
        path.name
        for path in (
            artifacts.original_video,
            artifacts.annotated_video,
            artifacts.landmarks_json,
            artifacts.metrics_json,
            artifacts.phases_json,
        )
        if not path.exists()
    ]
    if missing:
        raise FileNotFoundError(
            f"Incomplete analysis run at {output_dir}; missing: {', '.join(missing)}"
        )

    landmarks_payload = _read_json(artifacts.landmarks_json)
    metrics_payload = _read_json(artifacts.metrics_json)
    phases_payload = _read_json(artifacts.phases_json)
    assessment = (
        SwingAssessment.model_validate(_read_json(artifacts.assessment_json))
        if artifacts.assessment_json and artifacts.assessment_json.exists()
        else None
    )
    ai_review = (
        AIVisualReview.model_validate(_read_json(artifacts.ai_review_json))
        if artifacts.ai_review_json and artifacts.ai_review_json.exists()
        else None
    )
    llm_assessment = (
        LLMAssessment.model_validate(_read_json(artifacts.llm_assessment_json))
        if artifacts.llm_assessment_json and artifacts.llm_assessment_json.exists()
        else None
    )

    return AnalysisResult(
        metadata=VideoMetadata.model_validate(landmarks_payload["metadata"]),
        landmarks=[
            LandmarkFrame.model_validate(frame)
            for frame in landmarks_payload.get("frames", [])
        ],
        phases=[
            SwingPhase.model_validate(phase)
            for phase in phases_payload.get("phases", [])
        ],
        metrics=MetricSet.model_validate(metrics_payload),
        artifacts=artifacts,
        assessment=assessment,
        ai_review=ai_review,
        llm_assessment=llm_assessment,
    )


def _artifacts(output_dir: Path) -> AnalysisArtifacts:
    return AnalysisArtifacts(
        output_dir=output_dir,
        original_video=output_dir / "original.mp4",
        annotated_video=output_dir / "annotated.mp4",
        landmarks_json=output_dir / "landmarks.json",
        metrics_json=output_dir / "metrics.json",
        phases_json=output_dir / "phases.json",
        keyframes_dir=output_dir / "keyframes",
        assessment_json=(
            output_dir / "assessment.json"
            if (output_dir / "assessment.json").exists()
            else None
        ),
        ai_review_json=(
            output_dir / "ai_review.json"
            if (output_dir / "assessment.json").exists()
            or (output_dir / "ai_review.json").exists()
            else None
        ),
        llm_assessment_json=(
            output_dir / "llm_assessment.json"
            if (output_dir / "assessment.json").exists()
            or (output_dir / "llm_assessment.json").exists()
            else None
        ),
        llm_frames_dir=(
            output_dir / "llm_frames"
            if (output_dir / "assessment.json").exists()
            or (output_dir / "llm_frames").exists()
            else None
        ),
    )


def _is_complete_run(output_dir: Path) -> bool:
    artifacts = _artifacts(output_dir)
    return all(
        path.exists()
        for path in (
            artifacts.original_video,
            artifacts.annotated_video,
            artifacts.landmarks_json,
            artifacts.metrics_json,
            artifacts.phases_json,
        )
    )


def _read_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as file:
        return json.load(file)
