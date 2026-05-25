from __future__ import annotations

from math import acos, degrees, hypot
from statistics import mean
from typing import Iterable

from analysis.models import (
    AnalysisContext,
    LandmarkFrame,
    LandmarkPoint,
    MetricSet,
    MetricValue,
    SwingPhase,
)
from analysis.phases import phase_quality_issues


def calculate_metrics(
    landmark_frames: list[LandmarkFrame],
    phases: list[SwingPhase],
    context: AnalysisContext | None = None,
) -> MetricSet:
    frames_with_pose = [frame for frame in landmark_frames if frame.pose_detected]
    address_frame = _phase_frame(frames_with_pose, phases, "Address")
    body_scale = _shoulder_width(address_frame) if address_frame else None
    quality = {
        "frames_total": len(landmark_frames),
        "frames_with_pose": len(frames_with_pose),
        "pose_detection_rate": round(
            len(frames_with_pose) / len(landmark_frames), 4
        )
        if landmark_frames
        else 0.0,
        "body_scale_shoulder_width": round(body_scale, 5) if body_scale else None,
        "phase_quality_issues": phase_quality_issues(phases, _infer_fps(landmark_frames)),
        "phase_markers_confirmed": any(
            phase.detection_method == "user_confirmed_marker" for phase in phases
        ),
        "phase_scoped_metrics": True,
    }

    metrics = {
        "tempo_ratio": _tempo_ratio(phases),
        "head_movement": _head_movement(
            frames_with_pose, phases, address_frame, body_scale
        ),
        "spine_angle_at_address": _spine_angle_at_address(address_frame),
        "lead_arm_angle": _lead_arm_angle(frames_with_pose, phases, context),
        "hip_sway": _hip_sway(
            frames_with_pose, phases, address_frame, body_scale
        ),
        "knee_flex": _knee_flex(address_frame),
        "posture_retention": _posture_retention(frames_with_pose, phases),
        "finish_stability": _finish_stability(frames_with_pose, phases, body_scale),
        "wrist_path_trajectory": _wrist_path(frames_with_pose),
    }
    return MetricSet(metrics=metrics, quality=quality)


def _tempo_ratio(phases: list[SwingPhase]) -> MetricValue:
    by_name = {phase.name: phase for phase in phases}
    address = by_name.get("Address")
    top = by_name.get("Top of backswing")
    impact = by_name.get("Impact approximation")
    value = None
    if address and top and impact:
        backswing = top.timestamp_seconds - address.timestamp_seconds
        downswing = impact.timestamp_seconds - top.timestamp_seconds
        if backswing > 0 and downswing > 0:
            value = round(backswing / downswing, 3)
    return MetricValue(
        name="Tempo ratio",
        value=value,
        unit="backswing:downswing",
        description="Backswing duration divided by downswing duration.",
        frame_index=top.frame_index if top else None,
    )


def _head_movement(
    frames: list[LandmarkFrame],
    phases: list[SwingPhase],
    address_frame: LandmarkFrame | None,
    body_scale: float | None,
) -> MetricValue:
    impact_frame = _phase_frame(frames, phases, "Impact approximation")
    value = None
    frame_index = None
    origin = _point(address_frame, "nose") if address_frame else None
    impact = _point(impact_frame, "nose") if impact_frame else None
    if origin and impact and body_scale:
        value = round(abs(impact.x - origin.x) / body_scale, 4)
        frame_index = impact_frame.frame_index
    return MetricValue(
        name="Head movement at impact",
        value=value,
        unit="shoulder widths",
        description="Lateral nose movement from address to impact approximation, scaled by shoulder width.",
        frame_index=frame_index,
    )


def _spine_angle_at_address(frame: LandmarkFrame | None) -> MetricValue:
    value = _spine_angle(frame) if frame else None
    return MetricValue(
        name="Spine angle at address",
        value=value,
        unit="degrees from vertical",
        description="Angle between hip-to-shoulder centre line and vertical at address.",
        frame_index=frame.frame_index if value is not None and frame else None,
    )


