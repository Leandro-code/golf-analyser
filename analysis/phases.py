from __future__ import annotations

from math import hypot

from analysis.models import LandmarkFrame, LandmarkPoint, SwingPhase


PHASE_NAMES = [
    "Address",
    "Takeaway",
    "Lead arm parallel backswing (P3)",
    "Top (P4)",
    "Lead arm parallel downswing (P5)",
    "Shaft parallel downswing (P6)",
    "Impact approximation (P7)",
    "Shaft parallel follow-through (P8)",
    "Finish",
]

ADDRESS_PHASE = "Address"
TAKEAWAY_PHASE = "Takeaway"
P3_PHASE = "Lead arm parallel backswing (P3)"
TOP_PHASE = "Top (P4)"
P5_PHASE = "Lead arm parallel downswing (P5)"
P6_PHASE = "Shaft parallel downswing (P6)"
IMPACT_PHASE = "Impact approximation (P7)"
P8_PHASE = "Shaft parallel follow-through (P8)"
FINISH_PHASE = "Finish"

LEGACY_PHASE_ALIASES = {
    "Top of backswing": TOP_PHASE,
    "Downswing": P6_PHASE,
    "Impact approximation": IMPACT_PHASE,
    "Follow-through": P8_PHASE,
}


def canonical_phase_name(name: str) -> str:
    return LEGACY_PHASE_ALIASES.get(name, name)


def phase_by_name(phases: list[SwingPhase]) -> dict[str, SwingPhase]:
    by_name: dict[str, SwingPhase] = {}
    for phase in phases:
        by_name.setdefault(canonical_phase_name(phase.name), phase)
    return by_name


def detect_swing_phases(
    landmark_frames: list[LandmarkFrame], fps: float
) -> list[SwingPhase]:
    points = _smooth_points(_interpolate_short_gaps(_wrist_points(landmark_frames)))
    if not points:
        return _fallback_phases(landmark_frames, fps, "no_pose_landmarks")

    address_idx = _active_address_index(points)
    active = [point for point in points if point[0] >= address_idx]
    address = active[0]
    top_result = _first_backswing_reversal(active, address)
    if top_result is None:
        return _fallback_phases(landmark_frames, fps, "no_ordered_backswing_reversal")
    top, top_method, top_confidence = top_result
    impact = _first_impact_return(active, address, top)
    if impact is None:
        return _fallback_phases(landmark_frames, fps, "no_ordered_impact_return")
    finish_idx = _first_finish_position(active, impact[0], address, top)
    p6_idx = _first_downswing_delivery_index(active, top, impact, address)

    indices = [
        address_idx,
        _nearest_detected_index(active, _between(address_idx, top[0], 0.25)),
        _nearest_detected_index(active, _between(address_idx, top[0], 0.65)),
        top[0],
        _nearest_detected_index(active, _between(top[0], impact[0], 0.32)),
        p6_idx,
        impact[0],
        _nearest_detected_index(active, _between(impact[0], finish_idx, 0.45)),
        finish_idx,
    ]
    methods = [
        "detected_stable_address_before_motion",
        "pose_proxy_ordered_address_to_top",
        "pose_proxy_ordered_address_to_top",
        top_method,
        "pose_proxy_ordered_top_to_impact",
        "pose_proxy_hand_return_to_delivery",
        "first_post_top_strike_region_transition",
        "pose_proxy_ordered_impact_to_finish",
        "first_post_impact_high_hands_position",
    ]
    confidences = [0.72, 0.62, 0.64, top_confidence, 0.64, 0.66, 0.72, 0.64, 0.68]
    return [
        SwingPhase(
            name=name,
            frame_index=frame_index,
            timestamp_seconds=_timestamp(frame_index, fps),
            confidence=confidence,
            detection_method=method,
        )
        for name, frame_index, confidence, method in zip(
            PHASE_NAMES, indices, confidences, methods
        )
    ]


def build_confirmed_phases(
    address_index: int,
    *marker_indices: int,
    fps: float,
) -> list[SwingPhase]:
    if len(marker_indices) == 3:
        top_index, impact_index, finish_index = marker_indices
        indices = [
            address_index,
            _between(address_index, top_index, 0.25),
            _between(address_index, top_index, 0.65),
            top_index,
            _between(top_index, impact_index, 0.32),
            _between(top_index, impact_index, 0.62),
            impact_index,
            _between(impact_index, finish_index, 0.45),
            finish_index,
        ]
        confirmed_names = {ADDRESS_PHASE, TOP_PHASE, IMPACT_PHASE, FINISH_PHASE}
    elif len(marker_indices) == len(PHASE_NAMES) - 1:
        indices = [address_index, *marker_indices]
        confirmed_names = set(PHASE_NAMES)
    else:
        raise ValueError("Expected either 4 legacy markers or all 9 phase markers.")
    if not _strictly_ordered(indices):
        raise ValueError(
            "Phase markers must be ordered: "
            + " < ".join(_short_phase_name(name) for name in PHASE_NAMES)
            + "."
        )
    return [
        SwingPhase(
            name=name,
            frame_index=index,
            timestamp_seconds=_timestamp(index, fps),
            confidence=1.0,
            detection_method=(
                "user_confirmed_marker"
                if name in confirmed_names
                else "interpolated_from_user_confirmed_markers"
            ),
        )
        for name, index in zip(PHASE_NAMES, indices)
    ]


