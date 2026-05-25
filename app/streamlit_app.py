from __future__ import annotations

import json
import os
import sys
import tempfile
from datetime import datetime
from pathlib import Path

import streamlit as st

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from analysis import SwingAnalyser
from analysis.models import AnalysisContext
from analysis.storage import list_analysis_runs, load_analysis_result

OUTPUTS_DIR = Path(os.environ.get("GOLF_ANALYSER_OUTPUTS_DIR", PROJECT_ROOT / "outputs"))


def main() -> None:
    st.set_page_config(page_title="Golf Swing Analysis Workbench", layout="wide")
    st.title("Golf Swing Analysis Workbench")

    view = st.segmented_control(
        "Workspace view",
        ["New analysis", "History"],
        default="New analysis",
        selection_mode="single",
    )
    if view == "History":
        runs = list_analysis_runs(OUTPUTS_DIR)
        if not runs:
            st.info("No saved analyses yet.")
            return
        selected_run = st.selectbox(
            "Saved analyses",
            runs,
            format_func=_run_label,
        )
        try:
            result = load_analysis_result(selected_run)
        except (FileNotFoundError, KeyError, ValueError) as exc:
            st.error(f"Unable to open this analysis: {exc}")
            return
        _render_result(result, f"Saved analysis: {_run_label(selected_run)}")
        return

    st.subheader("Capture Context")
    context_columns = st.columns(3)
    with context_columns[0]:
        handedness = st.selectbox(
            "Handedness",
            ["right", "left"],
            format_func=lambda value: value.title(),
        )
    with context_columns[1]:
        camera_view = st.selectbox(
            "Camera view",
            ["face_on", "down_the_line"],
            format_func=lambda value: value.replace("_", " ").title(),
        )
    with context_columns[2]:
        club_family = st.selectbox(
            "Club family",
            ["driver", "wood_or_hybrid", "iron", "wedge"],
            format_func=lambda value: value.replace("_", " / ").title(),
        )
    uploaded = st.file_uploader(
        "Upload a golf swing video",
        type=["mp4", "mov", "m4v", "avi"],
        accept_multiple_files=False,
    )
    if uploaded is None:
        st.info("Upload a video to run local pose analysis.")
        return

    run_button = st.button("Analyse swing", type="primary")
    if not run_button:
        active_run = st.session_state.get("active_run_dir")
        if active_run:
            try:
                result = load_analysis_result(Path(active_run))
                _render_result(result, f"Latest analysis: {_run_label(Path(active_run))}")
            except (FileNotFoundError, KeyError, ValueError):
                st.session_state.pop("active_run_dir", None)
        return

    OUTPUTS_DIR.mkdir(parents=True, exist_ok=True)
    run_dir = OUTPUTS_DIR / f"swing_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
    suffix = Path(uploaded.name).suffix or ".mp4"

    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        tmp.write(uploaded.getbuffer())
        temp_video = Path(tmp.name)

    progress_bar = st.progress(0.0)
    status = st.empty()

    def progress(message: str, value: float) -> None:
        status.write(message)
        progress_bar.progress(value)

    try:
        result = SwingAnalyser().analyse(
            temp_video,
            run_dir,
            progress_callback=progress,
            context=AnalysisContext(
                handedness=handedness,
                camera_view=camera_view,
                club_family=club_family,
            ),
        )
    except Exception as exc:  # pragma: no cover - Streamlit error path
        st.error(f"Analysis failed: {exc}")
        return
    finally:
        temp_video.unlink(missing_ok=True)

    st.session_state["active_run_dir"] = str(run_dir)
    _render_result(result, f"Analysis complete: {run_dir.name}", success=True)


