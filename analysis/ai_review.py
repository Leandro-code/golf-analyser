from __future__ import annotations

import base64
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from openai import OpenAI

from analysis.models import AIReviewContent, AIVisualReview, AnalysisResult, SwingPhase


SCHEMA_VERSION = "1.0.0"
PROMPT_VERSION = "1.0.0"
DEFAULT_MODEL = "gpt-5.5"
ANCHOR_PHASES = [
    "Address",
    "Top of backswing",
    "Impact approximation",
    "Finish",
]
SUPERSEDED_PHASE_METHODS = {
    "minimum_wrist_y_coordinate",
    "closest_wrist_return_to_address_after_top",
    "detected_active_swing_onset",
}

INSTRUCTIONS = """
You are providing a supplementary visual review of a golf swing from annotated
2D still images and deterministic pose metrics. Use only what is visible in the
provided labelled images and measured context. Tie every observation to a named
phase image and do not override, invent, or recalculate numeric measurements.

Do not claim knowledge of club path, clubface angle, strike/contact quality,
ball flight, distance, power, overall score, medical diagnosis, or comparison
to a professional golfer. Describe uncertainty where still images or 2D pose
proxies are insufficient. Offer at most three concise practice cues, each
supported by a visible observation.
""".strip()


class AIReviewError(RuntimeError):
    pass


def generate_ai_review(
    result: AnalysisResult,
    client: Any | None = None,
    model: str | None = None,
) -> AnalysisResult:
    issue = ai_review_eligibility_issue(result)
    if issue is not None:
        raise AIReviewError(issue)
    if result.assessment is None or result.artifacts.ai_review_json is None:
        raise AIReviewError("This analysis does not support saved AI visual review.")

    selected_phases = _selected_phases(result)
    payload = _review_context(result, selected_phases)
    content: list[dict[str, Any]] = [
        {
            "type": "input_text",
            "text": (
                "Review this swing using the labelled phase images and measured "
                "context below. Measurements are deterministic inputs, not values "
                "to estimate from the images.\n\n"
                + json.dumps(payload, indent=2, sort_keys=True)
            ),
        }
    ]
    for phase in selected_phases:
        content.append(
            {
                "type": "input_text",
                "text": (
                    f"Phase image: {phase.name}; frame {phase.frame_index}; "
                    f"time {phase.timestamp_seconds:.3f}s."
                ),
            }
        )
        content.append(
            {
                "type": "input_image",
                "image_url": _data_url(_keyframe_path(result, phase.name)),
                "detail": "high",
            }
        )

    requested_model = model or os.environ.get(
        "GOLF_ANALYSER_OPENAI_MODEL", DEFAULT_MODEL
    )
    api_client = client
    if api_client is None:
        if not os.environ.get("OPENAI_API_KEY"):
            raise AIReviewError(
                "Set OPENAI_API_KEY in the local .env file before generating an AI review."
            )
        api_client = OpenAI()

    try:
        response = api_client.responses.parse(
            model=requested_model,
            instructions=INSTRUCTIONS,
            input=[{"role": "user", "content": content}],
            text_format=AIReviewContent,
            store=False,
        )
        review_content = response.output_parsed
    except Exception as exc:
        raise AIReviewError(f"OpenAI visual review request failed: {exc}") from exc
    if review_content is None:
        raise AIReviewError("OpenAI did not return a structured visual review.")

    review = AIVisualReview(
        schema_version=SCHEMA_VERSION,
        prompt_version=PROMPT_VERSION,
        model=requested_model,
        generated_at=datetime.now(timezone.utc).isoformat(),
        context=result.assessment.context,
        reviewed_phases=selected_phases,
        quality_snapshot=_quality_snapshot(result),
        evidence_fingerprint=ai_review_fingerprint(result),
        content=review_content,
    )
    _write_json(result.artifacts.ai_review_json, review.model_dump(mode="json"))
    return result.model_copy(update={"ai_review": review})


