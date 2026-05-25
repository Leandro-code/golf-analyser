```markdown
# Golf Swing Analysis Workbench

## Overview

Build a local-first golf swing analysis application that allows a user to upload a golf swing video, automatically analyse the swing using computer vision and pose estimation, and replay the swing with overlays, phase detection and biomechanical metrics.

The initial goal is not a production mobile app. The goal is to rapidly validate the swing analysis pipeline locally using Python and a lightweight web UI before eventually porting capture and playback to Android.

The system should process uploaded golf swing videos and output:

- An annotated replay video
- Detected swing phases
- Extracted body landmarks
- Simple biomechanical metrics
- Key swing frames
- Structured JSON output for future mobile/API integration

The architecture should prioritise:

- Fast iteration
- Local execution
- Clear separation between UI and analysis logic
- Extensibility for future Android integration
- Reusable analysis pipeline

---

# Technical Direction

## Core Stack

### Backend / Analysis

- Python 3.11+
- MediaPipe Pose
- OpenCV
- NumPy
- FFmpeg (optional for rendering/export)
- FastAPI (eventually)
- Streamlit for rapid local UI

### Future Mobile

- Android Kotlin
- CameraX
- Upload video to backend API
- Replay annotated analysis results

---

# Key Design Principles

## 1. Analysis-first architecture

The most important component is the analysis pipeline, not the mobile app.

The application should be structured so that:
- video analysis is independent of UI
- the same analysis engine can later be used by:
  - Streamlit
  - FastAPI
  - Android
  - batch processing
  - future web clients

---

## 2. Local-first development

Everything should initially run locally.

No authentication.
No cloud deployment.
No accounts.
No external storage.

Use local filesystem storage for:
- uploaded videos
- generated annotated videos
- extracted frames
- landmarks JSON
- metrics JSON

---

## 3. Incremental complexity

Start with deterministic and explainable analysis.

Avoid:
- AI-generated coaching
- custom ML models
- club tracking
- swing scoring
- pro comparison
- LLM feedback

Focus first on:
- reliable pose extraction
- swing phase detection
- replay experience
- useful metrics

---

# Functional Requirements

## Phase 1 — Basic Video Processing

The system should:

1. Upload a golf swing video
2. Process the video frame-by-frame
3. Run MediaPipe Pose detection
4. Extract body landmarks for each frame
5. Draw pose skeleton overlays
6. Save an annotated replay video
7. Save landmarks to JSON

### Output Example

outputs/swing_001/
- original.mp4
- annotated.mp4
- landmarks.json
- metrics.json
- keyframes/

---

## Phase 2 — Swing Phase Detection

Implement basic golf swing phase detection using wrist trajectory and body movement.

Target phases:
- Address
- Takeaway
- Top of backswing
- Downswing
- Impact approximation
- Follow-through
- Finish

Use simple deterministic heuristics initially.

Example:
- top of backswing = max wrist height or backswing extension
- impact = wrist returns near address/ball zone

Store phase frame indices and timestamps.

---

## Phase 3 — Metrics

Calculate simple biomechanical metrics including:

- Tempo ratio
- Head movement
- Spine angle at address
- Lead arm angle
- Hip sway
- Knee flex
- Wrist path trajectory

Metrics should be deterministic and derived directly from pose landmarks.

---

## Phase 4 — Local Replay UI

Create a lightweight local UI using Streamlit.

The UI should allow:
- Uploading a swing video
- Viewing processing status
- Watching annotated replay video
- Viewing key frames
- Viewing metrics
- Downloading output JSON/video

---

# Suggested Project Structure

golf-swing-lab/
- app/
  - streamlit_app.py
- api/
  - main.py
- analysis/
  - analyser.py
  - pose.py
  - phases.py
  - metrics.py
  - visualise.py
  - models.py
- outputs/
- samples/
- tests/
- requirements.txt
- README.md

---

# Analysis Pipeline

## Step 1 — Video Ingestion

- Load uploaded video
- Extract FPS and metadata
- Iterate through frames

## Step 2 — Pose Detection

Use MediaPipe Pose to extract:
- shoulders
- hips
- wrists
- elbows
- knees
- ankles
- head landmarks

Store:
- x/y coordinates
- confidence values
- timestamps

## Step 3 — Signal Processing

Apply smoothing to:
- wrist trajectories
- head movement
- body centre

Potential techniques:
- moving average
- Savitzky–Golay filter
- interpolation for missing landmarks

## Step 4 — Swing Segmentation

Identify swing phases using:
- wrist velocity
- wrist direction changes
- body rotation proxies
- trajectory extrema

## Step 5 — Visualisation

Generate:
- annotated video overlays
- pose skeleton
- angle annotations
- phase labels
- key frame exports

---

# Initial Non-Goals

Do NOT implement initially:
- club head tracking
- golf ball flight tracking
- cloud hosting
- real-time processing
- live camera feed
- user accounts
- AI coaching text generation
- professional swing comparison
- scoring/ranking systems
- custom neural network training

---

# Future Roadmap

## Future Phase — Android App

Eventually:
- Android app records swing
- Uploads video to analysis API
- Displays annotated replay and metrics

Potential stack:
- Kotlin
- Jetpack Compose
- CameraX

---

# Reference Projects

Use these repositories for inspiration and selective code reuse:

- GolfPosePro
- HeleenaRobert/golf-swing-analysis
- MediaPipe Pose examples

Do not tightly couple the project architecture to any single repository.

The goal is to build a clean reusable analysis pipeline while borrowing proven ideas for:
- pose extraction
- swing phase detection
- replay visualisation

---

# Success Criteria

The first meaningful milestone is:

“A user can upload a golf swing video locally and receive:
- an annotated replay video,
- detected swing phases,
- key frames,
- and simple biomechanical metrics.”

Once this works reliably, the project can evolve into:
- a backend API,
- a web product,
- or a native Android application.
```
