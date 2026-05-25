from __future__ import annotations

from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, Field


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


class AnalysisArtifacts(BaseModel):
    output_dir: Path
    original_video: Path
    annotated_video: Path
    landmarks_json: Path
    metrics_json: Path
    phases_json: Path
    keyframes_dir: Path
    assessment_json: Path | None = None


class AnalysisResult(BaseModel):
    metadata: VideoMetadata
    landmarks: list[LandmarkFrame]
    phases: list[SwingPhase]
    metrics: MetricSet
    artifacts: AnalysisArtifacts
    assessment: SwingAssessment | None = None
