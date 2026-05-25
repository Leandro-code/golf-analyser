# Opt-In AI Visual Review for Swing Analyses

## Summary
Add a user-triggered `AI Visual Review` feature to the Streamlit workbench. It sends selected trusted keyframes plus compact deterministic context to the OpenAI Responses API, saves a structured review with the run, and displays it separately from the existing evidence-based `Improvement Areas`.

The deterministic rubric remains authoritative. AI review is supplementary visual interpretation and must not generate coaching when phase timing or pose evidence is unreliable.

## Key Changes
- Add the `openai` Python dependency and configure access through `OPENAI_API_KEY`; support `GOLF_ANALYSER_OPENAI_MODEL` with default `gpt-5.5`.
- Add an AI review service invoked explicitly from the UI after local analysis, never automatically during `analyse(...)`.
- Only enable generation when the run has contextual assessment data, `phase_scoped_metrics` is current, no phase/pose quality limitations block guidance, and required keyframes exist.
- Send:
  - Capture context: handedness, camera view, club family, swing type.
  - Trusted phase metadata: name, frame index, timestamp, confidence, detection method.
  - Existing annotated phase JPEGs for `Address`, `Top of backswing`, `Impact approximation`, and `Finish`.
  - Include `Downswing` only when an applicable down-the-line finding depends on it.
  - Compact rubric results: applicable finding statuses, observed values, expected ranges, phase names, confidence, and limitations.
- Do not send raw landmarks, the full wrist trajectory, the full video, or duplicate `finding_*.jpg` images in v1.
- Prompt the model to assess only visible 2D pose cues, relate observations to named phase images and supplied metrics, state uncertainty, and avoid claims about club path, clubface, strike quality, ball flight, power, score, diagnosis, or professional comparison.
- Use the Responses API with image inputs and Structured Outputs. Send requests with `store=False`.

## Interfaces And Persistence
- Extend result contracts with an optional saved AI review record and an optional `ai_review.json` artifact.
- Define structured response content with:
  - `summary: str`
  - `observations: list` of `phase_name`, `observation`, `evidence_visible`, `confidence`, and optional `related_metric_key`
  - `priorities: list` of at most three `focus`, `practice_cue`, and supporting phase names
  - `limitations: list[str]`
- Wrap model content in persisted metadata:
  - schema/prompt version
  - model identifier
  - generation timestamp
  - context snapshot
  - reviewed phase/frame mapping
  - quality snapshot
  - an evidence fingerprint derived from phase frames, detection methods, applicable metric values, and keyframe selection
- Load `ai_review.json` opportunistically so existing and legacy runs remain readable without it.
- When phase markers are re-detected or confirmed, retain any previous AI review file for history but mark it stale through fingerprint mismatch; do not display its recommendations as current until the user regenerates the review.

## Interface Behavior
- Render `AI Visual Review` below the deterministic assessment as a distinct section labelled as model-generated interpretation.
- When eligible and no current saved review exists, show `Generate AI visual review` with a disclosure that selected still images and measured context will be sent to OpenAI.
- When a current saved review exists, render its summary, phase-linked observations, practice priorities, limitations, model name, and generation time; allow explicit regeneration.
- When review is blocked, explain the reason: unreliable timing, insufficient pose evidence, superseded saved analysis, missing API key, missing keyframes, or stale review after phase changes.
- Keep API failures isolated: a failed remote review must not alter replay, metrics, deterministic findings, or saved local analysis usability.
- Update documentation and output structure to describe `ai_review.json`, API-key setup, opt-in data sharing, and the restricted interpretation scope.

## Test Plan
- Model and persistence tests: optional AI review serialization, loading runs with and without `ai_review.json`, and legacy compatibility.
- Service tests with a mocked OpenAI client: correct keyframe selection, compact context payload, Structured Output parsing, `store=False`, model override behavior, and saved artifact creation.
- Gating tests: no request when timing is invalid, evidence is insufficient, phase-scoped metrics are stale, assessment is absent, or required keyframes are missing.
- Invalidation tests: phase re-detection/manual confirmation changes the fingerprint and hides a previous review until regeneration.
- Streamlit tests: explicit generation control, disclosure text, separate rendering from deterministic findings, saved-history rendering, stale-review warning, missing credential handling, and API-error handling.
- Run the full suite with `.venv/bin/python -m pytest`; tests must not call the live OpenAI API or modify existing user runs in `outputs/`.

## Assumptions
- The selected first version is built into Streamlit, user-triggered, persisted in history, and displayed separately from deterministic recommendations.
- Existing annotated phase keyframes are sufficient input for v1; generation of clean unannotated stills or video submission is deferred.
- Phase reliability and pose-quality gating applies equally to AI-generated coaching: unreliable inputs do not produce recommendations.
- As verified on May 26, 2026, OpenAI guidance identifies `gpt-5.5` as the current flagship and recommends the Responses API for new reasoning workflows; the model remains environment-configurable for later cost/quality evaluation.
- API privacy messaging will be based on OpenAI’s documented data controls: API inputs are not used for training by default, and standard abuse-monitoring retention may apply.
- Relevant official references: [Images and vision](https://developers.openai.com/api/docs/guides/images-vision), [Structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [Responses guidance](https://developers.openai.com/api/docs/guides/migrate-to-responses), [Data controls](https://developers.openai.com/api/docs/guides/your-data), and [Latest model guidance](https://developers.openai.com/api/docs/guides/latest-model.md).
