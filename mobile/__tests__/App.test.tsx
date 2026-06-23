import React from "react";
import { act, fireEvent, render, waitFor } from "@testing-library/react-native";

import App from "../App";

const mockCreateAnalysis = jest.fn();
const mockGetDocumentAsync = jest.fn();
const mockGetStatus = jest.fn();
const mockGetAnalysis = jest.fn();
const mockListAnalyses = jest.fn();
const mockConfirmPhases = jest.fn();
const mockCreateLLMAssessment = jest.fn();

jest.mock("expo-document-picker", () => ({
  getDocumentAsync: (...args: unknown[]) => mockGetDocumentAsync(...args),
}));

jest.mock("expo-av", () => ({
  Video: () => null,
  ResizeMode: { CONTAIN: "contain" },
}));

jest.mock("../src/api", () => {
  const actual = jest.requireActual("../src/api");
  return {
    ...actual,
    ApiClient: jest.fn().mockImplementation(() => ({
      createAnalysis: (...args: unknown[]) => mockCreateAnalysis(...args),
      getStatus: (...args: unknown[]) => mockGetStatus(...args),
      getAnalysis: (...args: unknown[]) => mockGetAnalysis(...args),
      listAnalyses: (...args: unknown[]) => mockListAnalyses(...args),
      confirmPhases: (...args: unknown[]) => mockConfirmPhases(...args),
      createLLMAssessment: (...args: unknown[]) => mockCreateLLMAssessment(...args),
      artifactSource: (path?: string) => ({ uri: `http://localhost:8000${path ?? ""}` }),
    })),
  };
});

beforeEach(() => {
  jest.clearAllMocks();
  mockGetDocumentAsync.mockResolvedValue({
    canceled: false,
    assets: [{ uri: "file:///swing.mp4", name: "swing.mp4", mimeType: "video/mp4" }],
  });
});

async function chooseVideo(view: ReturnType<typeof render>) {
  await act(async () => {
    fireEvent.press(view.getByText("Choose Video"));
  });
  await waitFor(() => expect(view.getByText("swing.mp4")).toBeTruthy());
}

test("shows a busy upload state before the API responds", async () => {
  mockCreateAnalysis.mockReturnValue(new Promise(() => undefined));
  const view = render(<App />);
  await chooseVideo(view);

  fireEvent.press(view.getByText("Upload And Analyse"));

  await waitFor(() => expect(view.getByText("Uploading video...")).toBeTruthy());
  expect(view.getByTestId("submit-analysis").props.accessibilityState).toEqual({
    disabled: true,
    busy: true,
  });
});

test("shows an inline error and re-enables upload after a failure", async () => {
  mockCreateAnalysis.mockRejectedValue(new TypeError("Failed to fetch"));
  const view = render(<App />);
  await chooseVideo(view);

  fireEvent.press(view.getByText("Upload And Analyse"));

  await waitFor(() => expect(view.getByText("Could not start analysis")).toBeTruthy());
  expect(
    view.getByText(
      "Could not reach the analysis API at http://localhost:8000. Check that the API is running and try again.",
    ),
  ).toBeTruthy();
  expect(view.getByTestId("submit-analysis").props.accessibilityState).toEqual({
    disabled: false,
    busy: false,
  });
});

const phaseNames = [
  "Address",
  "Takeaway",
  "Lead arm parallel backswing (P3)",
  "Top (P4)",
  "Lead arm parallel downswing (P5)",
  "Shaft parallel downswing (P6)",
  "Impact approximation (P7)",
  "Shaft parallel follow-through (P8)",
  "Finish",
];

const savedResult = {
  run_id: "swing_test",
  status: "completed",
  context: {
    handedness: "right",
    camera_view: "face_on",
    club_family: "iron",
    swing_type: "full_swing",
  },
  metadata: null,
  phases: phaseNames.map((name, frame_index) => ({
    name,
    frame_index,
    timestamp_seconds: frame_index / 10,
    confidence: 0.9,
    detection_method: "automatic",
  })),
  metrics_summary: {},
  quality_flags: {},
  assessment: {
    findings: [{ reference: { name: "Swing tempo" }, status: "needs_attention" }],
  },
  llm_assessment: null,
  llm_assessment_eligibility_issue: null,
  llm_assessment_current: false,
  llm_assessment_stale: false,
  artifact_urls: {},
};

