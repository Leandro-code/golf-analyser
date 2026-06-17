import { buildPhaseConfirmationPayload, PhaseName } from "../src/api";

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
