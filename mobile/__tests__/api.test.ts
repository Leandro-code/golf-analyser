import { ApiClient, buildPhaseConfirmationPayload, ContextPayload, PhaseName } from "../src/api";

const phases: PhaseName[] = [
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

test("builds phase confirmation payload in API order", () => {
  const payload = buildPhaseConfirmationPayload(
    Object.fromEntries(phases.map((phase, index) => [phase, String(index * 5)])),
    phases,
  );

  expect(payload).toEqual({ frame_indices: [0, 5, 10, 15, 20, 25, 30, 35, 40] });
});

test("rejects incomplete phase confirmation payloads", () => {
  expect(() => buildPhaseConfirmationPayload({}, phases)).toThrow("Missing frame");
});

test("uploads the browser File rather than stringifying the picker asset", async () => {
  const file = new File(["video"], "swing.mp4", { type: "video/mp4" });
  const fetchMock = jest.spyOn(globalThis, "fetch").mockResolvedValue({
    ok: true,
    json: async () => ({ run_id: "swing_test" }),
  } as Response);
  const context: ContextPayload = {
    handedness: "right",
    camera_view: "face_on",
    club_family: "iron",
    swing_type: "full_swing",
  };

  await new ApiClient("http://localhost:8000").createAnalysis(
    { uri: "blob:test", name: file.name, mimeType: file.type, file },
    context,
  );

  const request = fetchMock.mock.calls[0][1];
  const body = request?.body as FormData;
  const uploaded = body.get("video") as File;
  expect(uploaded).toBeInstanceOf(Blob);
  expect(uploaded.name).toBe("swing.mp4");
  expect(uploaded.type).toBe("video/mp4");
  expect(uploaded.size).toBe(5);
  expect(body.get("camera_view")).toBe("face_on");
  fetchMock.mockRestore();
});

test("requests AI coaching and returns authenticated artifact sources", async () => {
  const fetchMock = jest.spyOn(globalThis, "fetch").mockResolvedValue({
    ok: true,
    json: async () => ({ run_id: "swing_test", llm_assessment_current: true }),
  } as Response);
  const client = new ApiClient("https://analysis.example", "secret");

  await client.createLLMAssessment("swing test");

  expect(fetchMock).toHaveBeenCalledWith(
    "https://analysis.example/analyses/swing%20test/llm-assessment",
    expect.objectContaining({ method: "POST" }),
  );
  const headers = fetchMock.mock.calls[0][1]?.headers as Headers;
  expect(headers.get("Authorization")).toBe("Bearer secret");
  expect(client.artifactSource("/analyses/swing_test/artifacts/annotated_video")).toEqual({
    uri: "https://analysis.example/analyses/swing_test/artifacts/annotated_video",
    headers: { Authorization: "Bearer secret" },
  });
  fetchMock.mockRestore();
});
