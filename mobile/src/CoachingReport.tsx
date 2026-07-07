import React from "react";
import { ActivityIndicator, Image, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";

import { AnalysisResult, SubmittedEvidenceFrame } from "./api";

type Props = {
  result: AnalysisResult;
  isGenerating: boolean;
  generationError: string | null;
  artifactSource: (path?: string) => { uri: string; headers?: { Authorization: string } };
  onGenerate: () => void;
  onReviewTiming: () => void;
};

export function CoachingReport({
  result,
  isGenerating,
  generationError,
  artifactSource,
  onGenerate,
  onReviewTiming,
}: Props) {
  const assessment = result.llm_assessment_current ? result.llm_assessment : null;
  const eligibilityIssue = result.llm_assessment_eligibility_issue;
  const timingReviewMayHelp = Boolean(eligibilityIssue && /phase|timing|marker/i.test(eligibilityIssue));

  if (!assessment) {
    return (
      <View style={styles.section}>
        <Text style={styles.title}>AI Swing Coaching</Text>
        <Text style={styles.body}>
          Get a model-generated report grounded in selected swing frames, local pose measurements, and quality checks.
        </Text>
        {result.llm_assessment_stale ? (
          <Banner kind="warning" text="Your saved coaching no longer matches the current phase timing. Regenerate it to use the latest evidence." />
        ) : null}
        {eligibilityIssue ? <Banner kind="warning" text={eligibilityIssue} /> : null}
        {generationError ? <Banner kind="error" text={generationError} /> : null}
        {timingReviewMayHelp ? (
          <ActionButton label="Review phase timing" onPress={onReviewTiming} />
        ) : !eligibilityIssue ? (
          <ActionButton
            label={isGenerating ? "Generating AI coaching..." : result.llm_assessment_stale ? "Regenerate AI coaching" : "Generate AI coaching"}
            onPress={onGenerate}
            disabled={isGenerating}
            busy={isGenerating}
            testID="generate-ai-coaching"
          />
        ) : null}
        <Text style={styles.caption}>
          Coaching is opt-in and cannot assess club path, clubface, contact quality, or ball flight.
        </Text>
      </View>
    );
  }

  const frames = new Map(assessment.submitted_frames.map((frame) => [frame.frame_id, frame]));
  const evidence = (frameIds: string[]) => (
    <EvidenceStrip
      frameIds={frameIds}
      frames={frames}
      result={result}
      artifactSource={artifactSource}
    />
  );

  return (
    <View style={styles.section}>
      <Text style={styles.title}>AI Swing Coaching</Text>
      <Text style={styles.overview}>{assessment.content.overview}</Text>

      {(assessment.content.priorities ?? []).length ? (
        <View style={styles.group}>
          <Text style={styles.groupTitle}>Practice priorities</Text>
          {assessment.content.priorities.map((priority, index) => (
            <View key={`${priority.title}-${index}`} style={styles.card}>
              <View style={styles.headingRow}>
                <Text style={styles.cardTitle}>{index + 1}. {priority.title}</Text>
                <Text style={styles.confidence}>{confidenceLabel(priority.confidence)}</Text>
              </View>
              <Text style={styles.body}>{priority.rationale}</Text>
              <View style={styles.cue}>
                <Text style={styles.cueLabel}>Practice cue</Text>
                <Text style={styles.cueText}>{priority.practice_cue}</Text>
              </View>
              {priority.explanation ? <Text style={styles.body}>{priority.explanation}</Text> : null}
              <BulletList title="Drills" items={priority.drills ?? []} />
              <BulletList title="Practice plan" items={priority.practice_plan ?? []} ordered />
              <RelatedMetrics keys={priority.related_metric_keys ?? []} result={result} />
              {evidence(priority.supporting_frame_ids ?? [])}
            </View>
          ))}
        </View>
      ) : null}

      <TextList title="Strengths" items={assessment.content.strengths ?? []} tone="positive" />

      {(assessment.content.observations ?? []).length ? (
        <View style={styles.group}>
          <Text style={styles.groupTitle}>Visible observations</Text>
          {assessment.content.observations.map((observation, index) => (
            <View key={`${observation.title}-${index}`} style={styles.card}>
              <View style={styles.headingRow}>
                <Text style={styles.cardTitle}>{observation.title}</Text>
                <Text style={styles.confidence}>{confidenceLabel(observation.confidence)}</Text>
              </View>
              <Text style={styles.body}>{observation.observation}</Text>
              <RelatedMetrics keys={observation.related_metric_keys ?? []} result={result} />
              {evidence(observation.supporting_frame_ids ?? [])}
            </View>
          ))}
        </View>
      ) : null}

      <TextList title="Limitations" items={assessment.content.limitations ?? []} />
      <Text style={styles.caption}>Generated with {assessment.model} at {formatDate(assessment.generated_at)}.</Text>
      {generationError ? <Banner kind="error" text={generationError} /> : null}
      <ActionButton
        label={isGenerating ? "Regenerating AI coaching..." : "Regenerate AI coaching"}
        onPress={onGenerate}
        disabled={isGenerating}
        busy={isGenerating}
        testID="regenerate-ai-coaching"
        secondary
      />
    </View>
  );
}

function EvidenceStrip({
  frameIds,
  frames,
  result,
  artifactSource,
}: {
  frameIds: string[];
  frames: Map<string, SubmittedEvidenceFrame>;
  result: AnalysisResult;
  artifactSource: Props["artifactSource"];
}) {
  const available = frameIds.flatMap((frameId) => {
    const frame = frames.get(frameId);
    if (!frame) return [];
    const path = result.artifact_urls[`llm_frame:${frame.image_file}`];
    return path ? [{ frame, path }] : [];
  });
  if (!available.length) return null;
  return (
    <View style={styles.evidenceGroup}>
      <Text style={styles.smallTitle}>Supporting frames</Text>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.evidenceStrip}>
        {available.map(({ frame, path }) => (
          <View key={frame.frame_id} style={styles.frameCard}>
            <Image source={artifactSource(path)} style={styles.frameImage} resizeMode="contain" />
            <Text style={styles.frameLabel}>{frame.frame_id}</Text>
            <Text style={styles.frameCaption}>{frame.phase_relations.join(" · ")}</Text>
          </View>
        ))}
      </ScrollView>
    </View>
  );
}

