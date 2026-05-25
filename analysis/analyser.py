from __future__ import annotations

import json
import shutil
from collections.abc import Callable
from pathlib import Path

import cv2

from analysis.assessment import assess_swing
from analysis.metrics import calculate_metrics
from analysis.models import (
    AnalysisContext,
    AnalysisArtifacts,
    AnalysisResult,
    LandmarkFrame,
    VideoMetadata,
)
from analysis.phases import build_confirmed_phases, detect_swing_phases
from analysis.pose import MediaPipePoseEstimator
from analysis.visualise import render_annotated_video

ProgressCallback = Callable[[str, float], None]


class SwingAnalyser:
    def analyse(
        self,
        video_path: Path,
        output_dir: Path,
        progress_callback: ProgressCallback | None = None,
        context: AnalysisContext | None = None,
    ) -> AnalysisResult:
        video_path = Path(video_path)
        output_dir = Path(output_dir)
        keyframes_dir = output_dir / "keyframes"
        output_dir.mkdir(parents=True, exist_ok=True)
        keyframes_dir.mkdir(parents=True, exist_ok=True)

        original_video = output_dir / "original.mp4"
        annotated_video = output_dir / "annotated.mp4"
        landmarks_json = output_dir / "landmarks.json"
        metrics_json = output_dir / "metrics.json"
        phases_json = output_dir / "phases.json"
        assessment_json = output_dir / "assessment.json" if context else None

        _report(progress_callback, "Copying source video", 0.05)
        if video_path.resolve() != original_video.resolve():
            shutil.copyfile(video_path, original_video)

        _report(progress_callback, "Extracting pose landmarks", 0.15)
        metadata, landmarks = self._extract_landmarks(original_video, progress_callback)

        _report(progress_callback, "Detecting swing phases", 0.7)
        phases = detect_swing_phases(landmarks, metadata.fps)

        _report(progress_callback, "Calculating metrics", 0.78)
        metrics = calculate_metrics(landmarks, phases, context)

        _report(progress_callback, "Assessing improvement areas", 0.82)
        assessment = assess_swing(metrics, phases, context) if context else None

        _report(progress_callback, "Rendering annotated replay", 0.84)
        render_annotated_video(
            original_video,
            annotated_video,
            keyframes_dir,
            landmarks,
            phases,
            assessment,
        )

        artifacts = AnalysisArtifacts(
            output_dir=output_dir,
            original_video=original_video,
            annotated_video=annotated_video,
            landmarks_json=landmarks_json,
            metrics_json=metrics_json,
            phases_json=phases_json,
            keyframes_dir=keyframes_dir,
            assessment_json=assessment_json,
        )
        result = AnalysisResult(
            metadata=metadata,
            landmarks=landmarks,
            phases=phases,
            metrics=metrics,
            artifacts=artifacts,
            assessment=assessment,
        )

        _report(progress_callback, "Writing JSON outputs", 0.95)
        _write_json(
            landmarks_json,
            {
                "metadata": metadata.model_dump(mode="json"),
                "frames": [frame.model_dump(mode="json") for frame in landmarks],
            },
        )
        _write_json(
            metrics_json,
            metrics.model_dump(mode="json"),
        )
        _write_json(
            phases_json,
            {"phases": [phase.model_dump(mode="json") for phase in phases]},
        )
        if assessment_json is not None and assessment is not None:
            _write_json(assessment_json, assessment.model_dump(mode="json"))

        _report(progress_callback, "Complete", 1.0)
        return result

    def confirm_phase_markers(
        self,
        result: AnalysisResult,
        address_index: int,
        top_index: int,
        impact_index: int,
        finish_index: int,
    ) -> AnalysisResult:
        phases = build_confirmed_phases(
            address_index,
            top_index,
            impact_index,
            finish_index,
            result.metadata.fps,
        )
        return self._regenerate_from_phases(result, phases)

    def redetect_phases(self, result: AnalysisResult) -> AnalysisResult:
        phases = detect_swing_phases(result.landmarks, result.metadata.fps)
        return self._regenerate_from_phases(result, phases)

    def _regenerate_from_phases(
        self,
        result: AnalysisResult,
        phases,
    ) -> AnalysisResult:
        context = result.assessment.context if result.assessment is not None else None
        metrics = calculate_metrics(result.landmarks, phases, context)
        assessment = assess_swing(metrics, phases, context) if context else None
        render_annotated_video(
            result.artifacts.original_video,
            result.artifacts.annotated_video,
            result.artifacts.keyframes_dir,
            result.landmarks,
            phases,
            assessment,
        )
        updated = result.model_copy(
            update={"phases": phases, "metrics": metrics, "assessment": assessment}
        )
        _write_json(
            result.artifacts.metrics_json,
            metrics.model_dump(mode="json"),
        )
        _write_json(
            result.artifacts.phases_json,
            {"phases": [phase.model_dump(mode="json") for phase in phases]},
        )
        if result.artifacts.assessment_json is not None and assessment is not None:
            _write_json(
                result.artifacts.assessment_json,
                assessment.model_dump(mode="json"),
            )
        return updated

    def _extract_landmarks(
        self, video_path: Path, progress_callback: ProgressCallback | None
    ) -> tuple[VideoMetadata, list[LandmarkFrame]]:
        cap = cv2.VideoCapture(str(video_path))
        if not cap.isOpened():
            raise RuntimeError(f"Unable to open video: {video_path}")

        fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        duration = frame_count / fps if fps > 0 else 0.0
        metadata = VideoMetadata(
            source_path=str(video_path),
            fps=float(fps),
            frame_count=frame_count,
            width=width,
            height=height,
            duration_seconds=duration,
        )

        frames: list[LandmarkFrame] = []
        frame_index = 0
        with MediaPipePoseEstimator(width, height) as estimator:
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                timestamp = frame_index / fps if fps > 0 else 0.0
                frames.append(estimator.extract_frame(frame, frame_index, timestamp))
                frame_index += 1
                if frame_count:
                    progress = 0.15 + 0.55 * min(frame_index / frame_count, 1.0)
                    _report(progress_callback, f"Processing frame {frame_index}/{frame_count}", progress)

        cap.release()
        return metadata, frames


def _write_json(path: Path, payload: object) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")


def _report(callback: ProgressCallback | None, message: str, progress: float) -> None:
    if callback is not None:
        callback(message, max(0.0, min(1.0, progress)))
