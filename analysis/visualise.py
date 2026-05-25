from __future__ import annotations

from pathlib import Path

import cv2

from analysis.models import LandmarkFrame, LandmarkPoint, SwingAssessment, SwingPhase


POSE_CONNECTIONS = [
    ("left_shoulder", "right_shoulder"),
    ("left_shoulder", "left_elbow"),
    ("left_elbow", "left_wrist"),
    ("right_shoulder", "right_elbow"),
    ("right_elbow", "right_wrist"),
    ("left_shoulder", "left_hip"),
    ("right_shoulder", "right_hip"),
    ("left_hip", "right_hip"),
    ("left_hip", "left_knee"),
    ("left_knee", "left_ankle"),
    ("right_hip", "right_knee"),
    ("right_knee", "right_ankle"),
    ("nose", "left_shoulder"),
    ("nose", "right_shoulder"),
]


def render_annotated_video(
    source_video: Path,
    annotated_video: Path,
    keyframes_dir: Path,
    landmark_frames: list[LandmarkFrame],
    phases: list[SwingPhase],
    assessment: SwingAssessment | None = None,
) -> None:
    keyframes_dir.mkdir(parents=True, exist_ok=True)
    for existing in keyframes_dir.glob("finding_*.jpg"):
        existing.unlink()
    cap = cv2.VideoCapture(str(source_video))
    if not cap.isOpened():
        raise RuntimeError(f"Unable to open video for visualisation: {source_video}")

    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    writer = cv2.VideoWriter(
        str(annotated_video),
        cv2.VideoWriter_fourcc(*"mp4v"),
        fps,
        (width, height),
    )
    if not writer.isOpened():
        cap.release()
        raise RuntimeError(f"Unable to create annotated video: {annotated_video}")

    frames_by_index = {frame.frame_index: frame for frame in landmark_frames}
    phases_by_index = {phase.frame_index: phase for phase in phases}
    phase_targets = {phase.frame_index for phase in phases}
    evidence_findings = (
        [
            finding
            for finding in assessment.findings
            if finding.frame_index is not None and finding.evidence_keyframe is not None
        ]
        if assessment
        else []
    )
    findings_by_index = {}
    for finding in evidence_findings:
        findings_by_index.setdefault(finding.frame_index, []).append(finding)

    frame_index = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        landmark_frame = frames_by_index.get(frame_index)
        current_phase = _current_phase(phases, frame_index)
        annotated = frame.copy()
        if landmark_frame is not None:
            _draw_pose(annotated, landmark_frame)
        _draw_label(annotated, current_phase.name if current_phase else "Analysing")
        for finding in findings_by_index.get(frame_index, []):
            _draw_evidence_label(annotated, finding.reference.name, finding.observed_value)
        writer.write(annotated)

        if frame_index in phase_targets:
            phase = phases_by_index[frame_index]
            filename = _safe_phase_filename(phase.name)
            cv2.imwrite(str(keyframes_dir / f"{filename}.jpg"), annotated)
        for finding in findings_by_index.get(frame_index, []):
            cv2.imwrite(str(keyframes_dir / finding.evidence_keyframe), annotated)
        frame_index += 1

    cap.release()
    writer.release()


def _draw_pose(frame, landmark_frame: LandmarkFrame) -> None:
    landmarks = {point.name: point for point in landmark_frame.landmarks}
    for first, second in POSE_CONNECTIONS:
        a = landmarks.get(first)
        b = landmarks.get(second)
        if _visible(a) and _visible(b):
            cv2.line(
                frame,
                _pixel(a),
                _pixel(b),
                color=(0, 210, 255),
                thickness=2,
                lineType=cv2.LINE_AA,
            )
    for point in landmarks.values():
        if _visible(point):
            cv2.circle(frame, _pixel(point), 3, (20, 255, 120), -1, cv2.LINE_AA)


def _draw_label(frame, label: str) -> None:
    cv2.rectangle(frame, (16, 16), (340, 62), (18, 18, 18), -1)
    cv2.putText(
        frame,
        label,
        (28, 48),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.85,
        (255, 255, 255),
        2,
        cv2.LINE_AA,
    )


def _draw_evidence_label(frame, label: str, value: float | None) -> None:
    text = label if value is None else f"{label}: {value:g}"
    height, width = frame.shape[:2]
    cv2.rectangle(frame, (16, height - 54), (min(width - 16, 560), height - 16), (18, 18, 18), -1)
    cv2.putText(
        frame,
        text[:54],
        (26, height - 28),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.58,
        (255, 255, 255),
        2,
        cv2.LINE_AA,
    )


def _current_phase(phases: list[SwingPhase], frame_index: int) -> SwingPhase | None:
    current = None
    for phase in sorted(phases, key=lambda item: item.frame_index):
        if phase.frame_index <= frame_index:
            current = phase
        else:
            break
    return current


def _visible(point: LandmarkPoint | None) -> bool:
    return point is not None and (point.visibility is None or point.visibility >= 0.2)


def _pixel(point: LandmarkPoint) -> tuple[int, int]:
    if point.pixel_x is not None and point.pixel_y is not None:
        return int(point.pixel_x), int(point.pixel_y)
    return int(point.x), int(point.y)


def _safe_phase_filename(name: str) -> str:
    return name.lower().replace(" ", "_").replace("-", "_")
