import React, { useEffect, useMemo, useState } from "react";
import { ActivityIndicator, Alert, FlatList, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import * as DocumentPicker from "expo-document-picker";
import { Video, ResizeMode } from "expo-av";

import { ApiClient, AnalysisResult, AnalysisStatus, ContextPayload, PhaseName, buildPhaseConfirmationPayload } from "./src/api";

const API_BASE_URL = process.env.EXPO_PUBLIC_API_BASE_URL ?? "http://localhost:8000";
const API_TOKEN = process.env.EXPO_PUBLIC_API_TOKEN;

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

  useEffect(() => {
    if (screen !== "processing" || !status) return;
    const timer = setInterval(async () => {
      const next = await client.getStatus(status.run_id);
      setStatus(next);
      if (next.status === "completed") {
        const loaded = await client.getAnalysis(next.run_id);
        setResult(loaded);
        setPhaseFrames(Object.fromEntries(loaded.phases.map((phase) => [phase.name, String(phase.frame_index)])));
        setScreen("result");
      }
      if (next.status === "failed") {
        Alert.alert("Analysis failed", next.error ?? next.message);
        setScreen("new");
      }
    }, 1200);
    return () => clearInterval(timer);
  }, [client, screen, status]);

  const submit = async () => {
    if (!video) {
      Alert.alert("Choose a video first");
      return;
    }
    const created = await client.createAnalysis(video, context);
    setStatus(created.status);
    setScreen("processing");
  };

  const loadHistory = async () => {
    const summaries = await client.listAnalyses();
    const details = await Promise.all(summaries.map((item) => client.getAnalysis(item.run_id)));
    setHistory(details);
    setScreen("history");
  };

  const confirmPhases = async () => {
    if (!result) return;
    const payload = buildPhaseConfirmationPayload(phaseFrames, PHASES);
    const updated = await client.confirmPhases(result.run_id, payload);
    setResult(updated);
    setPhaseFrames(Object.fromEntries(updated.phases.map((phase) => [phase.name, String(phase.frame_index)])));
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
        <FlatList
          data={history}
          keyExtractor={(item) => item.run_id}
          renderItem={({ item }) => (
            <Pressable style={styles.row} onPress={() => { setResult(item); setScreen("result"); }}>
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
    const replayUrl = client.artifactUrl(result.artifact_urls.annotated_video);
    return (
      <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
        <Toolbar onNew={() => setScreen("new")} onHistory={loadHistory} />
        <Text style={styles.title}>{result.run_id}</Text>
        {result.quality_flags.phase_quality_issues?.length ? <Text style={styles.warning}>{result.quality_flags.phase_quality_issues.join(" ")}</Text> : null}
        {result.llm_assessment_stale ? <Text style={styles.warning}>Saved AI assessment is stale after timing changes.</Text> : null}
        <Video source={{ uri: replayUrl }} style={styles.video} resizeMode={ResizeMode.CONTAIN} useNativeControls />
        <Section title="Assessment">
          {result.assessment?.findings?.map((finding, index) => (
            <Text key={index} style={styles.body}>{finding.reference.name}: {finding.status}</Text>
          )) ?? <Text style={styles.body}>No contextual assessment for this saved run.</Text>}
        </Section>
        <Section title="Metrics">
          {Object.entries(result.metrics_summary).map(([key, metric]) => (
            <Text key={key} style={styles.body}>{metric.name}: {String(metric.value)} {metric.unit ?? ""}</Text>
          ))}
        </Section>
        <Section title="Phase Review">
          {PHASES.map((phase) => (
            <View key={phase} style={styles.phaseRow}>
              <Text style={styles.phaseLabel}>{phase}</Text>
              <TextInput
                value={phaseFrames[phase] ?? ""}
                onChangeText={(value) => setPhaseFrames((current) => ({ ...current, [phase]: value }))}
                keyboardType="number-pad"
                style={styles.input}
              />
            </View>
          ))}
          <Pressable style={styles.primaryButton} onPress={confirmPhases}><Text style={styles.buttonText}>Confirm Markers</Text></Pressable>
        </Section>
      </ScrollView>
    );
  }

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
      <Toolbar onNew={() => setScreen("new")} onHistory={loadHistory} />
      <Text style={styles.title}>New Swing</Text>
      <Pressable style={styles.primaryButton} onPress={async () => {
        const picked = await DocumentPicker.getDocumentAsync({ type: "video/*", copyToCacheDirectory: true });
        if (!picked.canceled) setVideo(picked.assets[0]);
      }}>
        <Text style={styles.buttonText}>{video ? video.name : "Choose Video"}</Text>
      </Pressable>
      <OptionGroup label="Handedness" value={context.handedness} options={["right", "left"]} onChange={(handedness) => setContext({ ...context, handedness: handedness as ContextPayload["handedness"] })} />
      <OptionGroup label="Camera" value={context.camera_view} options={["face_on", "down_the_line"]} onChange={(camera_view) => setContext({ ...context, camera_view: camera_view as ContextPayload["camera_view"] })} />
      <OptionGroup label="Club" value={context.club_family} options={["driver", "wood_or_hybrid", "iron", "wedge"]} onChange={(club_family) => setContext({ ...context, club_family: club_family as ContextPayload["club_family"] })} />
      <Pressable style={styles.primaryButton} onPress={submit}><Text style={styles.buttonText}>Upload And Analyse</Text></Pressable>
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
  section: { gap: 8 },
  sectionTitle: { fontSize: 18, fontWeight: "700", color: "#1f2723" },
  primaryButton: { backgroundColor: "#245c4f", borderRadius: 6, padding: 14, alignItems: "center" },
  buttonText: { color: "white", fontWeight: "800" },
  options: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  option: { borderWidth: 1, borderColor: "#9ba79f", borderRadius: 6, paddingVertical: 10, paddingHorizontal: 12 },
  optionSelected: { backgroundColor: "#dfeae4", borderColor: "#245c4f" },
  video: { width: "100%", aspectRatio: 16 / 9, backgroundColor: "#111" },
  body: { color: "#303833", lineHeight: 21 },
  warning: { color: "#8a3b12", fontWeight: "700" },
  phaseRow: { flexDirection: "row", alignItems: "center", gap: 8 },
  phaseLabel: { flex: 1, color: "#303833" },
  input: { width: 72, borderWidth: 1, borderColor: "#9ba79f", borderRadius: 6, padding: 8, backgroundColor: "white" },
  row: { padding: 14, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: "#b9c1bb", gap: 4 },
  rowTitle: { fontWeight: "800", color: "#1f2723" }
});
