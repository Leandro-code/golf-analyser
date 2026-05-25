from __future__ import annotations

from analysis.phases import PHASE_NAMES, build_confirmed_phases, detect_swing_phases, phase_quality_issues


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
    assert phases[2].name == "Top of backswing"
    assert phases[2].frame_index == 3
    assert phases[4].name == "Impact approximation"
    assert phases[4].frame_index == 5
    assert phases[-1].frame_index == 7


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
    phase_by_name = {phase.name: phase for phase in phases}

    assert phase_by_name["Address"].frame_index >= 9
    assert phase_by_name["Top of backswing"].frame_index > phase_by_name["Address"].frame_index
    assert phase_by_name["Impact approximation"].frame_index > phase_by_name["Top of backswing"].frame_index


def test_finish_hands_higher_than_backswing_does_not_become_top(frame):
    y_values = [0.7] * 8 + [0.66, 0.56, 0.43, 0.28, 0.3, 0.4, 0.58, 0.7, 0.48, 0.18, 0.12, 0.13]
    frames = [
        frame(index, left_wrist=(0.45, y), right_wrist=(0.55, y))
        for index, y in enumerate(y_values)
    ]

    phases = detect_swing_phases(frames, fps=30)
    phase_by_name = {phase.name: phase for phase in phases}

    assert phase_by_name["Top of backswing"].frame_index < phase_by_name["Impact approximation"].frame_index
    assert phase_by_name["Top of backswing"].frame_index < 17


def test_user_confirmed_markers_bypass_automatic_timing_warning():
    phases = build_confirmed_phases(10, 12, 20, 25, fps=30)

    assert phase_quality_issues(phases, fps=30) == []
    assert phases[2].detection_method == "user_confirmed_marker"