function RelatedMetrics({ keys, result }: { keys: string[]; result: AnalysisResult }) {
  const metrics = keys.flatMap((key) => result.metrics_summary[key] ? [{ key, metric: result.metrics_summary[key] }] : []);
  if (!metrics.length) return null;
  return (
    <View style={styles.metricBox}>
      <Text style={styles.smallTitle}>Supporting measurements</Text>
      {metrics.map(({ key, metric }) => (
        <Text key={key} style={styles.metricText}>{metric.name}: {String(metric.value)} {metric.unit ?? ""}</Text>
      ))}
    </View>
  );
}

function BulletList({ title, items, ordered = false }: { title: string; items: string[]; ordered?: boolean }) {
  if (!items.length) return null;
  return (
    <View style={styles.list}>
      <Text style={styles.smallTitle}>{title}</Text>
      {items.map((item, index) => <Text key={`${item}-${index}`} style={styles.body}>{ordered ? `${index + 1}.` : "•"} {item}</Text>)}
    </View>
  );
}

function TextList({ title, items, tone }: { title: string; items: string[]; tone?: "positive" }) {
  if (!items.length) return null;
  return (
    <View style={styles.group}>
      <Text style={styles.groupTitle}>{title}</Text>
      {items.map((item, index) => (
        <View key={`${item}-${index}`} style={tone === "positive" ? styles.positiveItem : styles.listItem}>
          <Text style={styles.body}>• {item}</Text>
        </View>
      ))}
    </View>
  );
}

function Banner({ kind, text }: { kind: "warning" | "error"; text: string }) {
  return (
    <View accessibilityRole="alert" style={kind === "error" ? styles.errorBanner : styles.warningBanner}>
      <Text style={kind === "error" ? styles.errorText : styles.warningText}>{text}</Text>
    </View>
  );
}

