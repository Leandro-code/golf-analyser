# Golf Swing Analysis Workbench

A local-first Python workbench for validating a golf swing analysis pipeline. It uploads a swing video, extracts MediaPipe pose landmarks, detects coarse swing phases, calculates deterministic metrics, renders an annotated replay, and writes JSON artifacts for future API or Android integration.

## Setup

Create and use the project-local virtual environment:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/pip install -r requirements.txt
```

All third-party dependencies should be installed into `.venv`.

The first analysis run downloads the MediaPipe pose landmarker model into `.cache/mediapipe/`. For offline use, set `GOLF_ANALYSER_POSE_MODEL` to a local `.task` model file.

## Run the UI

```bash
.venv/bin/streamlit run app/streamlit_app.py
```

Choose handedness, camera view, and club family, then upload a full-swing video and click `Analyse swing`. Generated runs are written under `outputs/swing_<timestamp>/`. Use the `History` view to reopen any completed analysis stored in that directory.

For newly analysed swings, the app reports improvement areas against a versioned, evidence-based technique rubric. Findings include observed measurements, reference ranges, short correction cues, source disclosure, confidence, and supporting keyframes. Pose-only analysis does not assess ball flight, strike quality, clubface, or club path.

Phase timing is detected as an ordered sequence: address motion, the first backswing reversal, return through the strike area, and the first finish position. Open `Review phase timing` on any result to rerun automatic phase detection or confirm `Address`, `Top`, `Impact`, and `Finish` frame markers manually. Metrics, feedback, keyframes, and the annotated replay are regenerated from confirmed markers. Guidance is withheld when automatic timing is implausible or from saved runs produced by the earlier timing detector until they are regenerated.

## Output Structure

Each run writes:

```text
outputs/swing_<timestamp>/
  original.mp4
  annotated.mp4
  landmarks.json
  metrics.json
  phases.json
  assessment.json       # present for context-labelled analyses
  keyframes/
```

Historical runs created before contextual feedback remain viewable, but do not receive retrospective improvement guidance.

## Run Tests

```bash
.venv/bin/python -m pytest
```

The project disables pytest output capture in `pytest.ini` because MediaPipe/OpenGL logging can break pytest's default capture backend in some WSL temp-directory setups.

## Scope

This milestone intentionally excludes club tracking, ball flight tracking, live camera capture, cloud deployment, authentication, custom ML models, AI coaching, scoring, pro comparison, and Android implementation.
