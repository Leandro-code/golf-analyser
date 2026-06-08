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
from analysis.phases import (
    ADDRESS_PHASE,
    FINISH_PHASE,
    IMPACT_PHASE,
    P3_PHASE,
    P5_PHASE,
    P6_PHASE,
    P8_PHASE,
    PHASE_NAMES,
    TAKEAWAY_PHASE,
    TOP_PHASE,
    phase_by_name,
)
from analysis.storage import list_analysis_runs, load_analysis_result
from analysis.visualise import browser_playback_video

load_dotenv(PROJECT_ROOT / ".env")
OUTPUTS_DIR = Path(os.environ.get("GOLF_ANALYSER_OUTPUTS_DIR", PROJECT_ROOT / "outputs"))
ASSESSMENT_EVIDENCE_IMAGE_WIDTH = 220
KEYFRAME_IMAGE_WIDTH = 240
MAX_REPLAY_VIDEO_HEIGHT = 560
MAX_REPLAY_VIDEO_WIDTH = 760


def main() -> None:
    st.set_page_config(page_title="AI Swing Review", layout="wide")
    st.title("AI Swing Review")

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

    st.subheader("Review a new swing")
    uploaded = st.file_uploader(
        "Upload a golf swing video",
        type=["mp4", "mov", "m4v", "avi"],
        accept_multiple_files=False,
    )

    st.markdown("**Capture details**")
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
    if uploaded is None:
        st.info("Upload a video to start a local swing review.")
        return

    run_button = st.button("Review swing", type="primary")
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

    issues = result.metrics.quality.get("phase_quality_issues", [])
    outdated = _uses_superseded_phase_detection(result)
    if issues or outdated:
        st.warning(
            "Swing markers need review before coaching can be relied on. "
            "Open Advanced to re-run detection or confirm the key frames."
        )

    assessment_tab, replay_tab, evidence_tab, advanced_tab = st.tabs(
        ["Assessment", "Replay", "Evidence", "Advanced"]
    )
    with advanced_tab:
        result = _render_phase_editor(result, expanded=bool(issues) or outdated)
        _render_advanced_details(result)
    with assessment_tab:
        _render_llm_assessment(result)
    with replay_tab:
        _render_replay(result)
    with evidence_tab:
        _render_evidence(result)


def _render_replay(result) -> None:
    st.subheader("Annotated Replay")
    try:
        with st.spinner("Preparing replay for browser playback..."):
            playback_video = browser_playback_video(result.artifacts.annotated_video)
    except RuntimeError as exc:
        st.warning(f"Unable to prepare in-app playback: {exc}")
        playback_video = result.artifacts.annotated_video
    st.video(str(playback_video), format="video/mp4", width=_replay_video_width(result))
    _download_button(
        "Download annotated video",
        result.artifacts.annotated_video,
        "video/mp4",
        key="download_annotated_video",
    )


def _render_evidence(result) -> None:
    st.subheader("Evidence Frames")
    if result.llm_assessment is not None and llm_assessment_is_current(result):
        assessment = result.llm_assessment
        used_frame_ids = []
        for priority in assessment.content.priorities:
            used_frame_ids.extend(priority.supporting_frame_ids)
        for observation in assessment.content.observations:
            used_frame_ids.extend(observation.supporting_frame_ids)
        if used_frame_ids:
            st.markdown("**Frames referenced by the AI assessment**")
            _render_llm_frame_evidence(result, _unique_ordered(used_frame_ids))

    st.markdown("**Key swing positions**")
    keyframes = sorted(result.artifacts.keyframes_dir.glob("*.jpg"))
    if keyframes:
        columns = st.columns(min(4, len(keyframes)))
        for index, image_path in enumerate(keyframes):
            with columns[index % len(columns)]:
                st.image(
                    str(image_path),
                    caption=image_path.stem.replace("_", " ").title(),
                    width=KEYFRAME_IMAGE_WIDTH,
                )
    else:
        st.write("No keyframes were exported.")

    quality = result.metrics.quality
    with st.expander("Evidence quality", expanded=False):
        st.write(
            {
                "frames_with_pose": quality.get("frames_with_pose", 0),
                "frames_total": quality.get("frames_total", 0),
                "pose_detection_rate": quality.get("pose_detection_rate", 0.0),
                "phase_markers_confirmed": quality.get("phase_markers_confirmed", False),
            }
        )


def _render_advanced_details(result) -> None:
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

    _render_measured_pose_data(result)

    with st.expander("Landmarks", expanded=False):
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


