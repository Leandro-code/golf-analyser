export type ContextPayload = {
  handedness: "right" | "left";
  camera_view: "face_on" | "down_the_line";
  club_family: "driver" | "wood_or_hybrid" | "iron" | "wedge";
  swing_type: "full_swing";
};

export type AnalysisStatus = {
  run_id: string;
  status: "queued" | "processing" | "completed" | "failed";
  progress: number;
  message: string;
  created_at: string;
  updated_at: string;
  error?: string | null;
};

export type PhaseName =
  | "Address"
  | "Takeaway"
  | "Lead arm parallel backswing (P3)"
  | "Top (P4)"
  | "Lead arm parallel downswing (P5)"
  | "Shaft parallel downswing (P6)"
  | "Impact approximation (P7)"
  | "Shaft parallel follow-through (P8)"
  | "Finish";

export type SwingPhase = {
  name: PhaseName;
  frame_index: number;
  timestamp_seconds: number;
  confidence: number;
  detection_method: string;
};

export type MetricSummary = {
  name: string;
  value: unknown;
  unit?: string | null;
};

export type AssessmentFinding = {
  reference: {
    id?: string;
    name: string;
    rationale?: string;
    correction_cue?: string;
  };
  status: string;
  observed_value?: unknown;
  expected?: string | null;
  phase_name?: string | null;
  confidence?: number | null;
  evidence_keyframe?: string | null;
  note?: string | null;
};

export type SubmittedEvidenceFrame = {
  frame_id: string;
  frame_index: number;
  timestamp_seconds: number;
  phase_relations: string[];
  image_file: string;
};

export type LLMObservation = {
  title: string;
  observation: string;
  supporting_frame_ids: string[];
  related_metric_keys: string[];
  confidence: number;
};

export type LLMPriority = {
  title: string;
  rationale: string;
  practice_cue: string;
  explanation?: string | null;
  drills: string[];
  practice_plan: string[];
  supporting_frame_ids: string[];
  related_metric_keys: string[];
  confidence: number;
  support_type: "ai_generated";
};

export type LLMAssessment = {
  schema_version: string;
  prompt_version: string;
  model: string;
  generated_at: string;
  submitted_frames: SubmittedEvidenceFrame[];
  content: {
    overview: string;
    strengths: string[];
    observations: LLMObservation[];
    priorities: LLMPriority[];
    limitations: string[];
  };
};

export type AnalysisResult = {
  run_id: string;
  status: "completed";
  context: ContextPayload | null;
  metadata: Record<string, unknown> | null;
  phases: SwingPhase[];
  metrics_summary: Record<string, MetricSummary>;
  quality_flags: Record<string, any>;
  assessment: {
    findings: AssessmentFinding[];
    quality_limitations?: string[];
  } | null;
  llm_assessment: LLMAssessment | null;
  llm_assessment_eligibility_issue?: string | null;
  llm_assessment_current: boolean;
  llm_assessment_stale: boolean;
  artifact_urls: Record<string, string>;
};

export type AnalysisSummary = Pick<
  AnalysisResult,
  "run_id" | "context" | "metadata" | "quality_flags" | "artifact_urls" | "llm_assessment_current" | "llm_assessment_stale"
>;

export class ApiClient {
  constructor(private readonly baseUrl: string, private readonly token?: string) {}

  async createAnalysis(video: { uri: string; name?: string; mimeType?: string; file?: Blob }, context: ContextPayload) {
    const body = new FormData();
    if (video.file) {
      body.append("video", video.file, video.name ?? "swing.mp4");
    } else {
      body.append("video", {
        uri: video.uri,
        name: video.name ?? "swing.mp4",
        type: video.mimeType ?? "video/mp4",
      } as any);
    }
    Object.entries(context).forEach(([key, value]) => body.append(key, value));
    return this.request<{ run_id: string; status_url: string; result_url: string; status: AnalysisStatus }>("/analyses", {
      method: "POST",
      body,
    });
  }

  getStatus(runId: string) {
    return this.request<AnalysisStatus>(`/analyses/${encodeURIComponent(runId)}/status`);
  }

  listAnalyses() {
    return this.request<AnalysisSummary[]>("/analyses");
  }

  getAnalysis(runId: string) {
    return this.request<AnalysisResult>(`/analyses/${encodeURIComponent(runId)}`);
  }

  confirmPhases(runId: string, payload: { frame_indices: number[] }) {
    return this.request<AnalysisResult>(`/analyses/${encodeURIComponent(runId)}/phases/confirm`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
  }

  createLLMAssessment(runId: string) {
    return this.request<AnalysisResult>(`/analyses/${encodeURIComponent(runId)}/llm-assessment`, {
      method: "POST",
    });
  }

  artifactUrl(path?: string) {
    if (!path) return "";
    return `${this.baseUrl}${path}`;
  }

  artifactSource(path?: string) {
    const uri = this.artifactUrl(path);
    return this.token
      ? { uri, headers: { Authorization: `Bearer ${this.token}` } }
      : { uri };
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers);
    if (this.token) headers.set("Authorization", `Bearer ${this.token}`);
    const response = await fetch(`${this.baseUrl}${path}`, { ...init, headers });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(readApiError(text) || `Request failed with ${response.status}`);
    }
    return response.json() as Promise<T>;
  }
}

function readApiError(text: string) {
  if (!text) return "";
  try {
    const payload = JSON.parse(text) as { detail?: string | Array<{ msg?: string }> };
    if (typeof payload.detail === "string") return payload.detail;
    if (Array.isArray(payload.detail)) {
      const messages = payload.detail.flatMap((item) => item.msg ? [item.msg] : []);
      if (messages.length) return messages.join(" ");
    }
  } catch {
    return text;
  }
  return text;
}

export function buildPhaseConfirmationPayload(frameMap: Record<string, string>, phaseOrder: PhaseName[]) {
  const frame_indices = phaseOrder.map((phase) => {
    const value = Number.parseInt(frameMap[phase] ?? "", 10);
    if (!Number.isFinite(value)) {
      throw new Error(`Missing frame for ${phase}`);
    }
    return value;
  });
  return { frame_indices };
}
