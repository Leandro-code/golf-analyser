from __future__ import annotations

import json
from pathlib import Path

from analysis.models import (
    AnalysisContext,
    ImprovementFinding,
    MetricSet,
    SwingAssessment,
    SwingPhase,
    TechniqueReference,
)


RUBRIC_PATH = Path(__file__).with_name("rubric.v1.json")
MIN_POSE_DETECTION_RATE = 0.65
MIN_PHASE_CONFIDENCE = 0.5


def assess_swing(
    metrics: MetricSet,
    phases: list[SwingPhase],
    context: AnalysisContext,
) -> SwingAssessment:
    version, references = _load_rubric()
    phase_by_name = {phase.name: phase for phase in phases}
    quality_limitations: list[str] = []
    pose_rate = float(metrics.quality.get("pose_detection_rate", 0.0))
    if pose_rate < MIN_POSE_DETECTION_RATE:
        quality_limitations.append(
            "Pose detection coverage is too low for reliable technique guidance."
        )
    quality_limitations.extend(metrics.quality.get("phase_quality_issues", []))

    applicable = [
        reference
        for reference in references
        if context.camera_view in reference.applicable_views
        and context.club_family in reference.applicable_clubs
    ]
    skipped = [
        reference.name
        for reference in references
        if reference not in applicable
    ]
    findings = [
        _evaluate_reference(
            reference,
            metrics,
            phase_by_name,
            pose_rate,
            quality_limitations,
        )
        for reference in applicable
    ]
    findings.sort(
        key=lambda finding: (
            {"needs_attention": 0, "insufficient_data": 1, "within_reference": 2}[
                finding.status
            ],
            finding.reference.priority,
        )
    )
    return SwingAssessment(
        rubric_version=version,
        context=context,
        findings=findings,
        skipped_checks=sorted(set(skipped)),
        quality_limitations=quality_limitations,
    )


def _evaluate_reference(
    reference: TechniqueReference,
    metrics: MetricSet,
    phases: dict[str, SwingPhase],
    pose_rate: float,
    quality_limitations: list[str],
) -> ImprovementFinding:
    expected = _expected_text(reference)
    metric = metrics.metrics.get(reference.metric_key)
    phase = phases.get(reference.phase_name) if reference.phase_name else None
    observed = metric.value if metric is not None else None
    observed_value = float(observed) if isinstance(observed, (int, float)) else None

    insufficient_reason = None
    if quality_limitations:
        insufficient_reason = quality_limitations[0]
    elif metric is None or observed_value is None:
        insufficient_reason = "Required pose metric was not available for this checkpoint."
    elif reference.phase_name and (
        phase is None or phase.confidence < MIN_PHASE_CONFIDENCE
    ):
        insufficient_reason = "The supporting swing phase was not detected confidently."

    frame_index = (
        metric.frame_index
        if metric is not None and metric.frame_index is not None
        else phase.frame_index if phase else None
    )
    confidence = min(
        pose_rate,
        phase.confidence if phase is not None else pose_rate,
    )
    evidence_keyframe = (
        f"finding_{reference.id}.jpg" if frame_index is not None else None
    )
    if insufficient_reason:
        return ImprovementFinding(
            reference=reference,
            status="insufficient_data",
            observed_value=observed_value,
            expected=expected,
            phase_name=reference.phase_name,
            frame_index=frame_index,
            confidence=round(confidence, 3),
            evidence_keyframe=evidence_keyframe,
            note=insufficient_reason,
        )

    within_range = (
        (reference.target_min is None or observed_value >= reference.target_min)
        and (reference.target_max is None or observed_value <= reference.target_max)
    )
    return ImprovementFinding(
        reference=reference,
        status="within_reference" if within_range else "needs_attention",
        observed_value=round(observed_value, 3),
        expected=expected,
        phase_name=reference.phase_name,
        frame_index=frame_index,
        confidence=round(confidence, 3),
        evidence_keyframe=evidence_keyframe,
    )


def _expected_text(reference: TechniqueReference) -> str:
    if reference.target_min is not None and reference.target_max is not None:
        return f"{reference.target_min:g}-{reference.target_max:g} {reference.unit}"
    if reference.target_min is not None:
        return f"at least {reference.target_min:g} {reference.unit}"
    return f"no more than {reference.target_max:g} {reference.unit}"


def _load_rubric() -> tuple[str, list[TechniqueReference]]:
    with RUBRIC_PATH.open(encoding="utf-8") as file:
        payload = json.load(file)
    return payload["version"], [
        TechniqueReference.model_validate(reference)
        for reference in payload["references"]
    ]