function ActionButton({
  label,
  onPress,
  disabled = false,
  busy = false,
  secondary = false,
  testID,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  busy?: boolean;
  secondary?: boolean;
  testID?: string;
}) {
  return (
    <Pressable
      testID={testID}
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityState={{ disabled, busy }}
      style={[secondary ? styles.secondaryButton : styles.primaryButton, disabled && styles.disabled]}
    >
      <View style={styles.buttonContent}>
        {busy ? <ActivityIndicator color={secondary ? "#245c4f" : "white"} size="small" /> : null}
        <Text style={secondary ? styles.secondaryButtonText : styles.primaryButtonText}>{label}</Text>
      </View>
    </Pressable>
  );
}

function confidenceLabel(confidence: number) {
  return `${Math.round(confidence * 100)}% confidence`;
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

const styles = StyleSheet.create({
  section: { gap: 12 },
  title: { fontSize: 22, fontWeight: "800", color: "#1f2723" },
  overview: { color: "#303833", lineHeight: 22, fontSize: 16 },
  body: { color: "#303833", lineHeight: 21 },
  caption: { color: "#617068", lineHeight: 18, fontSize: 12 },
  group: { gap: 10 },
  groupTitle: { fontSize: 18, fontWeight: "800", color: "#1f2723" },
  card: { borderWidth: 1, borderColor: "#c9d0cb", borderRadius: 10, padding: 14, gap: 10, backgroundColor: "white" },
  headingRow: { gap: 4 },
  cardTitle: { color: "#1f2723", fontSize: 17, lineHeight: 22, fontWeight: "800" },
  confidence: { color: "#617068", fontSize: 12 },
  cue: { borderLeftWidth: 4, borderLeftColor: "#245c4f", backgroundColor: "#e3f0e9", padding: 12, gap: 3 },
  cueLabel: { color: "#1d5941", fontSize: 12, fontWeight: "800", textTransform: "uppercase" },
  cueText: { color: "#1d5941", lineHeight: 21, fontWeight: "700" },
  list: { gap: 5 },
  smallTitle: { color: "#303833", fontWeight: "800" },
  listItem: { paddingVertical: 2 },
  positiveItem: { backgroundColor: "#eef6f1", borderRadius: 6, padding: 10 },
  metricBox: { backgroundColor: "#f1f3f1", borderRadius: 6, padding: 10, gap: 4 },
  metricText: { color: "#303833", lineHeight: 19 },
  evidenceGroup: { gap: 7 },
  evidenceStrip: { gap: 10, paddingRight: 8 },
  frameCard: { width: 210, gap: 4 },
  frameImage: { width: 210, height: 240, backgroundColor: "#102b3d", borderRadius: 6 },
  frameLabel: { color: "#303833", fontWeight: "700", fontSize: 12 },
  frameCaption: { color: "#617068", fontSize: 11, lineHeight: 15 },
  warningBanner: { borderLeftWidth: 4, borderLeftColor: "#b36b18", backgroundColor: "#fbf0dc", padding: 12 },
  warningText: { color: "#70410e", lineHeight: 20 },
  errorBanner: { borderLeftWidth: 4, borderLeftColor: "#a23c20", backgroundColor: "#f8e8e2", padding: 12 },
  errorText: { color: "#5d2c20", lineHeight: 20 },
  primaryButton: { backgroundColor: "#245c4f", borderRadius: 7, padding: 14, alignItems: "center" },
  secondaryButton: { borderWidth: 1, borderColor: "#245c4f", borderRadius: 7, padding: 13, alignItems: "center" },
  disabled: { opacity: 0.7 },
  buttonContent: { minHeight: 20, flexDirection: "row", alignItems: "center", justifyContent: "center", gap: 8 },
  primaryButtonText: { color: "white", fontWeight: "800" },
  secondaryButtonText: { color: "#245c4f", fontWeight: "800" },
});
