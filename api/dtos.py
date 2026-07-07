from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, Field

from analysis.llm_assessment import (
    llm_assessment_eligibility_issue,
    llm_assessment_is_current,
)
from analysis.models import AnalysisContext, AnalysisResult


JobStatus = Literal["queued", "processing", "completed", "failed"]


class AnalysisStatusResponse(BaseModel):
    run_id: str
    status: JobStatus
    progress: float = Field(ge=0.0, le=1.0)
    message: str
    created_at: str
    updated_at: str
    error: str | None = None


class AnalysisCreateResponse(BaseModel):
    run_id: str
    status_url: str
    result_url: str
    status: AnalysisStatusResponse


class PhaseConfirmationRequest(BaseModel):
    frame_indices: list[int] = Field(min_length=9, max_length=9)


class AnalysisSummaryResponse(BaseModel):
    run_id: str
    created_at: str | None = None
    context: AnalysisContext | None = None
    metadata: dict[str, Any] | None = None
    quality_flags: dict[str, Any] = Field(default_factory=dict)
    artifact_urls: dict[str, str] = Field(default_factory=dict)
    llm_assessment_current: bool = False
    llm_assessment_stale: bool = False


class AnalysisResultResponse(AnalysisSummaryResponse):
    status: Literal["completed"] = "completed"
    phases: list[dict[str, Any]] = Field(default_factory=list)
    metrics_summary: dict[str, Any] = Field(default_factory=dict)
    assessment: dict[str, Any] | None = None
    llm_assessment: dict[str, Any] | None = None
    llm_assessment_eligibility_issue: str | None = None


def run_id_from_dir(output_dir: Path) -> str:
    return Path(output_dir).name


def result_to_summary(result: AnalysisResult) -> AnalysisSummaryResponse:
    return AnalysisSummaryResponse(
        run_id=run_id_from_dir(result.artifacts.output_dir),
        created_at=_created_at(result.artifacts.output_dir),
        context=_context(result),
        metadata=result.metadata.model_dump(mode="json"),
        quality_flags=_quality_flags(result),
        artifact_urls=_artifact_urls(result),
        llm_assessment_current=llm_assessment_is_current(result),
        llm_assessment_stale=_llm_stale(result),
    )


def result_to_response(result: AnalysisResult) -> AnalysisResultResponse:
    summary = result_to_summary(result)
    eligibility_issue = llm_assessment_eligibility_issue(result)
    return AnalysisResultResponse(
        **summary.model_dump(mode="json"),
        phases=[phase.model_dump(mode="json") for phase in result.phases],
        metrics_summary=_metrics_summary(result),
        assessment=(
            result.assessment.model_dump(mode="json")
            if result.assessment is not None
            else None
        ),
        llm_assessment=(
            result.llm_assessment.model_dump(mode="json")
            if result.llm_assessment is not None
            else None
        ),
        llm_assessment_eligibility_issue=eligibility_issue,
    )


def _context(result: AnalysisResult) -> AnalysisContext | None:
    if result.assessment is None:
        return None
    return result.assessment.context


def _metrics_summary(result: AnalysisResult) -> dict[str, Any]:
    return {
        key: metric.model_dump(mode="json")
        for key, metric in result.metrics.metrics.items()
        if key != "wrist_path_trajectory"
    }


def _quality_flags(result: AnalysisResult) -> dict[str, Any]:
    quality = dict(result.metrics.quality)
    quality["has_contextual_assessment"] = result.assessment is not None
    quality["has_llm_assessment"] = result.llm_assessment is not None
    quality["llm_assessment_current"] = llm_assessment_is_current(result)
    quality["llm_assessment_stale"] = _llm_stale(result)
    return quality


def _llm_stale(result: AnalysisResult) -> bool:
    return result.llm_assessment is not None and not llm_assessment_is_current(result)


def _artifact_urls(result: AnalysisResult) -> dict[str, str]:
    run_id = run_id_from_dir(result.artifacts.output_dir)
    base = f"/analyses/{run_id}/artifacts"
    urls = {
        "original_video": f"{base}/original_video",
        "annotated_video": f"{base}/annotated_video",
        "landmarks_json": f"{base}/landmarks_json",
        "metrics_json": f"{base}/metrics_json",
        "phases_json": f"{base}/phases_json",
    }
    if result.artifacts.assessment_json and result.artifacts.assessment_json.exists():
        urls["assessment_json"] = f"{base}/assessment_json"
    if (
        result.artifacts.llm_assessment_json
        and result.artifacts.llm_assessment_json.exists()
    ):
        urls["llm_assessment_json"] = f"{base}/llm_assessment_json"
    urls.update(
        {
            f"keyframe:{path.name}": f"{base}/keyframe:{path.name}"
            for path in sorted(result.artifacts.keyframes_dir.glob("*"))
            if path.is_file()
        }
    )
    if result.artifacts.llm_frames_dir and result.artifacts.llm_frames_dir.exists():
        urls.update(
            {
                f"llm_frame:{path.name}": f"{base}/llm_frame:{path.name}"
                for path in sorted(result.artifacts.llm_frames_dir.glob("*"))
                if path.is_file()
            }
        )
    return urls


def _created_at(output_dir: Path) -> str | None:
    try:
        return datetime.fromtimestamp(
            output_dir.stat().st_mtime, tz=timezone.utc
        ).isoformat()
    except OSError:
        return None