def _render_result(result, title: str, success: bool = False) -> None:
    if success:
        st.success(title)
    else:
        st.caption(title)
    result = _render_phase_editor(result)
    _render_assessment(result)
    st.subheader("Annotated Replay")
    st.video(str(result.artifacts.annotated_video))
    _download_button("Download annotated video", result.artifacts.annotated_video, "video/mp4")

    left, right = st.columns(2)
    with left:
        st.subheader("Swing Phases")
        st.dataframe(
            [
                {
                    "Phase": phase.name,
                    "Frame": phase.frame_index,
                    "Timestamp": phase.timestamp_seconds,
                    "Confidence": phase.confidence,
                    "Method": phase.detection_method,
                }
                for phase in result.phases
            ],
            width="stretch",
        )
        _download_button("Download phases JSON", result.artifacts.phases_json, "application/json")

    with right:
        st.subheader("Metrics")
        st.dataframe(
            [
                {
                    "Metric": metric.name,
                    "Value": _format_metric_value(metric.value),
                    "Unit": metric.unit or "",
                    "Frame": metric.frame_index,
                }
                for metric in result.metrics.metrics.values()
            ],
            width="stretch",
        )
        _download_button("Download metrics JSON", result.artifacts.metrics_json, "application/json")

    st.subheader("Key Frames")
    keyframes = sorted(result.artifacts.keyframes_dir.glob("*.jpg"))
    if keyframes:
        columns = st.columns(min(4, len(keyframes)))
        for index, image_path in enumerate(keyframes):
            with columns[index % len(columns)]:
                st.image(str(image_path), caption=image_path.stem.replace("_", " ").title())
    else:
        st.write("No keyframes were exported.")

    st.subheader("Landmarks")
    st.write(
        {
            "frames": len(result.landmarks),
            "frames_with_pose": result.metrics.quality.get("frames_with_pose", 0),
            "pose_detection_rate": result.metrics.quality.get("pose_detection_rate", 0.0),
        }
    )
    _download_button("Download landmarks JSON", result.artifacts.landmarks_json, "application/json")


def _render_phase_editor(result):
    issues = result.metrics.quality.get("phase_quality_issues", [])
    outdated = _uses_superseded_phase_detection(result)
    with st.expander("Review phase timing", expanded=bool(issues) or outdated):
        if outdated:
            st.warning(
                "This saved analysis used earlier timing or evidence calculations that "
                "can attach findings to the wrong swing phase. Re-run detection or confirm frames."
            )
        if issues:
            st.warning(" ".join(issues))
            st.write(
                "Improvement guidance is withheld until the phase timing is reliable "
                "or you confirm the key frames."
            )
        else:
            st.caption(
                "Automatic markers are ordered. Confirm them when the phase labels "
                "do not match the replay."
            )
        if st.button(
            "Re-run automatic phase detection",
            key=f"redetect_{result.artifacts.output_dir.name}",
        ):
            result = SwingAnalyser().redetect_phases(result)
            issues = result.metrics.quality.get("phase_quality_issues", [])
            if issues:
                st.warning("Automatic re-detection remains uncertain. Confirm the key frames below.")
            else:
                st.success("Automatic phase detection rerun and results regenerated.")
        by_name = {phase.name: phase.frame_index for phase in result.phases}
        maximum = max(0, result.metadata.frame_count - 1)
        with st.form(f"phase_markers_{result.artifacts.output_dir.name}"):
            columns = st.columns(4)
            with columns[0]:
                address = st.number_input(
                    "Address frame",
                    min_value=0,
                    max_value=maximum,
                    value=min(by_name.get("Address", 0), maximum),
                    step=1,
                )
            with columns[1]:
                top = st.number_input(
                    "Top frame",
                    min_value=0,
                    max_value=maximum,
                    value=min(by_name.get("Top of backswing", 0), maximum),
                    step=1,
                )
            with columns[2]:
                impact = st.number_input(
                    "Impact frame",
                    min_value=0,
                    max_value=maximum,
                    value=min(by_name.get("Impact approximation", 0), maximum),
                    step=1,
                )
            with columns[3]:
                finish = st.number_input(
                    "Finish frame",
                    min_value=0,
                    max_value=maximum,
                    value=min(by_name.get("Finish", maximum), maximum),
                    step=1,
                )
            submitted = st.form_submit_button("Confirm phase markers")
        if submitted:
            try:
                result = SwingAnalyser().confirm_phase_markers(
                    result,
                    int(address),
                    int(top),
                    int(impact),
                    int(finish),
                )
            except ValueError as exc:
                st.error(str(exc))
            else:
                st.success("Phase markers confirmed. Metrics and guidance were regenerated.")
    return result


