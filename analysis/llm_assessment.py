from __future__ import annotations

import base64
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import cv2
from openai import OpenAI

from analysis.models import (
    AnalysisResult,
    LLMAssessment,
    LLMAssessmentContent,
    SubmittedEvidenceFrame,
)
from analysis.visualise import annotate_frame


SCHEMA_VERSION = "1.0.0"
PROMPT_VERSION = "1.0.0"
DEFAULT_MODEL = "gpt-5.5"
NEIGHBOR_SECONDS = 0.1
SUPERSEDED_PHASE_METHODS = {
    "minimum_wrist_y_coordinate",
    "closest_wrist_return_to_address_after_top",
    "detected_active_swing_onset",
}

INSTRUCTIONS = """
You are the primary AI swing assessment writer for a golf swing review workbench.
You receive still images selected from an ordered full-swing replay,
capture context, deterministic 2D pose measurements, and local quality metadata.

Ground every observation and recommendation in supplied frame IDs and/or metrics.
Treat supplied measurements as facts; do not recalculate or contradict them from
the images. Prioritise up to three useful practice actions. Do not compare against
target ranges, hidden standards, or normative thresholds. Use support_type
ai_generated for every priority because no source-backed reference ranges are
provided.

Limit analysis to visible 2D pose observations. Do not claim club path, clubface
angle, strike/contact quality, ball flight, distance, power, overall score,
medical diagnosis, or comparison to a professional golfer. Include limitations
when images or 2D pose evidence cannot support a conclusion.
""".strip()


class LLMAssessmentError(RuntimeError):
    pass


def generate_llm_assessment(
    result: AnalysisResult,
    client: Any | None = None,
    model: str | None = None,
) -> AnalysisResult:
    issue = llm_assessment_eligibility_issue(result)
    if issue is not None:
        raise LLMAssessmentError(issue)
    if (
        result.artifacts.llm_assessment_json is None
        or result.artifacts.llm_frames_dir is None
    ):
        raise LLMAssessmentError("This analysis does not support saved AI assessment.")

    submitted_frames = _select_frames(result)
    _export_evidence_frames(result, submitted_frames)
    payload = _assessment_context(result, submitted_frames)
    content: list[dict[str, Any]] = [
        {
            "type": "input_text",
            "text": (
                "Produce the primary swing assessment from the supplied evidence "
                "packet. Use only frame IDs supplied below when citing visual support.\n\n"
                + json.dumps(payload, indent=2, sort_keys=True)
            ),
        }
    ]
    for evidence_frame in submitted_frames:
        content.append(
            {
                "type": "input_text",
                "text": (
                    f"Evidence frame {evidence_frame.frame_id}: video frame "
                    f"{evidence_frame.frame_index}, {evidence_frame.timestamp_seconds:.3f}s; "
                    f"relations: {', '.join(evidence_frame.phase_relations)}."
                ),
            }
        )
        content.append(
            {
                "type": "input_image",
                "image_url": _data_url(
                    result.artifacts.llm_frames_dir / evidence_frame.image_file
                ),
                "detail": "high",
            }
        )

    requested_model = model or os.environ.get(
        "GOLF_ANALYSER_OPENAI_MODEL", DEFAULT_MODEL
    )
    api_client = client
    if api_client is None:
        if not os.environ.get("OPENAI_API_KEY"):
            raise LLMAssessmentError(
                "Set OPENAI_API_KEY in the local .env file before generating an AI assessment."
            )
        api_client = OpenAI()

    try:
        response = api_client.responses.parse(
            model=requested_model,
            instructions=INSTRUCTIONS,
            input=[{"role": "user", "content": content}],
            text_format=LLMAssessmentContent,
            store=False,
        )
        assessment_content = response.output_parsed
    except Exception as exc:
        raise LLMAssessmentError(f"OpenAI swing assessment request failed: {exc}") from exc
    if assessment_content is None:
        raise LLMAssessmentError("OpenAI did not return a structured swing assessment.")
    _validate_frame_references(assessment_content, submitted_frames)

    assessment = LLMAssessment(
        schema_version=SCHEMA_VERSION,
        prompt_version=PROMPT_VERSION,
        model=requested_model,
        generated_at=datetime.now(timezone.utc).isoformat(),
        context=_context(result),
        submitted_frames=submitted_frames,
        quality_snapshot=_quality_snapshot(result),
        evidence_fingerprint=llm_assessment_fingerprint(result, submitted_frames),
        content=assessment_content,
    )
    _write_json(
        result.artifacts.llm_assessment_json,
        assessment.model_dump(mode="json"),
    )
    return result.model_copy(update={"llm_assessment": assessment})