def phase_quality_issues(phases: list[SwingPhase], fps: float) -> list[str]:
    phases_by_name = phase_by_name(phases)
    required = [phases_by_name.get(name) for name in PHASE_NAMES]
    if any(phase is None for phase in required):
        return ["Required swing phase markers are missing."]
    if not _strictly_ordered([phase.frame_index for phase in required if phase is not None]):
        return ["Swing phases are not in chronological order."]
    if any(phase.detection_method.startswith("no_") for phase in phases):
        return ["Automatic detection could not establish an ordered swing sequence."]
    manually_confirmed = any(
        phase.detection_method == "user_confirmed_marker" for phase in phases
    )
    address = phases_by_name[ADDRESS_PHASE]
    top = phases_by_name[TOP_PHASE]
    impact = phases_by_name[IMPACT_PHASE]
    backswing = (top.frame_index - address.frame_index) / max(fps, 1e-9)
    downswing = (impact.frame_index - top.frame_index) / max(fps, 1e-9)
    if not manually_confirmed and downswing > backswing:
        return [
            "Automatic timing appears implausible because the detected downswing "
            "is longer than the backswing. Confirm the phase markers."
        ]
    return []


def _wrist_points(frames: list[LandmarkFrame]) -> list[tuple[int, float, float]]:
    points = []
    for frame in frames:
        wrists = [
            point
            for point in (
                _landmark(frame, "left_wrist"),
                _landmark(frame, "right_wrist"),
            )
            if point is not None and (point.visibility is None or point.visibility >= 0.2)
        ]
        if wrists:
            points.append(
                (
                    frame.frame_index,
                    sum(point.x for point in wrists) / len(wrists),
                    sum(point.y for point in wrists) / len(wrists),
                )
            )
    return points


def _interpolate_short_gaps(
    points: list[tuple[int, float, float]], max_gap: int = 3
) -> list[tuple[int, float, float]]:
    if len(points) < 2:
        return points
    completed: list[tuple[int, float, float]] = []
    for first, second in zip(points, points[1:]):
        completed.append(first)
        gap = second[0] - first[0] - 1
        if 0 < gap <= max_gap:
            for offset in range(1, gap + 1):
                ratio = offset / (gap + 1)
                completed.append(
                    (
                        first[0] + offset,
                        first[1] + (second[1] - first[1]) * ratio,
                        first[2] + (second[2] - first[2]) * ratio,
                    )
                )
    completed.append(points[-1])
    return completed


def _smooth_points(
    points: list[tuple[int, float, float]], window: int = 5
) -> list[tuple[int, float, float]]:
    if len(points) < 10:
        return points
    radius = window // 2
    return [
        (
            point[0],
            sum(item[1] for item in points[max(0, index - radius) : index + radius + 1])
            / len(points[max(0, index - radius) : index + radius + 1]),
            sum(item[2] for item in points[max(0, index - radius) : index + radius + 1])
            / len(points[max(0, index - radius) : index + radius + 1]),
        )
        for index, point in enumerate(points)
    ]


