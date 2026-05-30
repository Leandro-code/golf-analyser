from __future__ import annotations

from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator


class VideoMetadata(BaseModel):
    source_path: str
    fps: float
    frame_count: int
    width: int
    height: int
    duration_seconds: float


class LandmarkPoint(BaseModel):
    name: str
    x: float
    y: float
    z: float | None = None
    visibility: float | None = None
    pixel_x: float | None = None
    pixel_y: float | None = None


class LandmarkFrame(BaseModel):
    frame_index: int
    timestamp_seconds: float
    pose_detected: bool
    landmarks: list[LandmarkPoint] = Field(default_factory=list)


class SwingPhase(BaseModel):
    name: str
    frame_index: int
    timestamp_seconds: float
    confidence: float = 0.0
    detection_method: str


class MetricValue(BaseModel):
    name: str
    value: Any
    unit: str | None = None
    description: str
    frame_index: int | None = None


class MetricSet(BaseModel):
    metrics: dict[str, MetricValue] = Field(default_factory=dict)
    quality: dict[str, Any] = Field(default_factory=dict)


class AnalysisContext(BaseModel):
    handedness: Literal["right", "left"]
    camera_view: Literal["face_on", "down_the_line"]
    club_family: Literal["driver", "wood_or_hybrid", "iron", "wedge"]
    swing_type: Literal["full_swing"] = "full_swing"


class TechniqueReference(BaseModel):
    id: str
    metric_key: str
    name: str
    applicable_views: list[str]
    applicable_clubs: list[str]
    phase_name: str | None = None
    target_min: float | None = None
    target_max: float | None = None
    unit: str
    rationale: str
    correction_cue: str
    priority: int
    source_title: str
    source_url: str
    source_note: str


class ImprovementFinding(BaseModel):
    reference: TechniqueReference
    status: Literal["needs_attention", "within_reference", "insufficient_data"]
    observed_value: float | None = None
    expected: str
    phase_name: str | None = None
    frame_index: int | None = None
    confidence: float = 0.0
    evidence_keyframe: str | None = None
    note: str | None = None


class SwingAssessment(BaseModel):
    rubric_version: str
    context: AnalysisContext
    findings: list[ImprovementFinding] = Field(default_factory=list)
    skipped_checks: list[str] = Field(default_factory=list)
    quality_limitations: list[str] = Field(default_factory=list)


class AIReviewObservation(BaseModel):
    phase_name: str
    observation: str
    evidence_visible: str
    confidence: float = Field(ge=0.0, le=1.0)
    related_metric_key: str | None = None


class AIReviewPriority(BaseModel):
    focus: str
    practice_cue: str
    supporting_phases: list[str] = Field(default_factory=list)


class AIReviewContent(BaseModel):
    summary: str
    observations: list[AIReviewObservation] = Field(default_factory=list)
    priorities: list[AIReviewPriority] = Field(default_factory=list, max_length=3)
    limitations: list[str] = Field(default_factory=list)


class AIVisualReview(BaseModel):
    schema_version: str
    prompt_version: str
    model: str
    generated_at: str
    context: AnalysisContext
    reviewed_phases: list[SwingPhase] = Field(default_factory=list)
    quality_snapshot: dict[str, Any] = Field(default_factory=dict)
    evidence_fingerprint: str
    content: AIReviewContent


class SubmittedEvidenceFrame(BaseModel):
    frame_id: str
    frame_index: int
    timestamp_seconds: float
    phase_relations: list[str] = Field(default_factory=list)
    image_file: str


class LLMObservation(BaseModel):
    title: str
    observation: str
    supporting_frame_ids: list[str] = Field(default_factory=list)
    related_metric_keys: list[str] = Field(default_factory=list)
    confidence: float = Field(ge=0.0, le=1.0)


class LLMPriority(BaseModel):
    title: str
    rationale: str
    practice_cue: str
    supporting_frame_ids: list[str] = Field(default_factory=list)
    related_metric_keys: list[str] = Field(default_factory=list)
    confidence: float = Field(ge=0.0, le=1.0)
    support_type: Literal["ai_generated"] = "ai_generated"

    @field_validator("support_type", mode="before")
    @classmethod
    def _normalize_legacy_support_type(cls, value: object) -> str:
        # Older saved LLM assessments used sourced_reference before the primary
        # assessment stopped sending deterministic reference ranges.
        if value == "sourced_reference":
            return "ai_generated"
        return "ai_generated" if value is None else str(value)


class LLMAssessmentContent(BaseModel):
    overview: str
    strengths: list[str] = Field(default_factory=list)
    observations: list[LLMObservation] = Field(default_factory=list)
    priorities: list[LLMPriority] = Field(default_factory=list, max_length=3)
    limitations: list[str] = Field(default_factory=list)


class LLMAssessment(BaseModel):
    schema_version: str
    prompt_version: str
    model: str
    generated_at: str
    context: AnalysisContext
    submitted_frames: list[SubmittedEvidenceFrame] = Field(default_factory=list)
    quality_snapshot: dict[str, Any] = Field(default_factory=dict)
    evidence_fingerprint: str
    content: LLMAssessmentContent


class AnalysisArtifacts(BaseModel):
    output_dir: Path
    original_video: Path
    annotated_video: Path
    landmarks_json: Path
    metrics_json: Path
    phases_json: Path
    keyframes_dir: Path
    assessment_json: Path | None = None
    ai_review_json: Path | None = None
    llm_assessment_json: Path | None = None
    llm_frames_dir: Path | None = None


class AnalysisResult(BaseModel):
    metadata: VideoMetadata
    landmarks: list[LandmarkFrame]
    phases: list[SwingPhase]
    metrics: MetricSet
    artifacts: AnalysisArtifacts
    assessment: SwingAssessment | None = None
    ai_review: AIVisualReview | None = None
    llm_assessment: LLMAssessment | None = None