def llm_assessment_eligibility_issue(result: AnalysisResult) -> str | None:
    if result.assessment is None:
        return "AI swing assessment requires a contextual analysis."
    if not result.metrics.quality.get("phase_scoped_metrics", False) or any(
        phase.detection_method in SUPERSEDED_PHASE_METHODS for phase in result.phases
    ):
        return "Regenerate phase timing before requesting an AI swing assessment."
    quality_issues = _quality_issues(result)
    if quality_issues:
        return (
            "AI swing assessment is withheld until pose evidence and phase timing "
            "are reliable."
        )
    by_name = {phase.name for phase in result.phases}
    if any(name not in by_name for name in _review_phase_names(result)):
        return "Required review phase markers are unavailable for this analysis."
    if not result.artifacts.original_video.exists():
        return "The original replay required for AI assessment is unavailable."
    return None


def llm_assessment_is_current(result: AnalysisResult) -> bool:
    return (
        result.llm_assessment is not None
        and llm_assessment_eligibility_issue(result) is None
        and result.llm_assessment.evidence_fingerprint
        == llm_assessment_fingerprint(result)
    )


def llm_assessment_fingerprint(
    result: AnalysisResult,
    submitted_frames: list[SubmittedEvidenceFrame] | None = None,
) -> str:
    selected = submitted_frames or _select_frames(result)
    payload = {
        "context": _context(result).model_dump(mode="json") if result.assessment else None,
        "phases": [phase.model_dump(mode="json") for phase in result.phases],
        "submitted_frames": [frame.model_dump(mode="json") for frame in selected],
        "metrics": _measurements(result),
        "quality": _quality_snapshot(result),
    }
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _select_frames(result: AnalysisResult) -> list[SubmittedEvidenceFrame]:
    by_name = {phase.name: phase for phase in result.phases}
    address = by_name["Address"].frame_index
    finish = by_name["Finish"].frame_index
    neighbor_frames = max(1, round(result.metadata.fps * NEIGHBOR_SECONDS))
    relation_map: dict[int, set[str]] = {}

    def add_frame(index: int, relation: str) -> None:
        clipped = max(address, min(finish, index))
        relation_map.setdefault(clipped, set()).add(relation)

    add_frame(address, "Address anchor")
    for phase_name in _review_phase_names(result):
        if phase_name == "Address":
            continue
        phase = by_name[phase_name]
        offsets = (
            [-neighbor_frames, 0, neighbor_frames]
            if phase_name in {"Top of backswing", "Impact approximation", "Downswing", "Finish"}
            else [0]
        )
        for offset in offsets:
            actual_index = max(address, min(finish, phase.frame_index + offset))
            actual_offset = (actual_index - phase.frame_index) / max(result.metadata.fps, 1e-9)
            relation = (
                f"{phase_name} anchor"
                if actual_index == phase.frame_index
                else f"{phase_name} {actual_offset:+.2f}s"
            )
            add_frame(phase.frame_index + offset, relation)

    return [
        SubmittedEvidenceFrame(
            frame_id=f"frame_{frame_index:06d}",
            frame_index=frame_index,
            timestamp_seconds=round(
                frame_index / max(result.metadata.fps, 1e-9), 4
            ),
            phase_relations=sorted(relations),
            image_file=f"frame_{frame_index:06d}.jpg",
        )
        for frame_index, relations in sorted(relation_map.items())
    ]