def _lead_arm_angle(
    frames: list[LandmarkFrame],
    phases: list[SwingPhase],
    context: AnalysisContext | None,
) -> MetricValue:
    side = "right" if context and context.handedness == "left" else "left"
    top_frame = _phase_frame(frames, phases, "Top of backswing")
    if top_frame is not None:
        value = _joint_angle(
            top_frame, f"{side}_shoulder", f"{side}_elbow", f"{side}_wrist"
        )
        if value is not None:
            return MetricValue(
                name="Lead arm angle",
                value=round(value, 2),
                unit="degrees",
                description=f"{side.title()} lead shoulder-elbow-wrist angle at the top of the backswing.",
                frame_index=top_frame.frame_index,
            )
    candidates = []
    for frame in frames:
        angle = _joint_angle(
            frame, f"{side}_shoulder", f"{side}_elbow", f"{side}_wrist"
        )
        if angle is not None:
            candidates.append((frame.frame_index, angle))
    value = round(max((angle for _, angle in candidates), default=None), 2) if candidates else None
    frame_index = max(candidates, key=lambda item: item[1])[0] if candidates else None
    return MetricValue(
        name="Lead arm angle",
        value=value,
        unit="degrees",
        description=f"Maximum {side} lead shoulder-elbow-wrist angle detected.",
        frame_index=frame_index,
    )


def _hip_sway(
    frames: list[LandmarkFrame],
    phases: list[SwingPhase],
    address_frame: LandmarkFrame | None,
    body_scale: float | None,
) -> MetricValue:
    top_frame = _phase_frame(frames, phases, "Top of backswing")
    value = None
    frame_index = None
    origin = _midpoint(address_frame, "left_hip", "right_hip") if address_frame else None
    top = _midpoint(top_frame, "left_hip", "right_hip") if top_frame else None
    if origin and top and body_scale:
        value = round(abs(top.x - origin.x) / body_scale, 4)
        frame_index = top_frame.frame_index
    return MetricValue(
        name="Hip sway at top",
        value=value,
        unit="shoulder widths",
        description="Horizontal hip-centre movement from address to top of backswing, scaled by shoulder width.",
        frame_index=frame_index,
    )


def _knee_flex(frame: LandmarkFrame | None) -> MetricValue:
    angles = [
        angle
        for angle in (
            _joint_angle(frame, "left_hip", "left_knee", "left_ankle") if frame else None,
            _joint_angle(frame, "right_hip", "right_knee", "right_ankle") if frame else None,
        )
        if angle is not None
    ]
    value = round(mean(angles), 2) if angles else None
    return MetricValue(
        name="Knee flex",
        value=value,
        unit="degrees",
        description="Average knee angle at the first detected address frame.",
        frame_index=frame.frame_index if value is not None and frame else None,
    )


def _posture_retention(
    frames: list[LandmarkFrame], phases: list[SwingPhase]
) -> MetricValue:
    address = _phase_frame(frames, phases, "Address")
    downswing = _phase_frame(frames, phases, "Downswing")
    address_angle = _spine_angle(address) if address else None
    downswing_angle = _spine_angle(downswing) if downswing else None
    value = (
        round(abs(downswing_angle - address_angle), 2)
        if address_angle is not None and downswing_angle is not None
        else None
    )
    return MetricValue(
        name="Posture retention",
        value=value,
        unit="degrees change",
        description="Change in spine inclination from address to downswing.",
        frame_index=downswing.frame_index if value is not None and downswing else None,
    )


def _finish_stability(
    frames: list[LandmarkFrame], phases: list[SwingPhase], body_scale: float | None
) -> MetricValue:
    follow = _phase_frame(frames, phases, "Follow-through")
    finish = _phase_frame(frames, phases, "Finish")
    follow_head = _point(follow, "nose") if follow else None
    finish_head = _point(finish, "nose") if finish else None
    value = None
    if follow_head and finish_head and body_scale:
        value = round(
            hypot(finish_head.x - follow_head.x, finish_head.y - follow_head.y)
            / body_scale,
            4,
        )
    return MetricValue(
        name="Finish stability",
        value=value,
        unit="shoulder widths",
        description="Head displacement from follow-through to finish as a balance proxy.",
        frame_index=finish.frame_index if value is not None and finish else None,
    )