def _active_address_index(points: list[tuple[int, float, float]]) -> int:
    if len(points) < 10:
        return points[0][0]
    velocities = [
        hypot(current[1] - previous[1], current[2] - previous[2])
        for previous, current in zip(points, points[1:])
    ]
    idle = sorted(velocities[: min(15, len(velocities))])
    baseline = idle[len(idle) // 2] if idle else 0.0
    threshold = max(0.003, baseline * 4)
    for index in range(1, len(velocities) - 4):
        if (
            sum(velocity > threshold for velocity in velocities[index : index + 4]) >= 3
            and sum(velocities[index : index + 4]) > threshold * 5
        ):
            return points[min(len(points) - 1, index + 1)][0]
    return points[0][0]


def _first_backswing_reversal(
    points: list[tuple[int, float, float]],
    address: tuple[int, float, float],
) -> tuple[tuple[int, float, float], str, float] | None:
    if len(points) < 5:
        candidate = min(points, key=lambda point: point[2]) if points else None
        if candidate is None:
            return None
        return candidate, "minimum_wrist_y_from_short_sequence", 0.62
    for index in range(2, len(points) - 2):
        candidate = points[index]
        if address[2] - candidate[2] < 0.04:
            continue
        before = sum(
            points[offset][2] - points[offset - 1][2]
            for offset in range(index - 1, index + 1)
        )
        after = sum(
            points[offset + 1][2] - points[offset][2]
            for offset in range(index, index + 2)
        )
        if before < 0 and after > 0.006:
            return candidate, "first_qualified_wrist_direction_reversal", 0.78
    return _first_sustained_backswing_reversal(points, address)


def _first_sustained_backswing_reversal(
    points: list[tuple[int, float, float]],
    address: tuple[int, float, float],
    lookahead: int = 6,
) -> tuple[tuple[int, float, float], str, float] | None:
    if len(points) <= lookahead + 2:
        return None
    for index in range(2, len(points) - lookahead):
        candidate = points[index]
        if address[2] - candidate[2] < 0.04:
            continue
        before = sum(
            points[offset][2] - points[offset - 1][2]
            for offset in range(max(1, index - 4), index + 1)
        )
        future_highest_y = max(
            point[2] for point in points[index + 1 : index + lookahead + 1]
        )
        if before < -0.01 and future_highest_y - candidate[2] >= 0.003:
            return candidate, "first_sustained_wrist_reversal", 0.72
    return None


def _first_impact_return(
    points: list[tuple[int, float, float]],
    address: tuple[int, float, float],
    top: tuple[int, float, float],
) -> tuple[int, float, float] | None:
    excursion = max(address[2] - top[2], 0.04)
    after_top = [point for point in points if point[0] > top[0]]
    for index in range(2, len(after_top) - 2):
        candidate = after_top[index]
        if candidate[2] - top[2] < excursion * 0.35:
            continue
        before = sum(
            after_top[offset][2] - after_top[offset - 1][2]
            for offset in range(index - 1, index + 1)
        )
        after = sum(
            after_top[offset + 1][2] - after_top[offset][2]
            for offset in range(index, index + 2)
        )
        if before > 0 and after < -0.006:
            return candidate
    return_threshold = address[2] - excursion * 0.32
    for point in after_top:
        if point[2] >= return_threshold:
            return point
    return None


def _first_finish_position(
    points: list[tuple[int, float, float]],
    impact_index: int,
    address: tuple[int, float, float],
    top: tuple[int, float, float],
) -> int:
    after_impact = [point for point in points if point[0] > impact_index]
    if not after_impact:
        return impact_index + 1
    high_threshold = address[2] - max(address[2] - top[2], 0.04) * 0.55
    min_completion_index = impact_index + max(1, impact_index - top[0])
    for index in range(1, len(after_impact) - 1):
        candidate = after_impact[index]
        if (
            candidate[0] >= min_completion_index
            and
            candidate[2] <= high_threshold
            and candidate[2] <= after_impact[index - 1][2]
            and candidate[2] <= after_impact[index + 1][2] + 0.006
        ):
            return candidate[0]
    late_high = [
        point for point in after_impact if point[0] >= min_completion_index and point[2] <= high_threshold
    ]
    if late_high:
        return late_high[-1][0]
    return after_impact[-1][0]


def _first_downswing_delivery_index(
    points: list[tuple[int, float, float]],
    top: tuple[int, float, float],
    impact: tuple[int, float, float],
    address: tuple[int, float, float],
) -> int:
    """Approximate P6 from body pose by hand return depth, not elapsed time.

    MediaPipe does not track the club shaft. The best local proxy is the first
    post-P5 downswing frame where the hands have returned materially toward the
    address hand height, immediately before the strike-region transition.
    """
    excursion = max(address[2] - top[2], 0.04)
    target_y = top[2] + excursion * 0.45
    p5_floor = top[0] + max(1, round((impact[0] - top[0]) * 0.32))
    candidates = [
        point
        for point in points
        if p5_floor < point[0] < impact[0] and point[2] >= target_y
    ]
    if candidates:
        return candidates[0][0]
    return _nearest_detected_index(points, _between(top[0], impact[0], 0.82))


def _landmark(frame: LandmarkFrame, name: str) -> LandmarkPoint | None:
    return next((point for point in frame.landmarks if point.name == name), None)


def _between(start: int, end: int, fraction: float) -> int:
    return int(round(start + (end - start) * fraction))


def _strictly_ordered(indices: list[int]) -> bool:
    return all(first < second for first, second in zip(indices, indices[1:]))


def _short_phase_name(name: str) -> str:
    if name == IMPACT_PHASE:
        return "Impact"
    if name.startswith("Lead arm parallel backswing"):
        return "P3"
    if name.startswith("Lead arm parallel downswing"):
        return "P5"
    if name.startswith("Shaft parallel downswing"):
        return "P6"
    if name.startswith("Shaft parallel follow-through"):
        return "P8"
    return name.replace(" (P4)", "")


def _nearest_detected_index(points: list[tuple[int, float, float]], target: int) -> int:
    return min(points, key=lambda item: abs(item[0] - target))[0]


def _timestamp(frame_index: int, fps: float) -> float:
    return round(float(frame_index) / fps, 4) if fps > 0 else 0.0


def _fallback_phases(
    landmark_frames: list[LandmarkFrame], fps: float, method: str
) -> list[SwingPhase]:
    max_index = max(0, len(landmark_frames) - 1)
    indices = (
        [0] * len(PHASE_NAMES)
        if len(landmark_frames) <= 1
        else [
            int(round(max_index * fraction))
            for fraction in (0.0, 0.12, 0.26, 0.4, 0.5, 0.6, 0.72, 0.84, 1.0)
        ]
    )
    return [
        SwingPhase(
            name=name,
            frame_index=index,
            timestamp_seconds=_timestamp(index, fps),
            confidence=0.1,
            detection_method=method,
        )
        for name, index in zip(PHASE_NAMES, indices)
    ]