def _render_assessment(result) -> None:
    st.subheader("Improvement Areas")
    assessment = result.assessment
    if assessment is None:
        st.info(
            "Improvement guidance is unavailable for this saved analysis because "
            "capture context was not recorded. New analyses include contextual feedback."
        )
        return
    if _uses_superseded_phase_detection(result):
        st.warning(
            "Improvement guidance is withheld because this saved run used superseded "
            "phase timing or evidence calculations. Use Review phase timing to regenerate it."
        )
        return

    context = assessment.context
    st.caption(
        f"{context.handedness.title()}-handed | "
        f"{context.camera_view.replace('_', ' ').title()} view | "
        f"{context.club_family.replace('_', ' / ').title()} | "
        f"Rubric v{assessment.rubric_version}"
    )
    if assessment.quality_limitations:
        st.warning(" ".join(assessment.quality_limitations))
    attention = [
        finding
        for finding in assessment.findings
        if finding.status == "needs_attention"
    ][:3]
    within = [
        finding
        for finding in assessment.findings
        if finding.status == "within_reference"
    ]
    insufficient = [
        finding
        for finding in assessment.findings
        if finding.status == "insufficient_data"
    ]

    if attention:
        for finding in attention:
            observed = _format_finding_value(finding)
            st.warning(
                f"{finding.reference.name}: observed {observed}; "
                f"reference {finding.expected}."
            )
            st.write(finding.reference.rationale)
            st.write(f"Correction cue: {finding.reference.correction_cue}")
            evidence = (
                result.artifacts.keyframes_dir / finding.evidence_keyframe
                if finding.evidence_keyframe
                else None
            )
            with st.expander(
                f"Evidence and source - {finding.phase_name or 'checkpoint'}"
            ):
                if evidence and evidence.exists():
                    st.image(str(evidence), caption=f"Frame {finding.frame_index}")
                st.caption(
                    f"Confidence: {finding.confidence:.2f}. "
                    f"{finding.reference.source_note}"
                )
                st.link_button(
                    f"Source: {finding.reference.source_title}",
                    finding.reference.source_url,
                )
    elif not insufficient:
        st.success("No assessed checkpoint is outside the current reference ranges.")

    if within:
        st.markdown("**Within Reference**")
        st.dataframe(
            [
                {
                    "Checkpoint": finding.reference.name,
                    "Observed": _format_finding_value(finding),
                    "Reference": finding.expected,
                    "Confidence": finding.confidence,
                }
                for finding in within
            ],
            width="stretch",
        )
    if insufficient:
        st.markdown("**Not Assessed Reliably**")
        for finding in insufficient:
            st.write(f"{finding.reference.name}: {finding.note}")
    if result.artifacts.assessment_json:
        _download_button(
            "Download assessment JSON",
            result.artifacts.assessment_json,
            "application/json",
        )


def _format_finding_value(finding) -> str:
    if finding.observed_value is None:
        return "unavailable"
    return f"{finding.observed_value:g} {finding.reference.unit}"


def _uses_superseded_phase_detection(result) -> bool:
    superseded_methods = {
        "minimum_wrist_y_coordinate",
        "closest_wrist_return_to_address_after_top",
        "detected_active_swing_onset",
    }
    return result.assessment is not None and (
        not result.metrics.quality.get("phase_scoped_metrics", False)
        or any(phase.detection_method in superseded_methods for phase in result.phases)
    )


def _run_label(run_dir: Path) -> str:
    try:
        timestamp = datetime.strptime(run_dir.name, "swing_%Y%m%d_%H%M%S")
        return timestamp.strftime("%d %b %Y, %H:%M:%S")
    except ValueError:
        return run_dir.name


def _download_button(label: str, path: Path, mime: str) -> None:
    if path.exists():
        st.download_button(
            label=label,
            data=path.read_bytes(),
            file_name=path.name,
            mime=mime,
        )


def _format_metric_value(value: object) -> str:
    if isinstance(value, float):
        return str(round(value, 4))
    if isinstance(value, list):
        return json.dumps(value[:3]) + (" ..." if len(value) > 3 else "")
    return "" if value is None else str(value)


if __name__ == "__main__":
    main()
