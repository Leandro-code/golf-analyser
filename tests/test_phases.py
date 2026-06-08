from __future__ import annotations

from analysis.phases import (
    PHASE_NAMES,
    ADDRESS_PHASE,
    FINISH_PHASE,
    IMPACT_PHASE,
    P3_PHASE,
    P5_PHASE,
    P6_PHASE,
    P8_PHASE,
    TAKEAWAY_PHASE,
    TOP_PHASE,
    _first_backswing_reversal,
    build_confirmed_phases,
    detect_swing_phases,
    phase_by_name,
    phase_quality_issues,
)


def test_detect_swing_phases_from_wrist_trajectory(frame):
    frames = []
    y_values = [0.7, 0.62, 0.48, 0.28, 0.42, 0.68, 0.55, 0.5]
    for index, y in enumerate(y_values):
        frames.append(
            frame(
                index,
                left_wrist=(0.45, y),
                right_wrist=(0.55, y),
            )
        )

    phases = detect_swing_phases(frames, fps=30)

    assert [phase.name for phase in phases] == PHASE_NAMES
    assert phases[0].frame_index == 0
    assert phases[3].name == TOP_PHASE
    assert phases[3].frame_index == 3
    assert phases[6].name == IMPACT_PHASE
    assert phases[6].frame_index == 5
    assert phases[-1].frame_index == 7
    assert [phase.frame_index for phase in phases] == sorted(
        phase.frame_index for phase in phases
    )


def test_detect_swing_phases_falls_back_without_pose(frame):
    frames = [frame(index) for index in range(7)]

    phases = detect_swing_phases(frames, fps=30)

    assert [phase.name for phase in phases] == PHASE_NAMES
    assert all(phase.confidence == 0.1 for phase in phases)
    assert phases[-1].frame_index == 6


def test_detect_swing_phases_ignores_idle_setup_frames(frame):
    y_values = ([0.7] * 12) + [0.68, 0.62, 0.5, 0.35, 0.25, 0.4, 0.58, 0.69, 0.62]
    frames = [
        frame(index, left_wrist=(0.45, y), right_wrist=(0.55, y))
        for index, y in enumerate(y_values)
    ]

    phases = detect_swing_phases(frames, fps=30)
    phases_by_name = phase_by_name(phases)

    assert phases_by_name[ADDRESS_PHASE].frame_index >= 9
    assert phases_by_name[TOP_PHASE].frame_index > phases_by_name[ADDRESS_PHASE].frame_index
    assert phases_by_name[IMPACT_PHASE].frame_index > phases_by_name[TOP_PHASE].frame_index


def test_finish_hands_higher_than_backswing_does_not_become_top(frame):
    y_values = [0.7] * 8 + [0.66, 0.56, 0.43, 0.28, 0.3, 0.4, 0.58, 0.7, 0.48, 0.18, 0.12, 0.13]
    frames = [
        frame(index, left_wrist=(0.45, y), right_wrist=(0.55, y))
        for index, y in enumerate(y_values)
    ]

    phases = detect_swing_phases(frames, fps=30)
    phases_by_name = phase_by_name(phases)

    assert phases_by_name[TOP_PHASE].frame_index < phases_by_name[IMPACT_PHASE].frame_index
    assert phases_by_name[TOP_PHASE].frame_index < 17


def test_flat_backswing_transition_uses_first_sustained_reversal():
    y_values = [
        0.444,
        0.404,
        0.395,
        0.386,
        0.376,
        0.358,
        0.337,
        0.314,
        0.289,
        0.264,
        0.252,
        0.245,
        0.244,
        0.244,
        0.246,
        0.248,
        0.248,
        0.248,
        0.253,
        0.257,
    ]
    points = [
        (frame_index, 0.5, y)
        for frame_index, y in enumerate(y_values, start=71)
    ]

    candidate = _first_backswing_reversal(points, points[0])

    assert candidate is not None
    point, method, confidence = candidate
    assert point[0] == 82
    assert method == "first_sustained_wrist_reversal"
    assert confidence == 0.72


