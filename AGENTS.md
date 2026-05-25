# Agent Context: Golf Swing Analysis Workbench

## Purpose

This repository is a local-first Python/Streamlit workbench for testing golf swing video analysis before any API or Android implementation. A user uploads a full-swing video, supplies capture context, receives an annotated replay, swing phases, metrics, and evidence-based improvement findings, and can reopen saved runs.

The system is deliberately deterministic and explainable. It does not provide AI-generated coaching, a swing score, clubface/path analysis, ball-strike conclusions, ball-flight analysis, pro comparison, accounts, or cloud deployment.

## Environment And Commands

- Use the project-local virtual environment at `.venv/` for all third-party Python packages.
- Install dependencies with `.venv/bin/pip install -r requirements.txt`.
- Run tests with `.venv/bin/python -m pytest`.
- Run the application with `.venv/bin/streamlit run app/streamlit_app.py`.
- The first real pose-analysis run downloads a MediaPipe `.task` model into `.cache/mediapipe/`; set `GOLF_ANALYSER_POSE_MODEL` for an offline model file.
- `pytest.ini` disables output capture because MediaPipe/OpenGL logging has caused capture failures in this WSL environment.

Do not remove or overwrite user analysis runs in `outputs/` unless explicitly asked. The directory is git-ignored application data and may contain important validation videos/results.

## Architecture

- `app/streamlit_app.py`: upload flow, required capture context, result/history UI, phase-review controls, downloads.
- `analysis/analyser.py`: orchestration through `SwingAnalyser.analyse(...)`, re-detection, and manual phase confirmation/regeneration.
- `analysis/pose.py`: MediaPipe Pose Landmarker wrapper; provides body landmarks, not golf-phase understanding.
- `analysis/phases.py`: ordered swing-phase detection and manual confirmed-marker construction.
- `analysis/metrics.py`: deterministic pose metrics and quality metadata.
- `analysis/assessment.py`: applies the technique rubric and suppresses untrustworthy findings.
- `analysis/rubric.v1.json`: versioned reference rules, thresholds, correction cues, and source attribution.
- `analysis/visualise.py`: pose overlays, phase labels, evidence/keyframe exports, annotated replay rendering.
- `analysis/storage.py`: reloads persisted analysis runs while supporting older runs without assessments.
- `analysis/models.py`: Pydantic models for the persisted/result contracts.

## Public Workflow And Artifacts

New analyses require:

- `handedness`: `right` or `left`
- `camera_view`: `face_on` or `down_the_line`
- `club_family`: `driver`, `wood_or_hybrid`, `iron`, or `wedge`
- `swing_type`: currently fixed to `full_swing`

The main entrypoint is:

```python
SwingAnalyser().analyse(video_path, output_dir, progress_callback=None, context=context)
```

Each contextual run writes:

```text
outputs/swing_<timestamp>/
  original.mp4
  annotated.mp4
  landmarks.json
  metrics.json
  phases.json
  assessment.json
  keyframes/
```

Legacy runs without `assessment.json` must remain readable and display replay/metrics without retrospective coaching.

## Critical Correctness Rules

MediaPipe tracks body landmarks only. The project's own phase logic determines golf timing, and this is the primary risk area.

- Never select `Top of backswing` from a global wrist-height maximum/minimum; high hands at finish previously caused a false backswing label.
- Automatic phases must be chronologically ordered: `Address < Top of backswing < Impact approximation < Finish`.
- The current automatic detector uses first ordered motion events: stable address, first qualified backswing reversal, strike-region transition, then first finish position.
- If phase timing is implausible, guidance must be withheld until markers are re-detected or user-confirmed.
- The UI's `Review phase timing` flow must continue to support re-detection and manual confirmation of `Address`, `Top`, `Impact`, and `Finish`, regenerating metrics, assessment JSON, keyframes, and annotated video.
- Phase-specific findings must use evidence from their stated phase. For example, impact head stability must point at the impact marker, not a maximum head movement at finish.
- Keep the `phase_scoped_metrics` quality marker; saved assessed runs lacking it are considered stale and must not show coaching until regenerated.
- Preserve quality gating: missing/weak pose evidence or invalid timing yields `insufficient_data`, not a corrective finding.

## Assessment Boundaries

- Rubric rules are data-driven in `analysis/rubric.v1.json`; do not hard-code thresholds or coaching text in Streamlit.
- Findings can include observed pose metric, reference range, evidence frame, confidence, rationale, and a concise correction cue.
- Any new rubric claim needs an attributable source and should be framed as a 2D pose proxy when it is not a directly validated measurement.
- Do not claim the pipeline knows club path, clubface angle, contact quality, shot outcome, power, or overall technique quality without a new validated signal.

## Testing Expectations

Run the full suite after changes. Add focused tests whenever changing phase selection, metric-to-frame evidence, persistence compatibility, or Streamlit history/review behavior.

Existing regression coverage includes:

- idle setup frames not being treated as the active swing;
- finish hands rising higher than the backswing without becoming `Top of backswing`;
- manual marker confirmation bypassing automatic timing uncertainty;
- handedness-aware lead-arm metrics;
- phase-scoped evidence not selecting post-swing extrema;
- assessment quality gating;
- legacy and assessed history rendering.

When modifying timing behavior, validate against the saved failure pattern where a finish frame was previously labelled as backswing/impact, while avoiding mutation of user output artifacts unless the requested task is to regenerate them.