def ai_review_eligibility_issue(result: AnalysisResult) -> str | None:
    if result.assessment is None:
        return "AI visual review requires a contextual analysis."
    if not result.metrics.quality.get("phase_scoped_metrics", False) or any(
        phase.detection_method in SUPERSEDED_PHASE_METHODS for phase in result.phases
    ):
        return "Regenerate phase timing before requesting AI visual review."
    if result.assessment.quality_limitations:
        return (
            "AI visual review is withheld until pose evidence and phase timing "
            "are reliable."
        )
    phase_names = {phase.name for phase in result.phases}
    missing_phases = [
        phase_name
        for phase_name in _selected_phase_names(result)
        if phase_name not in phase_names
    ]
    if missing_phases:
        return "Required review phase images are unavailable for this analysis."
    missing_images = [
        _keyframe_path(result, phase_name).name
        for phase_name in _selected_phase_names(result)
        if not _keyframe_path(result, phase_name).exists()
    ]
    if missing_images:
        return "Required review phase images are unavailable for this analysis."
    return None


def ai_review_is_current(result: AnalysisResult) -> bool:
    return (
        result.ai_review is not None
        and ai_review_eligibility_issue(result) is None
        and result.ai_review.evidence_fingerprint == ai_review_fingerprint(result)
    )


def ai_review_fingerprint(result: AnalysisResult) -> str:
    selected_names = _selected_phase_names(result)
    selected_phases = [
        phase.model_dump(mode="json")
        for phase in result.phases
        if phase.name in selected_names
    ]
    applicable_metrics = []
    if result.assessment is not None:
        for finding in result.assessment.findings:
            metric = result.metrics.metrics.get(finding.reference.metric_key)
            applicable_metrics.append(
                {
                    "reference_id": finding.reference.id,
                    "metric_key": finding.reference.metric_key,
                    "value": metric.value if metric is not None else None,
                    "frame_index": metric.frame_index if metric is not None else None,
                }
            )
    payload = {
        "context": (
            result.assessment.context.model_dump(mode="json")
            if result.assessment is not None
            else None
        ),
        "selected_phase_names": selected_names,
        "phases": selected_phases,
        "metrics": applicable_metrics,
        "quality": _quality_snapshot(result),
    }
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _selected_phases(result: AnalysisResult) -> list[SwingPhase]:
    by_name = {phase.name: phase for phase in result.phases}
    return [by_name[name] for name in _selected_phase_names(result) if name in by_name]


def _selected_phase_names(result: AnalysisResult) -> list[str]:
    names = list(ANCHOR_PHASES)
    if result.assessment and any(
        finding.reference.phase_name == "Downswing"
        for finding in result.assessment.findings
    ):
        names.insert(3, "Downswing")
    return names


def _review_context(
    result: AnalysisResult, selected_phases: list[SwingPhase]
) -> dict[str, Any]:
    assessment = result.assessment
    if assessment is None:
        raise AIReviewError("AI visual review requires a contextual analysis.")
    return {
        "context": assessment.context.model_dump(mode="json"),
        "quality": _quality_snapshot(result),
        "phases": [
            {
                "name": phase.name,
                "frame_index": phase.frame_index,
                "timestamp_seconds": phase.timestamp_seconds,
                "confidence": phase.confidence,
                "detection_method": phase.detection_method,
            }
            for phase in selected_phases
        ],
        "measured_findings": [
            {
                "metric_key": finding.reference.metric_key,
                "checkpoint": finding.reference.name,
                "status": finding.status,
                "observed_value": finding.observed_value,
                "unit": finding.reference.unit,
                "expected": finding.expected,
                "phase_name": finding.phase_name,
                "confidence": finding.confidence,
                "note": finding.note,
            }
            for finding in assessment.findings
        ],
    }


def _quality_snapshot(result: AnalysisResult) -> dict[str, Any]:
    keys = [
        "pose_detection_rate",
        "frames_with_pose",
        "frames_total",
        "phase_scoped_metrics",
        "phase_markers_confirmed",
        "phase_quality_issues",
    ]
    return {key: result.metrics.quality.get(key) for key in keys if key in result.metrics.quality}


def _keyframe_path(result: AnalysisResult, phase_name: str) -> Path:
    return result.artifacts.keyframes_dir / f"{_safe_phase_filename(phase_name)}.jpg"


def _safe_phase_filename(name: str) -> str:
    return name.lower().replace(" ", "_").replace("-", "_")


def _data_url(path: Path) -> str:
    encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    return f"data:image/jpeg;base64,{encoded}"


def _write_json(path: Path, payload: object) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
