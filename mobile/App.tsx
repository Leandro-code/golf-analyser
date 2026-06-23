import React, { useCallback, useEffect, useMemo, useState } from "react";
import { ActivityIndicator, Alert, FlatList, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import * as DocumentPicker from "expo-document-picker";
import { Video, ResizeMode } from "expo-av";

import { ApiClient, AnalysisResult, AnalysisStatus, ContextPayload, PhaseName, buildPhaseConfirmationPayload } from "./src/api";
import { CoachingReport } from "./src/CoachingReport";

const API_BASE_URL = process.env.EXPO_PUBLIC_API_BASE_URL ?? "http://localhost:8000";
const API_TOKEN = process.env.EXPO_PUBLIC_API_TOKEN;

function requestErrorMessage(error: unknown, fallback: string) {
  if (error instanceof TypeError && /failed to fetch|network request failed/i.test(error.message)) {
    return `Could not reach the analysis API at ${API_BASE_URL}. Check that the API is running and try again.`;
  }
  return error instanceof Error ? error.message : fallback;
}

function readableLabel(value: string) {
  return value.replaceAll("_", " ");
}

type Screen = "new" | "processing" | "result" | "history";

const PHASES: PhaseName[] = [
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

export default function App() {
  const client = useMemo(() => new ApiClient(API_BASE_URL, API_TOKEN), []);
  const [screen, setScreen] = useState<Screen>("new");
  const [video, setVideo] = useState<DocumentPicker.DocumentPickerAsset | null>(null);
  const [context, setContext] = useState<ContextPayload>({
    handedness: "right",
    camera_view: "face_on",
    club_family: "iron",
    swing_type: "full_swing",
  });
  const [status, setStatus] = useState<AnalysisStatus | null>(null);
  const [result, setResult] = useState<AnalysisResult | null>(null);
  const [history, setHistory] = useState<AnalysisResult[]>([]);
  const [phaseFrames, setPhaseFrames] = useState<Record<string, string>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [showPhaseReview, setShowPhaseReview] = useState(false);
  const [isConfirmingPhases, setIsConfirmingPhases] = useState(false);
  const [phaseFeedback, setPhaseFeedback] = useState<{ kind: "success" | "error"; message: string } | null>(null);
  const [isGeneratingCoaching, setIsGeneratingCoaching] = useState(false);
  const [coachingError, setCoachingError] = useState<string | null>(null);
  const [showDetails, setShowDetails] = useState(false);
  const [replayReady, setReplayReady] = useState(false);
  const [replayError, setReplayError] = useState<string | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [isLoadingHistory, setIsLoadingHistory] = useState(false);

  const displayResult = useCallback((loaded: AnalysisResult) => {
    setResult(loaded);
    setPhaseFrames(Object.fromEntries(loaded.phases.map((phase) => [phase.name, String(phase.frame_index)])));
    setShowPhaseReview(false);
    setPhaseFeedback(null);
    setCoachingError(null);
    setShowDetails(false);
    setReplayReady(false);
    setReplayError(null);
    setScreen("result");
  }, []);

  useEffect(() => {
    if (screen !== "processing" || !status) return;
    const timer = setInterval(async () => {
      try {
        const next = await client.getStatus(status.run_id);
        setStatus(next);
        if (next.status === "completed") {
          const loaded = await client.getAnalysis(next.run_id);
          displayResult(loaded);
        }
        if (next.status === "failed") {
          const message = next.error ?? next.message;
          setSubmitError(message);
          Alert.alert("Analysis failed", message);
          setScreen("new");
        }
      } catch (error) {
        const message = requestErrorMessage(error, "Unable to check analysis progress.");
        setSubmitError(message);
        Alert.alert("Connection error", message);
        setScreen("new");
      }
    }, 1200);
    return () => clearInterval(timer);
  }, [client, displayResult, screen, status]);

  const submit = async () => {
    if (!video) {
      Alert.alert("Choose a video first");
      return;
    }
    setIsSubmitting(true);
    setSubmitError(null);
    try {
      const created = await client.createAnalysis(video, context);
      setStatus(created.status);
      setScreen("processing");
    } catch (error) {
      const message = requestErrorMessage(error, "Unable to upload the video.");
      setSubmitError(message);
      Alert.alert("Upload failed", message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const loadHistory = async () => {
    setIsLoadingHistory(true);
    setHistoryError(null);
    try {
      const summaries = await client.listAnalyses();
      const details = await Promise.all(summaries.map((item) => client.getAnalysis(item.run_id)));
      setHistory(details);
      setScreen("history");
    } catch (error) {
      setHistoryError(requestErrorMessage(error, "Unable to load saved analyses."));
      setScreen("history");
    } finally {
      setIsLoadingHistory(false);
    }
  };

  const generateCoaching = async () => {
    if (!result) return;
    setIsGeneratingCoaching(true);
    setCoachingError(null);
    try {
      const updated = await client.createLLMAssessment(result.run_id);
      setResult(updated);
    } catch (error) {
      // The model request may have completed even if the response was interrupted.
      // Refresh once before offering a retry so we do not create a duplicate request.
      try {
        const refreshed = await client.getAnalysis(result.run_id);
        setResult(refreshed);
        if (!refreshed.llm_assessment_current) {
          setCoachingError(requestErrorMessage(error, "Unable to generate AI coaching."));
        }
      } catch {
        setCoachingError(requestErrorMessage(error, "Unable to generate AI coaching."));
      }
    } finally {
      setIsGeneratingCoaching(false);
    }
  };

  const confirmPhases = async () => {
    if (!result) return;
    setIsConfirmingPhases(true);
    setPhaseFeedback(null);
    try {
      const payload = buildPhaseConfirmationPayload(phaseFrames, PHASES);
      const updated = await client.confirmPhases(result.run_id, payload);
      setResult(updated);
      setPhaseFrames(Object.fromEntries(updated.phases.map((phase) => [phase.name, String(phase.frame_index)])));
      setPhaseFeedback({ kind: "success", message: "Phase timing saved and analysis regenerated." });
    } catch (error) {
      setPhaseFeedback({
        kind: "error",
        message: requestErrorMessage(error, "Unable to save phase timing."),
      });
    } finally {
      setIsConfirmingPhases(false);
    }
  };

  if (screen === "processing" && status) {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" />
        <Text style={styles.title}>{Math.round(status.progress * 100)}%</Text>
        <Text>{status.message}</Text>
      </View>
    );
  }

  if (screen === "history") {
    return (
      <View style={styles.screen}>
        <Toolbar onNew={() => setScreen("new")} onHistory={loadHistory} />
        {isLoadingHistory ? <View style={styles.inlineLoading}><ActivityIndicator /><Text>Loading saved analyses...</Text></View> : null}
        {historyError ? <View style={styles.errorBanner} accessibilityRole="alert"><Text style={styles.errorText}>{historyError}</Text></View> : null}
        <FlatList
          data={history}
          keyExtractor={(item) => item.run_id}
          renderItem={({ item }) => (
            <Pressable style={styles.row} onPress={() => displayResult(item)}>
              <Text style={styles.rowTitle}>{item.run_id}</Text>
              <Text>{item.context ? `${item.context.camera_view} / ${item.context.club_family}` : "Legacy run"}</Text>
              {item.llm_assessment_stale ? <Text style={styles.warning}>AI assessment is stale</Text> : null}
            </Pressable>
          )}
        />
      </View>
    );
  }

  if (screen === "result" && result) {
    const replaySource = client.artifactSource(result.artifact_urls.annotated_video);
    return (
      <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
        <Toolbar onNew={() => setScreen("new")} onHistory={loadHistory} />
        <Text style={styles.title}>Swing analysis</Text>
        {result.context ? (
          <Text style={styles.meta}>
            {readableLabel(result.context.camera_view)} | {readableLabel(result.context.club_family)} | {result.context.handedness} handed
          </Text>
        ) : null}
        {result.quality_flags.phase_quality_issues?.length ? <Text style={styles.warning}>{result.quality_flags.phase_quality_issues.join(" ")}</Text> : null}
        <View style={styles.replayContainer}>
          {!replayReady && !replayError ? <View style={styles.replayLoading}><ActivityIndicator color="white" /><Text style={styles.replayLoadingText}>Loading annotated replay...</Text></View> : null}
          <Video
            source={replaySource}
            style={styles.video}
            resizeMode={ResizeMode.CONTAIN}
            useNativeControls
            onLoadStart={() => { setReplayReady(false); setReplayError(null); }}
            onLoad={() => setReplayReady(true)}
            onError={(message) => setReplayError(message || "The annotated replay could not be loaded.")}
          />
        </View>
        {replayError ? <View style={styles.errorBanner} accessibilityRole="alert"><Text style={styles.errorText}>{replayError}</Text></View> : null}

        <CoachingReport
          result={result}
          isGenerating={isGeneratingCoaching}
          generationError={coachingError}
          artifactSource={(path) => client.artifactSource(path)}
          onGenerate={generateCoaching}
          onReviewTiming={() => setShowPhaseReview(true)}
        />

        <View style={styles.advancedSection}>
          <Pressable
            style={styles.disclosure}
            onPress={() => setShowDetails((current) => !current)}
            accessibilityRole="button"
            accessibilityState={{ expanded: showDetails }}
          >
            <View style={styles.disclosureCopy}>
              <Text style={styles.sectionTitle}>Measurements and reference checks</Text>
              <Text style={styles.meta}>Deterministic pose details</Text>
            </View>
            <Text style={styles.disclosureIcon}>{showDetails ? "-" : "+"}</Text>
          </Pressable>
          {showDetails ? (
            <View testID="measurement-details" style={styles.detailControls}>
              <Section title="Reference checks">
                {result.assessment?.findings?.map((finding, index) => (
                  <View key={`${finding.reference.name}-${index}`} style={styles.referenceCard}>
                    <Text style={styles.referenceTitle}>{finding.reference.name}: {readableLabel(finding.status)}</Text>
                    {finding.observed_value !== undefined ? <Text style={styles.body}>Observed: {String(finding.observed_value)}</Text> : null}
                    {finding.expected ? <Text style={styles.body}>Reference: {finding.expected}</Text> : null}
                    {finding.reference.rationale ? <Text style={styles.body}>{finding.reference.rationale}</Text> : null}
                    {finding.reference.correction_cue && finding.status === "needs_attention" ? <Text style={styles.referenceCue}>Cue: {finding.reference.correction_cue}</Text> : null}
                    {finding.note ? <Text style={styles.meta}>{finding.note}</Text> : null}
                  </View>
                )) ?? <Text style={styles.body}>No contextual reference checks for this saved run.</Text>}
              </Section>
              <Section title="Measured pose data">
                {Object.entries(result.metrics_summary).map(([key, metric]) => (
                  <Text key={key} style={styles.body}>{metric.name}: {String(metric.value)} {metric.unit ?? ""}</Text>
                ))}
              </Section>
            </View>
          ) : null}
        </View>
        <View style={styles.advancedSection}>
          <Pressable
            style={styles.disclosure}
            onPress={() => setShowPhaseReview((current) => !current)}
            accessibilityRole="button"
            accessibilityState={{ expanded: showPhaseReview }}
          >
            <View style={styles.disclosureCopy}>
              <Text style={styles.sectionTitle}>Advanced phase timing</Text>
              <Text style={styles.meta}>Manual frame controls</Text>
            </View>
            <Text style={styles.disclosureIcon}>{showPhaseReview ? "-" : "+"}</Text>
          </Pressable>
          {showPhaseReview ? (
            <View testID="phase-timing-controls" style={styles.phaseControls}>
              {PHASES.map((phase) => (
                <View key={phase} style={styles.phaseRow}>
                  <Text style={styles.phaseLabel}>{phase}</Text>
                  <View style={styles.frameField}>
                    <Text style={styles.inputLabel}>Frame</Text>
                    <TextInput
                      value={phaseFrames[phase] ?? ""}
                      onChangeText={(value) => setPhaseFrames((current) => ({ ...current, [phase]: value }))}
                      keyboardType="number-pad"
                      style={styles.input}
                    />
                  </View>
                </View>
              ))}
              {phaseFeedback ? (
                <View
                  style={phaseFeedback.kind === "success" ? styles.successBanner : styles.errorBanner}
                  accessibilityRole="alert"
                >
                  <Text style={phaseFeedback.kind === "success" ? styles.successText : styles.errorText}>
                    {phaseFeedback.message}
                  </Text>
                </View>
              ) : null}
              <Pressable
                testID="save-phase-timing"
                style={[styles.primaryButton, isConfirmingPhases && styles.buttonDisabled]}
                onPress={confirmPhases}
                disabled={isConfirmingPhases}
                accessibilityState={{ disabled: isConfirmingPhases, busy: isConfirmingPhases }}
              >
                <View style={styles.buttonContent}>
                  {isConfirmingPhases ? <ActivityIndicator color="white" size="small" /> : null}
                  <Text style={styles.buttonText}>{isConfirmingPhases ? "Saving timing..." : "Save phase timing"}</Text>
                </View>
              </Pressable>
            </View>
          ) : null}
        </View>
      </ScrollView>
    );
  }

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
      <Toolbar onNew={() => setScreen("new")} onHistory={loadHistory} />
      <Text style={styles.title}>New Swing</Text>
      <Pressable style={styles.primaryButton} onPress={async () => {
        const picked = await DocumentPicker.getDocumentAsync({ type: "video/*", copyToCacheDirectory: true });
        if (!picked.canceled) {
          setVideo(picked.assets[0]);
          setSubmitError(null);
        }
      }}>
        <Text style={styles.buttonText}>{video ? video.name : "Choose Video"}</Text>
      </Pressable>
      <OptionGroup label="Handedness" value={context.handedness} options={["right", "left"]} onChange={(handedness) => setContext({ ...context, handedness: handedness as ContextPayload["handedness"] })} />
      <OptionGroup label="Camera" value={context.camera_view} options={["face_on", "down_the_line"]} onChange={(camera_view) => setContext({ ...context, camera_view: camera_view as ContextPayload["camera_view"] })} />
      <OptionGroup label="Club" value={context.club_family} options={["driver", "wood_or_hybrid", "iron", "wedge"]} onChange={(club_family) => setContext({ ...context, club_family: club_family as ContextPayload["club_family"] })} />
      {submitError ? (
        <View style={styles.errorBanner} accessibilityRole="alert">
          <Text style={styles.errorTitle}>Could not start analysis</Text>
          <Text style={styles.errorText}>{submitError}</Text>
        </View>
      ) : null}
      <Pressable
        testID="submit-analysis"
        style={[styles.primaryButton, isSubmitting && styles.buttonDisabled]}
        onPress={submit}
        disabled={isSubmitting}
        accessibilityState={{ disabled: isSubmitting, busy: isSubmitting }}
      >
        <View style={styles.buttonContent}>
          {isSubmitting ? <ActivityIndicator color="white" size="small" /> : null}
          <Text style={styles.buttonText}>{isSubmitting ? "Uploading video..." : submitError ? "Retry upload" : "Upload And Analyse"}</Text>
        </View>
      </Pressable>
    </ScrollView>
  );
}

function Toolbar({ onNew, onHistory }: { onNew: () => void; onHistory: () => void }) {
  return (
    <View style={styles.toolbar}>
      <Pressable onPress={onNew}><Text style={styles.link}>New Swing</Text></Pressable>
      <Pressable onPress={onHistory}><Text style={styles.link}>History</Text></Pressable>
    </View>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return <View style={styles.section}><Text style={styles.sectionTitle}>{title}</Text>{children}</View>;
}

function OptionGroup({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (value: string) => void }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{label}</Text>
      <View style={styles.options}>{options.map((option) => (
        <Pressable key={option} style={[styles.option, option === value && styles.optionSelected]} onPress={() => onChange(option)}>
          <Text>{option}</Text>
        </Pressable>
      ))}</View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: "#f7f7f2" },
  content: { padding: 18, gap: 16 },
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 10, backgroundColor: "#f7f7f2" },
  toolbar: { flexDirection: "row", justifyContent: "space-between", paddingVertical: 8 },
  link: { color: "#245c4f", fontWeight: "700" },
  title: { fontSize: 26, fontWeight: "800", color: "#1f2723" },
  meta: { color: "#617068", lineHeight: 20 },
  section: { gap: 8 },
  sectionTitle: { fontSize: 18, fontWeight: "700", color: "#1f2723" },
  primaryButton: { backgroundColor: "#245c4f", borderRadius: 6, padding: 14, alignItems: "center" },
  buttonDisabled: { opacity: 0.72 },
  buttonContent: { minHeight: 20, flexDirection: "row", alignItems: "center", justifyContent: "center", gap: 8 },
  buttonText: { color: "white", fontWeight: "800" },
  errorBanner: { borderLeftWidth: 4, borderLeftColor: "#a23c20", backgroundColor: "#f8e8e2", padding: 12, gap: 3 },
  errorTitle: { color: "#782b18", fontWeight: "800" },
  errorText: { color: "#5d2c20", lineHeight: 20 },
  successBanner: { borderLeftWidth: 4, borderLeftColor: "#267052", backgroundColor: "#e3f0e9", padding: 12 },
  successText: { color: "#1d5941", lineHeight: 20, fontWeight: "700" },
  options: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  option: { borderWidth: 1, borderColor: "#9ba79f", borderRadius: 6, paddingVertical: 10, paddingHorizontal: 12 },
  optionSelected: { backgroundColor: "#dfeae4", borderColor: "#245c4f" },
  inlineLoading: { flexDirection: "row", alignItems: "center", gap: 8, padding: 14 },
  replayContainer: { position: "relative", width: "100%", aspectRatio: 16 / 9, backgroundColor: "#111" },
  replayLoading: { ...StyleSheet.absoluteFillObject, alignItems: "center", justifyContent: "center", gap: 8, zIndex: 1 },
  replayLoadingText: { color: "white" },
  video: { width: "100%", aspectRatio: 16 / 9, backgroundColor: "#111" },
  body: { color: "#303833", lineHeight: 21 },
  warning: { color: "#8a3b12", fontWeight: "700" },
  advancedSection: { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: "#b9c1bb", paddingTop: 4 },
  disclosure: { minHeight: 58, flexDirection: "row", alignItems: "center", justifyContent: "space-between", paddingVertical: 10 },
  disclosureCopy: { flex: 1, gap: 2 },
  disclosureIcon: { width: 32, textAlign: "center", fontSize: 26, color: "#245c4f" },
  detailControls: { gap: 18, paddingTop: 8 },
  referenceCard: { backgroundColor: "white", borderWidth: 1, borderColor: "#d2d7d3", borderRadius: 7, padding: 12, gap: 5 },
  referenceTitle: { color: "#1f2723", fontWeight: "800", lineHeight: 20 },
  referenceCue: { color: "#1d5941", lineHeight: 20, fontWeight: "700" },
  phaseControls: { gap: 12, paddingTop: 8 },
  phaseRow: { minHeight: 54, flexDirection: "row", alignItems: "center", gap: 12 },
  phaseLabel: { flex: 1, color: "#303833" },
  frameField: { gap: 3, alignItems: "flex-start" },
  inputLabel: { fontSize: 12, color: "#617068" },
  input: { width: 72, borderWidth: 1, borderColor: "#9ba79f", borderRadius: 6, padding: 8, backgroundColor: "white" },
  row: { padding: 14, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: "#b9c1bb", gap: 4 },
  rowTitle: { fontWeight: "800", color: "#1f2723" }
});