def test_observed_gradual_top_shape_detects_ordered_sequence(frame):
    wrist_points = [
        (0.339459, 0.446136), (0.336157, 0.440083),
        (0.332210, 0.434049), (0.323544, 0.429206),
        (0.300373, 0.418117), (0.283911, 0.413779),
        (0.272804, 0.404682), (0.261199, 0.393977),
        (0.260364, 0.387297), (0.254663, 0.377468),
        (0.241072, 0.367292), (0.235214, 0.353547),
        (0.136320, 0.302524), (0.130974, 0.286363),
        (0.123424, 0.260149), (0.121188, 0.240546),
        (0.126536, 0.232542), (0.135200, 0.238739),
        (0.150674, 0.253025), (0.157673, 0.253818),
        (0.165843, 0.241966), (0.172539, 0.240969),
        (0.176698, 0.250784), (0.179416, 0.253754),
        (0.191664, 0.254668), (0.195403, 0.262972),
        (0.199619, 0.262788), (0.205903, 0.263285),
        (0.232938, 0.291695), (0.302966, 0.322368),
        (0.364494, 0.368973), (0.421628, 0.401717),
        (0.456222, 0.407207), (0.429561, 0.397552),
        (0.356505, 0.328452), (0.325737, 0.319413),
        (0.261187, 0.264345), (0.194492, 0.239980),
        (0.166840, 0.239083), (0.168102, 0.231206),
        (0.163969, 0.231949), (0.159385, 0.211473),
        (0.172032, 0.201308), (0.173433, 0.200058),
        (0.185804, 0.198698), (0.191071, 0.199185),
        (0.192951, 0.204224), (0.202420, 0.205976),
        (0.190234, 0.204367), (0.186856, 0.204042),
        (0.188767, 0.203120), (0.185697, 0.205222),
        (0.293700, 0.253085), (0.333301, 0.269662),
        (0.396256, 0.283051), (0.397808, 0.275568),
        (0.375635, 0.267488),
    ]
    wrist_points = [wrist_points[0]] * 5 + wrist_points
    frames = [
        frame(index, left_wrist=point, right_wrist=point)
        for index, point in enumerate(wrist_points)
    ]

    phases = detect_swing_phases(frames, fps=30)
    phases_by_name = phase_by_name(phases)

    assert phase_quality_issues(phases, fps=30) == []
    assert phases_by_name[TOP_PHASE].detection_method == "first_sustained_wrist_reversal"
    assert phases_by_name[ADDRESS_PHASE].frame_index < phases_by_name[TOP_PHASE].frame_index
    assert phases_by_name[TOP_PHASE].frame_index < phases_by_name[IMPACT_PHASE].frame_index
    assert phases_by_name[IMPACT_PHASE].frame_index < phases_by_name[FINISH_PHASE].frame_index
    assert not any(
        phase.detection_method.startswith("no_")
        for phase in phases
    )


def test_user_confirmed_markers_bypass_automatic_timing_warning():
    phases = build_confirmed_phases(10, 12, 14, 16, 18, 20, 22, 24, 26, fps=30)

    assert phase_quality_issues(phases, fps=30) == []
    assert phases[2].detection_method == "user_confirmed_marker"


def test_all_confirmed_phase_markers_must_be_strictly_ordered():
    try:
        build_confirmed_phases(10, 12, 14, 16, 18, 18, 22, 24, 26, fps=30)
    except ValueError as exc:
        assert "Phase markers must be ordered" in str(exc)
    else:
        raise AssertionError("Out-of-order phase markers should fail validation.")


def test_phase_names_cover_requested_nine_marker_model():
    assert PHASE_NAMES == [
        ADDRESS_PHASE,
        TAKEAWAY_PHASE,
        P3_PHASE,
        TOP_PHASE,
        P5_PHASE,
        P6_PHASE,
        IMPACT_PHASE,
        P8_PHASE,
        FINISH_PHASE,
    ]


def test_p6_uses_hand_return_depth_not_elapsed_time(frame):
    y_values = (
        [0.46] * 20
        + [0.462, 0.461, 0.459, 0.453, 0.445, 0.434, 0.427, 0.42]
        + [0.37, 0.31, 0.25, 0.233, 0.239, 0.242, 0.254, 0.263]
        + [0.263, 0.292, 0.322, 0.369, 0.402, 0.407, 0.398]
        + [0.329, 0.319, 0.264, 0.24, 0.211, 0.199, 0.204]
    )
    frames = [
        frame(index, left_wrist=(0.45, y), right_wrist=(0.55, y))
        for index, y in enumerate(y_values)
    ]

    phases = phase_by_name(detect_swing_phases(frames, fps=30))

    assert phases[ADDRESS_PHASE].frame_index < 25
    assert phases[P6_PHASE].frame_index >= 38
    assert phases[P6_PHASE].frame_index < phases[IMPACT_PHASE].frame_index
    assert phases[FINISH_PHASE].frame_index > phases[P8_PHASE].frame_index
