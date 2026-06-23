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

To enable the optional AI swing assessment, create a local `.env` file from `.env.example`
and set `OPENAI_API_KEY`. The `.env` file is git-ignored. The default model is
`gpt-5.5`; override `GOLF_ANALYSER_OPENAI_MODEL` to evaluate another
vision-capable model.

## Run the UI

```bash
.venv/bin/streamlit run app/streamlit_app.py
```

Choose handedness, camera view, and club family, then upload a full-swing video and click `Analyse swing`. Generated runs are written under `outputs/swing_<timestamp>/`. Use the `History` view to reopen any completed analysis stored in that directory.

For newly analysed swings, the app calculates local pose metrics and quality checks. When requested, the AI swing assessment becomes the primary user-facing report using selected stills and raw measured context. Pose-only analysis does not assess ball flight, strike quality, clubface, or club path.

Phase timing is detected as an ordered sequence: address motion, the first backswing reversal, return through the strike area, and the first finish position. Open `Review phase timing` on any result to rerun automatic phase detection or confirm `Address`, `Top`, `Impact`, and `Finish` frame markers manually. Metrics, feedback, keyframes, and the annotated replay are regenerated from confirmed markers. Guidance is withheld when automatic timing is implausible or from saved runs produced by the earlier timing detector until they are regenerated.

Annotated replays are encoded as browser-compatible H.264 for playback directly in
the Streamlit app. When an older saved run contains OpenCV MP4V output, the UI
creates a temporary H.264 playback copy without overwriting the saved run.

After reliable phase timing is available, click `Generate AI swing assessment` to
send selected phase stills, nearby transition frames, raw pose measurements,
and quality metadata to the OpenAI Responses API. The returned assessment
is saved as the primary report until its phase evidence becomes stale following
phase regeneration. Deterministic reference ranges are not sent to the model;
raw measurements remain available under `Measured Pose Data`.

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
  llm_assessment.json   # present after an opt-in AI swing assessment
  llm_frames/           # exact still images submitted to the LLM
  ai_review.json        # legacy optional supplementary review artifact
  keyframes/
```

Historical runs created before contextual feedback remain viewable, but do not receive retrospective improvement guidance.

## Run Tests

```bash
.venv/bin/python -m pytest
```

The project disables pytest output capture in `pytest.ini` because MediaPipe/OpenGL logging can break pytest's default capture backend in some WSL temp-directory setups.

## Run the Dev Environment

For mobile/API development, start the backend and client in separate terminals.

Terminal 1, from the repo root:

```bash
cd /home/larranz/projects/golf-analyser
.venv/bin/uvicorn api.main:app --host 0.0.0.0 --port 8000 --reload
```

Check the API at <http://localhost:8000/health>.

Terminal 2, for Expo:

```bash
cd /home/larranz/projects/golf-analyser/mobile
EXPO_PUBLIC_API_BASE_URL=http://localhost:8000 npm start
```

The API loads `OPENAI_API_KEY` and `GOLF_ANALYSER_OPENAI_MODEL` from the
repository `.env` file when it starts, so AI coaching can be requested from the
mobile client without exposing the OpenAI key to Expo.

For browser testing instead of Expo Go:

```bash
cd /home/larranz/projects/golf-analyser/mobile
EXPO_PUBLIC_API_BASE_URL=http://localhost:8000 npm run start -- --web
```

Then open <http://localhost:8081>.

Run `npm install` in `mobile/` only for first-time setup or after mobile
dependency changes. If Expo reports missing web support, run
`npx expo install react-native-web react-dom` once from `mobile/`.

## Run the API

The FastAPI backend wraps the same `analysis/` pipeline and stores completed runs
under `outputs/` by default. For local testing, start it from the repo root:

```bash
cd /home/larranz/projects/golf-analyser
.venv/bin/uvicorn api.main:app --host 0.0.0.0 --port 8000 --reload
```

Check that it is running at <http://localhost:8000/health>.

Set `GOLF_ANALYSER_API_TOKEN` to require `Authorization: Bearer <token>` on API
routes except `/health`. Set `GOLF_ANALYSER_OUTPUTS_DIR` to point the API at a
different local run directory.

Implemented endpoints:

```text
GET  /health
POST /analyses
GET  /analyses
GET  /analyses/{run_id}
GET  /analyses/{run_id}/status
GET  /analyses/{run_id}/artifacts/{artifact_name}
POST /analyses/{run_id}/phases/redetect
POST /analyses/{run_id}/phases/confirm
POST /analyses/{run_id}/llm-assessment
```

Analysis jobs run asynchronously in a local in-process worker. Completed jobs are
recoverable from saved `outputs/swing_*` directories.

## Run the Mobile Client

The first mobile client is in `mobile/` and uses Expo/React Native. Install
dependencies once before the first run:

```bash
cd /home/larranz/projects/golf-analyser/mobile
npm install
```

Start the mobile client with the API URL:

```bash
cd /home/larranz/projects/golf-analyser/mobile
EXPO_PUBLIC_API_BASE_URL=http://localhost:8000 npm start
```

For browser testing, start Expo in web mode:

```bash
cd /home/larranz/projects/golf-analyser/mobile
EXPO_PUBLIC_API_BASE_URL=http://localhost:8000 npm run start -- --web
```

Then open <http://localhost:8081>.

If Expo reports missing web support, install the web dependencies once:

```bash
cd /home/larranz/projects/golf-analyser/mobile
npx expo install react-native-web react-dom
```

For Expo Go on a physical phone, replace `localhost` with the computer's LAN IP,
for example `EXPO_PUBLIC_API_BASE_URL=http://192.168.1.23:8000`. Start Uvicorn
with `--host 0.0.0.0`, keep the phone and computer on the same Wi-Fi or hotspot,
and allow inbound port `8000` through the computer firewall if needed.

Set `EXPO_PUBLIC_API_TOKEN` when the backend token is enabled. The client can
choose/import a swing video, submit capture context, poll processing status,
show the annotated replay, explicitly generate the full AI coaching report,
reopen saved reports from history, and confirm all nine phase markers. A failed
upload keeps the selected clip available for a session-level retry.

## Scope

This milestone intentionally excludes club tracking, ball flight tracking, live camera capture, cloud deployment, authentication, custom ML models, scoring, pro comparison, and Android implementation. Optional AI swing assessment is limited to visible 2D still-image observations and local pose measurements.
