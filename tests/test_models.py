from __future__ import annotations

from pathlib import Path

from analysis.models import (
    AnalysisArtifacts,
    AnalysisResult,
    LLMPriority,
    MetricSet,
    SwingPhase,
    VideoMetadata,
)


def test_analysis_result_serializes_paths_to_json():
    result = AnalysisResult(
        metadata=VideoMetadata(
            source_path="input.mp4",
            fps=30,
            frame_count=1,
            width=640,
            height=480,
            duration_seconds=0.033,
        ),
        landmarks=[],
        phases=[
            SwingPhase(
                name="Address",
                frame_index=0,
                timestamp_seconds=0.0,
                confidence=0.1,
                detection_method="test",
            )
        ],
        metrics=MetricSet(),
        artifacts=AnalysisArtifacts(
            output_dir=Path("outputs/swing_test"),
            original_video=Path("outputs/swing_test/original.mp4"),
            annotated_video=Path("outputs/swing_test/annotated.mp4"),
            landmarks_json=Path("outputs/swing_test/landmarks.json"),
            metrics_json=Path("outputs/swing_test/metrics.json"),
            phases_json=Path("outputs/swing_test/phases.json"),
            keyframes_dir=Path("outputs/swing_test/keyframes"),
        ),
    )

    json_payload = result.model_dump_json()

    assert "outputs/swing_test/annotated.mp4" in json_payload
    assert "Address" in json_payload


def test_llm_priority_accepts_legacy_payload_without_drilldown_fields():
    priority = LLMPriority.model_validate(
        {
            "title": "Improve transition control",
            "rationale": "The transition frames suggest a quick change of direction.",
            "practice_cue": "Rehearse a brief pause at the top before starting down.",
            "supporting_frame_ids": ["frame_000020"],
            "related_metric_keys": ["tempo_ratio"],
            "confidence": 0.76,
            "support_type": "ai_generated",
        }
    )

    assert priority.explanation is None
    assert priority.drills == []
    assert priority.practice_plan == []
