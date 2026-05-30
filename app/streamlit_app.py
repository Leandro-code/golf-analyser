from __future__ import annotations

import json
import os
import sys
import tempfile
from datetime import datetime
from pathlib import Path

import streamlit as st
from dotenv import load_dotenv

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from analysis import SwingAnalyser
from analysis.llm_assessment import (
    LLMAssessmentError,
    generate_llm_assessment,
    llm_assessment_eligibility_issue,
    llm_assessment_is_current,
)
from analysis.models import AnalysisContext
from analysis.storage import list_analysis_runs, load_analysis_result
from analysis.visualise import browser_playback_video

load_dotenv(PROJECT_ROOT / ".env")
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
    _render_llm_assessment(result)
    _render_measured_pose_data(result)
    st.subheader("Annotated Replay")
    try:
        with st.spinner("Preparing replay for browser playback..."):
            playback_video = browser_playback_video(result.artifacts.annotated_video)
    except RuntimeError as exc:
        st.warning(f"Unable to prepare in-app playback: {exc}")
        playback_video = result.artifacts.annotated_video
    st.video(str(playback_video), format="video/mp4")
    _download_button(
        "Download annotated video",
        result.artifacts.annotated_video,
        "video/mp4",
        key="download_annotated_video",
    )

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
        _download_button(
            "Download phases JSON",
            result.artifacts.phases_json,
            "application/json",
            key="download_phases_json",
        )

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
        _download_button(
            "Download metrics JSON",
            result.artifacts.metrics_json,
            "application/json",
            key="download_metrics_json",
        )

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
    _download_button(
        "Download landmarks JSON",
        result.artifacts.landmarks_json,
        "application/json",
        key="download_landmarks_json",
    )


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


def _render_measured_pose_data(result) -> None:
    with st.expander("Measured Pose Data", expanded=False):
        st.subheader("Measured Pose Data")
        if result.assessment is None:
            st.info(
                "Capture context was not recorded for this saved analysis. "
                "Raw metrics are still shown when available."
            )
        if _uses_superseded_phase_detection(result):
            st.warning(
                "This saved analysis used superseded phase timing or evidence "
                "calculations. Use Review phase timing to regenerate it before "
                "relying on the measurements."
            )
        quality = result.metrics.quality
        st.write(
            {
                "frames_with_pose": quality.get("frames_with_pose", 0),
                "frames_total": quality.get("frames_total", 0),
                "pose_detection_rate": quality.get("pose_detection_rate", 0.0),
                "phase_markers_confirmed": quality.get("phase_markers_confirmed", False),
            }
        )
        st.dataframe(
            [
                {
                    "Metric": metric.name,
                    "Value": _format_metric_value(metric.value),
                    "Unit": metric.unit or "",
                    "Frame": metric.frame_index,
                    "Description": metric.description,
                }
                for metric in result.metrics.metrics.values()
                if metric.name != "Wrist path trajectory"
            ],
            width="stretch",
        )
        if result.artifacts.metrics_json:
            _download_button(
                "Download metrics JSON",
                result.artifacts.metrics_json,
                "application/json",
                key="download_measured_pose_metrics_json",
            )