def _review_phase_names(result: AnalysisResult) -> list[str]:
    names = ["Address", "Top of backswing"]
    if result.assessment and result.assessment.context.camera_view == "down_the_line":
        names.append("Downswing")
    names.extend(["Impact approximation", "Finish"])
    return names


def _export_evidence_frames(
    result: AnalysisResult, submitted_frames: list[SubmittedEvidenceFrame]
) -> None:
    directory = result.artifacts.llm_frames_dir
    if directory is None:
        raise LLMAssessmentError("Evidence frame directory is unavailable.")
    directory.mkdir(parents=True, exist_ok=True)
    for existing in directory.glob("frame_*.jpg"):
        existing.unlink()
    cap = cv2.VideoCapture(str(result.artifacts.original_video))
    if not cap.isOpened():
        raise LLMAssessmentError("Unable to open original replay for AI evidence frames.")
    landmarks_by_index = {frame.frame_index: frame for frame in result.landmarks}
    try:
        for evidence_frame in submitted_frames:
            cap.set(cv2.CAP_PROP_POS_FRAMES, evidence_frame.frame_index)
            ok, frame = cap.read()
            if not ok:
                raise LLMAssessmentError(
                    f"Unable to export AI evidence frame {evidence_frame.frame_index}."
                )
            annotated = annotate_frame(
                frame,
                landmarks_by_index.get(evidence_frame.frame_index),
                ", ".join(evidence_frame.phase_relations),
            )
            if not cv2.imwrite(str(directory / evidence_frame.image_file), annotated):
                raise LLMAssessmentError(
                    f"Unable to write AI evidence frame {evidence_frame.frame_index}."
                )
    finally:
        cap.release()


def _assessment_context(
    result: AnalysisResult, submitted_frames: list[SubmittedEvidenceFrame]
) -> dict[str, Any]:
    if result.assessment is None:
        raise LLMAssessmentError("AI swing assessment requires a contextual analysis.")
    return {
        "context": _context(result).model_dump(mode="json"),
        "quality": _quality_snapshot(result),
        "phases": [phase.model_dump(mode="json") for phase in result.phases],
        "submitted_frames": [
            frame.model_dump(mode="json") for frame in submitted_frames
        ],
        "measurements": _measurements(result),
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


def _context(result: AnalysisResult):
    if result.assessment is None:
        raise LLMAssessmentError("AI swing assessment requires a contextual analysis.")
    return result.assessment.context


def _measurements(result: AnalysisResult) -> list[dict[str, Any]]:
    measurements = []
    for key, metric in result.metrics.metrics.items():
        if key == "wrist_path_trajectory":
            continue
        measurements.append(
            {
                "metric_key": key,
                "name": metric.name,
                "value": metric.value,
                "unit": metric.unit,
                "description": metric.description,
                "frame_index": metric.frame_index,
            }
        )
    return measurements


def _quality_issues(result: AnalysisResult) -> list[str]:
    issues = list(result.metrics.quality.get("phase_quality_issues", []))
    pose_rate = float(result.metrics.quality.get("pose_detection_rate", 0.0))
    if pose_rate < 0.65:
        issues.append("Pose detection coverage is too low for reliable AI assessment.")
    return issues


def _validate_frame_references(
    content: LLMAssessmentContent, submitted_frames: list[SubmittedEvidenceFrame]
) -> None:
    known_ids = {frame.frame_id for frame in submitted_frames}
    used_ids = {
        frame_id
        for observation in content.observations
        for frame_id in observation.supporting_frame_ids
    } | {
        frame_id
        for priority in content.priorities
        for frame_id in priority.supporting_frame_ids
    }
    unknown_ids = sorted(used_ids - known_ids)
    if unknown_ids:
        raise LLMAssessmentError(
            "OpenAI returned unsupported evidence frame IDs: "
            + ", ".join(unknown_ids)
        )


def _data_url(path: Path) -> str:
    encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    return f"data:image/jpeg;base64,{encoded}"


def _write_json(path: Path, payload: object) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