def _render_phase_editor(result, expanded: bool = False):
    issues = result.metrics.quality.get("phase_quality_issues", [])
    outdated = _uses_superseded_phase_detection(result)
    with st.expander("Review swing markers", expanded=expanded):
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
        if _uses_superseded_phase_detection(result) and st.button(
            "Reassess with 9-phase model",
            key=f"reassess_{result.artifacts.output_dir.name}",
        ):
            try:
                result = SwingAnalyser().reassess_with_current_phase_model(result)
            except Exception as exc:  # pragma: no cover - Streamlit error path
                st.error(f"Unable to reassess this run: {exc}")
            else:
                st.success("Run reassessed with the 9-phase model.")
        defaults = _phase_editor_defaults(result)
        maximum = max(0, result.metadata.frame_count - 1)
        with st.form(f"phase_markers_{result.artifacts.output_dir.name}"):
            phase_values = []
            columns = st.columns(3)
            for index, phase_name in enumerate(PHASE_NAMES):
                with columns[index % len(columns)]:
                    phase_values.append(
                        st.number_input(
                            f"{phase_name} frame",
                            min_value=0,
                            max_value=maximum,
                            value=min(defaults.get(phase_name, 0), maximum),
                            step=1,
                        )
                    )
            submitted = st.form_submit_button("Confirm phase markers")
        if submitted:
            try:
                result = SwingAnalyser().confirm_phase_markers(
                    result,
                    *(int(value) for value in phase_values),
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
        st.caption(
            "The assessment uses labelled stills, local pose measurements, and local "
            "quality checks. It does not infer clubface, strike quality, or ball flight."
        )
        return
    assessment = result.llm_assessment
    st.write(assessment.content.overview)
    if assessment.content.priorities:
        st.markdown("**Practice Priorities**")
        for index, priority in enumerate(assessment.content.priorities, start=1):
            with st.container(border=True):
                heading, confidence = st.columns([4, 1])
                with heading:
                    st.markdown(f"**{index}. {priority.title}**")
                with confidence:
                    st.caption(_confidence_label(priority.confidence))
                st.write(priority.rationale)
                st.info(f"Practice cue: {priority.practice_cue}")
                _render_priority_drilldown(result, priority)
                _render_llm_frame_evidence(result, priority.supporting_frame_ids)
                if priority.related_metric_keys:
                    with st.expander("Supporting measurements", expanded=False):
                        _render_related_metrics(result, priority.related_metric_keys)
    if assessment.content.strengths:
        st.markdown("**Strengths**")
        for strength in assessment.content.strengths:
            st.success(strength)
    if assessment.content.observations:
        st.markdown("**Visible Observations**")
        for observation in assessment.content.observations:
            with st.expander(
                f"{observation.title} - {_confidence_label(observation.confidence)}"
            ):
                st.write(observation.observation)
                _render_llm_frame_evidence(result, observation.supporting_frame_ids)
                if observation.related_metric_keys:
                    _render_related_metrics(result, observation.related_metric_keys)
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


def _render_priority_drilldown(result, priority) -> None:
    with st.expander("Explore this priority", expanded=False):
        st.markdown("**What this means**")
        st.write(priority.explanation or priority.rationale)

        st.markdown("**Drills**")
        for drill in _priority_drills(priority):
            st.markdown(f"- {drill}")

        st.markdown("**Practice plan**")
        for step in _priority_practice_plan(result, priority):
            st.markdown(f"- {step}")

        st.caption(
            "Use the evidence frames and measurements below to compare the same "
            "positions after another recorded swing."
        )


def _priority_drills(priority) -> list[str]:
    if priority.drills:
        return priority.drills
    return [
        f"Make slow-motion rehearsals using this cue: {priority.practice_cue}",
        "Pause at the referenced phase positions and check the feel against the replay.",
        "Hit a short block of easy swings while keeping attention on this one priority.",
    ]


def _priority_practice_plan(result, priority) -> list[str]:
    if priority.practice_plan:
        return priority.practice_plan
    camera_view = _practice_camera_view(result)
    progress_check = (
        "Compare the supporting measurements after a new analysis run."
        if priority.related_metric_keys
        else "Compare the same evidence frames after a new analysis run."
    )
    return [
        "Start without a ball and make five slow rehearsals focused only on this cue.",
        f"Record two or three rehearsals from the {camera_view} so the frames are comparable.",
        "Hit five easy balls at reduced speed before adding normal tempo.",
        progress_check,
    ]


def _practice_camera_view(result) -> str:
    if result.assessment is None:
        return "same camera view"
    return f"{result.assessment.context.camera_view.replace('_', ' ')} view"


def _render_related_metrics(result, metric_keys: list[str]) -> None:
    rows = []
    for key in metric_keys:
        metric = result.metrics.metrics.get(key)
        if metric is None:
            continue
        rows.append(
            {
                "Metric": metric.name,
                "Value": _format_metric_value(metric.value),
                "Unit": metric.unit or "",
                "Frame": metric.frame_index,
            }
        )
    if rows:
        st.dataframe(rows, width="stretch", hide_index=True)
    else:
        st.caption("No related measurements are available for this run.")


def _render_llm_frame_evidence(result, frame_ids: list[str]) -> None:
    assessment = result.llm_assessment
    if assessment is None or not frame_ids or result.artifacts.llm_frames_dir is None:
        return
    by_id = {frame.frame_id: frame for frame in assessment.submitted_frames}
    frames = [by_id[frame_id] for frame_id in frame_ids if frame_id in by_id]
    if not frames:
        return
    columns = st.columns(min(4, len(frames)))
    for index, frame in enumerate(frames):
        image_path = result.artifacts.llm_frames_dir / frame.image_file
        if image_path.exists():
            with columns[index % len(columns)]:
                st.image(
                    str(image_path),
                    caption=f"{frame.frame_id}: {', '.join(frame.phase_relations)}",
                    width=ASSESSMENT_EVIDENCE_IMAGE_WIDTH,
                )


def _confidence_label(confidence: float) -> str:
    if confidence >= 0.75:
        return "High confidence"
    if confidence >= 0.5:
        return "Medium confidence"
    return "Low confidence"


def _replay_video_width(result) -> int:
    metadata = result.metadata
    if metadata.width <= 0 or metadata.height <= 0:
        return MAX_REPLAY_VIDEO_WIDTH
    aspect_ratio = metadata.width / metadata.height
    height_limited_width = round(MAX_REPLAY_VIDEO_HEIGHT * aspect_ratio)
    return max(240, min(MAX_REPLAY_VIDEO_WIDTH, height_limited_width))


def _unique_ordered(values: list[str]) -> list[str]:
    seen = set()
    unique = []
    for value in values:
        if value not in seen:
            seen.add(value)
            unique.append(value)
    return unique


def _uses_superseded_phase_detection(result) -> bool:
    superseded_methods = {
        "minimum_wrist_y_coordinate",
        "closest_wrist_return_to_address_after_top",
        "detected_active_swing_onset",
    }
    phases_by_name = phase_by_name(result.phases)
    return (
        not result.metrics.quality.get("phase_scoped_metrics", False)
        or any(name not in phases_by_name for name in PHASE_NAMES)
        or any(phase.detection_method in superseded_methods for phase in result.phases)
    )


def _phase_editor_defaults(result) -> dict[str, int]:
    by_name = phase_by_name(result.phases)
    maximum = max(0, result.metadata.frame_count - 1)
    if all(name in by_name for name in PHASE_NAMES):
        return {
            name: min(by_name[name].frame_index, maximum)
            for name in PHASE_NAMES
        }
    fallback_address = result.phases[0].frame_index if result.phases else 0
    address = min(by_name.get(ADDRESS_PHASE).frame_index if by_name.get(ADDRESS_PHASE) else fallback_address, maximum)
    top = min(
        by_name.get(TOP_PHASE).frame_index
        if by_name.get(TOP_PHASE)
        else max(address + 1, round(maximum * 0.4)),
        maximum,
    )
    impact = min(
        by_name.get(IMPACT_PHASE).frame_index
        if by_name.get(IMPACT_PHASE)
        else max(top + 1, round(maximum * 0.72)),
        maximum,
    )
    finish = min(
        by_name.get(FINISH_PHASE).frame_index
        if by_name.get(FINISH_PHASE)
        else maximum,
        maximum,
    )
    return {
        ADDRESS_PHASE: address,
        TAKEAWAY_PHASE: _between(address, top, 0.25),
        P3_PHASE: _between(address, top, 0.65),
        TOP_PHASE: top,
        P5_PHASE: _between(top, impact, 0.32),
        P6_PHASE: _between(top, impact, 0.62),
        IMPACT_PHASE: impact,
        P8_PHASE: _between(impact, finish, 0.45),
        FINISH_PHASE: finish,
    }


def _between(start: int, end: int, fraction: float) -> int:
    return int(round(start + (end - start) * fraction))


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