def _wrist_path(frames: list[LandmarkFrame]) -> MetricValue:
    trajectory = []
    for frame in frames:
        wrists = [point for point in (_point(frame, "left_wrist"), _point(frame, "right_wrist")) if point]
        if wrists:
            trajectory.append(
                {
                    "frame_index": frame.frame_index,
                    "timestamp_seconds": frame.timestamp_seconds,
                    "x": round(mean(point.x for point in wrists), 5),
                    "y": round(mean(point.y for point in wrists), 5),
                }
            )
    return MetricValue(
        name="Wrist path trajectory",
        value=trajectory,
        unit="normalised image coordinates",
        description="Per-frame midpoint trajectory of detected wrists.",
    )


def _series(
    frames: Iterable[LandmarkFrame], name: str
) -> list[tuple[LandmarkFrame, LandmarkPoint]]:
    series = []
    for frame in frames:
        point = _point(frame, name)
        if point is not None:
            series.append((frame, point))
    return series


def _point(frame: LandmarkFrame, name: str) -> LandmarkPoint | None:
    for point in frame.landmarks:
        if point.name == name and (point.visibility is None or point.visibility >= 0.2):
            return point
    return None


def _phase_frame(
    frames: list[LandmarkFrame], phases: list[SwingPhase], name: str
) -> LandmarkFrame | None:
    phase = next((item for item in phases if item.name == name), None)
    if phase is None or not frames:
        return None
    return min(frames, key=lambda frame: abs(frame.frame_index - phase.frame_index))


def _shoulder_width(frame: LandmarkFrame | None) -> float | None:
    if frame is None:
        return None
    left = _point(frame, "left_shoulder")
    right = _point(frame, "right_shoulder")
    if left is None or right is None:
        return None
    width = hypot(left.x - right.x, left.y - right.y)
    return width if width > 1e-9 else None


def _spine_angle(frame: LandmarkFrame | None) -> float | None:
    if frame is None:
        return None
    shoulders = _midpoint(frame, "left_shoulder", "right_shoulder")
    hips = _midpoint(frame, "left_hip", "right_hip")
    if shoulders is None or hips is None:
        return None
    dx = shoulders.x - hips.x
    dy = shoulders.y - hips.y
    return round(
        degrees(acos(_clamp(abs(dy) / max(hypot(dx, dy), 1e-9)))),
        2,
    )


def _midpoint(frame: LandmarkFrame, first: str, second: str) -> LandmarkPoint | None:
    a = _point(frame, first)
    b = _point(frame, second)
    if a is None or b is None:
        return None
    return LandmarkPoint(
        name=f"{first}_{second}_midpoint",
        x=(a.x + b.x) / 2,
        y=(a.y + b.y) / 2,
        z=None if a.z is None or b.z is None else (a.z + b.z) / 2,
        visibility=None,
        pixel_x=None,
        pixel_y=None,
    )


def _joint_angle(frame: LandmarkFrame, a_name: str, b_name: str, c_name: str) -> float | None:
    a = _point(frame, a_name)
    b = _point(frame, b_name)
    c = _point(frame, c_name)
    if a is None or b is None or c is None:
        return None

    ba = (a.x - b.x, a.y - b.y)
    bc = (c.x - b.x, c.y - b.y)
    ba_len = hypot(*ba)
    bc_len = hypot(*bc)
    if ba_len == 0 or bc_len == 0:
        return None
    cosine = _clamp((ba[0] * bc[0] + ba[1] * bc[1]) / (ba_len * bc_len))
    return degrees(acos(cosine))


def _clamp(value: float) -> float:
    return max(-1.0, min(1.0, value))


def _infer_fps(frames: list[LandmarkFrame]) -> float:
    if len(frames) >= 2:
        delta = frames[1].timestamp_seconds - frames[0].timestamp_seconds
        if delta > 0:
            return 1.0 / delta
    return 30.0