def _render_llm_assessment(result) -> None:
    st.subheader("AI Swing Assessment")
    st.caption(
        "Model-generated swing assessment based on selected stills, "
        "local pose measurements, and local quality checks."
    )
    issue = llm_assessment_eligibility_issue(result)
    assessment_is_current = llm_assessment_is_current(result)
    if result.llm_assessment is not None and not assessment_is_current:
        st.warning(
            "A saved AI swing assessment no longer matches the current phase markers "
            "or measurements. Generate a new assessment to see updated guidance."
        )
    if issue is not None:
        st.info(issue)
        return

    st.caption(
        "Generation sends selected labelled stills and measured pose data to OpenAI. "
        "API data is not used for training by default; standard abuse-monitoring "
        "retention may apply. No deterministic reference ranges are sent to the model."
    )
    if not os.environ.get("OPENAI_API_KEY"):
        st.info("Set OPENAI_API_KEY in the local .env file to enable AI swing assessment.")
        return
    if result.ai_review is not None and result.llm_assessment is None:
        st.info(
            "This run has an older AI visual review. Generate an AI swing assessment "
            "to create the new primary report format."
        )

    label = (
        "Regenerate AI swing assessment"
        if assessment_is_current
        else "Generate AI swing assessment"
    )
    if st.button(label, key=f"llm_assessment_{result.artifacts.output_dir.name}"):
        with st.spinner("Requesting AI swing assessment..."):
            try:
                result = generate_llm_assessment(result)
            except LLMAssessmentError as exc:
                st.error(str(exc))
            else:
                assessment_is_current = True
                st.success("AI swing assessment generated and saved with this analysis.")

    if not assessment_is_current or result.llm_assessment is None:
        return
    assessment = result.llm_assessment
    st.write(assessment.content.overview)
    if assessment.content.priorities:
        st.markdown("**Practice Priorities**")
        for priority in assessment.content.priorities:
            with st.expander(
                f"{priority.title} - AI-generated - confidence {priority.confidence:.2f}",
                expanded=True,
            ):
                st.write(priority.rationale)
                st.write(f"Practice cue: {priority.practice_cue}")
                _render_llm_frame_evidence(result, priority.supporting_frame_ids)
                if priority.related_metric_keys:
                    st.caption(
                        "Related metrics: " + ", ".join(priority.related_metric_keys)
                    )
    if assessment.content.strengths:
        st.markdown("**Strengths**")
        for strength in assessment.content.strengths:
            st.write(strength)
    if assessment.content.observations:
        st.markdown("**Visible Observations**")
        for observation in assessment.content.observations:
            with st.expander(
                f"{observation.title} - confidence {observation.confidence:.2f}"
            ):
                st.write(observation.observation)
                _render_llm_frame_evidence(result, observation.supporting_frame_ids)
                if observation.related_metric_keys:
                    st.caption(
                        "Related metrics: " + ", ".join(observation.related_metric_keys)
                    )
    if assessment.content.limitations:
        st.markdown("**Limitations**")
        for limitation in assessment.content.limitations:
            st.write(limitation)
    st.caption(f"Generated with {assessment.model} at {assessment.generated_at}.")
    if result.artifacts.llm_assessment_json:
        _download_button(
            "Download AI swing assessment JSON",
            result.artifacts.llm_assessment_json,
            "application/json",
            key="download_llm_assessment_json",
        )


def _render_llm_frame_evidence(result, frame_ids: list[str]) -> None:
    assessment = result.llm_assessment
    if assessment is None or not frame_ids or result.artifacts.llm_frames_dir is None:
        return
    by_id = {frame.frame_id: frame for frame in assessment.submitted_frames}
    frames = [by_id[frame_id] for frame_id in frame_ids if frame_id in by_id]
    if not frames:
        return
    columns = st.columns(min(3, len(frames)))
    for index, frame in enumerate(frames):
        image_path = result.artifacts.llm_frames_dir / frame.image_file
        if image_path.exists():
            with columns[index % len(columns)]:
                st.image(
                    str(image_path),
                    caption=f"{frame.frame_id}: {', '.join(frame.phase_relations)}",
                )


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


def _download_button(label: str, path: Path, mime: str, key: str) -> None:
    if path.exists():
        st.download_button(
            label=label,
            data=path.read_bytes(),
            file_name=path.name,
            mime=mime,
            key=f"{key}_{path}",
        )


def _format_metric_value(value: object) -> str:
    if isinstance(value, float):
        return str(round(value, 4))
    if isinstance(value, list):
        return json.dumps(value[:3]) + (" ..." if len(value) > 3 else "")
    return "" if value is None else str(value)


if __name__ == "__main__":
    main()