test("keeps phase frames advanced and shows save progress and success", async () => {
  mockListAnalyses.mockResolvedValue([{ run_id: savedResult.run_id }]);
  mockGetAnalysis.mockResolvedValue(savedResult);
  let resolveConfirmation: (value: typeof savedResult) => void = () => undefined;
  mockConfirmPhases.mockReturnValue(new Promise((resolve) => {
    resolveConfirmation = resolve;
  }));
  const view = render(<App />);

  fireEvent.press(view.getByText("History"));
  await waitFor(() => expect(view.getByText(savedResult.run_id)).toBeTruthy());
  fireEvent.press(view.getByText(savedResult.run_id));

  expect(view.getByText("Swing analysis")).toBeTruthy();
  fireEvent.press(view.getByText("Measurements and reference checks"));
  expect(view.getByText("Swing tempo: needs attention")).toBeTruthy();
  expect(view.queryByTestId("phase-timing-controls")).toBeNull();

  fireEvent.press(view.getByText("Advanced phase timing"));
  expect(view.getByTestId("phase-timing-controls")).toBeTruthy();
  expect(view.getAllByDisplayValue("0")).toHaveLength(1);

  fireEvent.press(view.getByText("Save phase timing"));
  await waitFor(() => expect(view.getByText("Saving timing...")).toBeTruthy());
  expect(view.getByTestId("save-phase-timing").props.accessibilityState).toEqual({
    disabled: true,
    busy: true,
  });

  await act(async () => resolveConfirmation(savedResult));
  await waitFor(() => expect(view.getByText("Phase timing saved and analysis regenerated.")).toBeTruthy());
});

test("generates and renders the complete AI coaching report", async () => {
  const coachedResult = {
    ...savedResult,
    llm_assessment_current: true,
    llm_assessment: {
      schema_version: "1.0.0",
      prompt_version: "1.0.0",
      model: "test-model",
      generated_at: "2026-06-21T00:00:00Z",
      submitted_frames: [{
        frame_id: "frame_000003",
        frame_index: 3,
        timestamp_seconds: 0.3,
        phase_relations: ["Top (P4) anchor"],
        image_file: "frame_000003.jpg",
      }],
      content: {
        overview: "A grounded overview of this swing.",
        strengths: ["Your address is repeatable."],
        priorities: [{
          title: "Turn around a steadier centre",
          rationale: "The measured movement is the clearest practice target.",
          practice_cue: "Turn around your shirt buttons.",
          explanation: "Use the top frame as your checkpoint.",
          drills: ["Trail-hip wall drill"],
          practice_plan: ["Make ten slow rehearsals"],
          supporting_frame_ids: ["frame_000003"],
          related_metric_keys: [],
          confidence: 0.84,
          support_type: "ai_generated",
        }],
        observations: [{
          title: "Top position",
          observation: "The top is visible and measurable.",
          supporting_frame_ids: ["frame_000003"],
          related_metric_keys: [],
          confidence: 0.8,
        }],
        limitations: ["This is limited to visible 2D pose evidence."],
      },
    },
    artifact_urls: {
      "llm_frame:frame_000003.jpg": "/analyses/swing_test/artifacts/llm_frame:frame_000003.jpg",
    },
  };
  mockListAnalyses.mockResolvedValue([{ run_id: savedResult.run_id }]);
  mockGetAnalysis.mockResolvedValue(savedResult);
  mockCreateLLMAssessment.mockResolvedValue(coachedResult);
  const view = render(<App />);

  fireEvent.press(view.getByText("History"));
  await waitFor(() => expect(view.getByText(savedResult.run_id)).toBeTruthy());
  fireEvent.press(view.getByText(savedResult.run_id));
  fireEvent.press(view.getByText("Generate AI coaching"));

  await waitFor(() => expect(view.getByText("A grounded overview of this swing.")).toBeTruthy());
  expect(mockCreateLLMAssessment).toHaveBeenCalledWith(savedResult.run_id);
  expect(view.getByText("1. Turn around a steadier centre")).toBeTruthy();
  expect(view.getByText("Trail-hip wall drill", { exact: false })).toBeTruthy();
  expect(view.getByText("Your address is repeatable.", { exact: false })).toBeTruthy();
  expect(view.getByText("Top position")).toBeTruthy();
  expect(view.getByText("This is limited to visible 2D pose evidence.", { exact: false })).toBeTruthy();
});

test("withholds stale coaching and directs timing issues to phase review", async () => {
  const staleResult = {
    ...savedResult,
    llm_assessment: { content: { overview: "Old coaching" } },
    llm_assessment_stale: true,
    llm_assessment_eligibility_issue: "Regenerate phase timing before requesting an AI swing assessment.",
  };
  mockListAnalyses.mockResolvedValue([{ run_id: savedResult.run_id }]);
  mockGetAnalysis.mockResolvedValue(staleResult);
  const view = render(<App />);

  fireEvent.press(view.getByText("History"));
  await waitFor(() => expect(view.getByText(savedResult.run_id)).toBeTruthy());
  fireEvent.press(view.getByText(savedResult.run_id));

  expect(view.queryByText("Old coaching")).toBeNull();
  expect(view.getByText("Review phase timing")).toBeTruthy();
  fireEvent.press(view.getByText("Review phase timing"));
  expect(view.getByTestId("phase-timing-controls")).toBeTruthy();
});
