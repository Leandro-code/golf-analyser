package com.golfanalyser.app.data

import com.golfanalyser.app.analysis.LandmarkFrame
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ContextPayload(
    val handedness: String,
    @SerialName("camera_view") val cameraView: String,
    @SerialName("club_family") val clubFamily: String,
    @SerialName("swing_type") val swingType: String = "full_swing",
)

@Serializable
data class AnalysisStatusResponse(
    @SerialName("run_id") val runId: String,
    val status: String,
    val progress: Double,
    val message: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val error: String? = null,
)

@Serializable
data class AnalysisCreateResponse(
    @SerialName("run_id") val runId: String,
    @SerialName("status_url") val statusUrl: String,
    @SerialName("result_url") val resultUrl: String,
    val status: AnalysisStatusResponse,
)

@Serializable
data class AnalysisJobSpec(
    @SerialName("video_uri") val videoUri: String,
    val context: ContextPayload,
)

@Serializable
data class SwingPhaseDto(
    val name: String,
    @SerialName("frame_index") val frameIndex: Int,
    @SerialName("timestamp_seconds") val timestampSeconds: Double,
    val confidence: Double,
    @SerialName("detection_method") val detectionMethod: String,
)

@Serializable
data class MetricDto(
    val name: String,
    val value: JsonElement? = null,
    val unit: String? = null,
    val description: String? = null,
    @SerialName("frame_index") val frameIndex: Int? = null,
)

@Serializable
data class TechniqueReferenceDto(
    val id: String? = null,
    val name: String,
    val rationale: String? = null,
    @SerialName("correction_cue") val correctionCue: String? = null,
)

@Serializable
data class AssessmentFindingDto(
    val reference: TechniqueReferenceDto,
    val status: String,
    @SerialName("observed_value") val observedValue: JsonElement? = null,
    val expected: String? = null,
    @SerialName("phase_name") val phaseName: String? = null,
    val confidence: Double? = null,
    @SerialName("evidence_keyframe") val evidenceKeyframe: String? = null,
    val note: String? = null,
)

@Serializable
data class SwingAssessmentDto(
    val findings: List<AssessmentFindingDto> = emptyList(),
    @SerialName("quality_limitations") val qualityLimitations: List<String> = emptyList(),
)

@Serializable
data class SubmittedEvidenceFrameDto(
    @SerialName("frame_id") val frameId: String,
    @SerialName("frame_index") val frameIndex: Int,
    @SerialName("timestamp_seconds") val timestampSeconds: Double,
    @SerialName("phase_relations") val phaseRelations: List<String> = emptyList(),
    @SerialName("image_file") val imageFile: String,
)

@Serializable
data class LlmObservationDto(
    val title: String,
    val observation: String,
    @SerialName("supporting_frame_ids") val supportingFrameIds: List<String> = emptyList(),
    @SerialName("related_metric_keys") val relatedMetricKeys: List<String> = emptyList(),
    val confidence: Double,
)

@Serializable
data class LlmPriorityDto(
    val title: String,
    val rationale: String,
    @SerialName("practice_cue") val practiceCue: String,
    val explanation: String? = null,
    val drills: List<String> = emptyList(),
    @SerialName("practice_plan") val practicePlan: List<String> = emptyList(),
    @SerialName("supporting_frame_ids") val supportingFrameIds: List<String> = emptyList(),
    @SerialName("related_metric_keys") val relatedMetricKeys: List<String> = emptyList(),
    val confidence: Double,
    @SerialName("support_type") val supportType: String = "ai_generated",
)

@Serializable
data class LlmContentDto(
    val overview: String,
    val strengths: List<String> = emptyList(),
    val observations: List<LlmObservationDto> = emptyList(),
    val priorities: List<LlmPriorityDto> = emptyList(),
    val limitations: List<String> = emptyList(),
)

data class LlmObservationDraft(
    val title: String = "",
    val observation: String = "",
)

data class LlmPriorityDraft(
    val title: String = "",
    val rationale: String = "",
    val practiceCue: String = "",
    val explanation: String = "",
    val drills: List<String> = emptyList(),
    val practicePlan: List<String> = emptyList(),
)

data class LlmContentDraft(
    val overview: String = "",
    val strengths: List<String> = emptyList(),
    val observations: List<LlmObservationDraft> = emptyList(),
    val priorities: List<LlmPriorityDraft> = emptyList(),
    val limitations: List<String> = emptyList(),
) {
    val hasVisibleContent: Boolean
        get() = overview.isNotBlank() ||
            strengths.any(String::isNotBlank) ||
            observations.any { it.title.isNotBlank() || it.observation.isNotBlank() } ||
            priorities.any {
                it.title.isNotBlank() || it.rationale.isNotBlank() ||
                    it.practiceCue.isNotBlank() || it.explanation.isNotBlank() ||
                    it.drills.any(String::isNotBlank) || it.practicePlan.any(String::isNotBlank)
            } || limitations.any(String::isNotBlank)
}

enum class AiAssessmentStage(val step: Int) {
    PREPARING_IMAGES(1),
    SENDING_REQUEST(2),
    ANALYSING_SWING(3),
    WRITING_ADVICE(4),
    SAVING_ASSESSMENT(5),
}

@Serializable
data class CoachingFocusDto(
    val goals: List<String> = emptyList(),
    @SerialName("custom_note") val customNote: String? = null,
)

@Serializable
data class LlmAssessmentDto(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("prompt_version") val promptVersion: String,
    val model: String,
    @SerialName("generated_at") val generatedAt: String,
    val context: ContextPayload? = null,
    @SerialName("submitted_frames") val submittedFrames: List<SubmittedEvidenceFrameDto> = emptyList(),
    @SerialName("quality_snapshot") val qualitySnapshot: Map<String, JsonElement> = emptyMap(),
    @SerialName("evidence_fingerprint") val evidenceFingerprint: String? = null,
    @SerialName("coaching_focus") val coachingFocus: CoachingFocusDto? = null,
    val content: LlmContentDto,
)

@Serializable
data class AnalysisResultResponse(
    @SerialName("run_id") val runId: String,
    val status: String = "completed",
    val context: ContextPayload? = null,
    val metadata: Map<String, JsonElement>? = null,
    val landmarks: List<LandmarkFrame> = emptyList(),
    val phases: List<SwingPhaseDto> = emptyList(),
    @SerialName("metrics_summary") val metricsSummary: Map<String, MetricDto> = emptyMap(),
    @SerialName("quality_flags") val qualityFlags: Map<String, JsonElement> = emptyMap(),
    val assessment: SwingAssessmentDto? = null,
    @SerialName("llm_assessment") val llmAssessment: LlmAssessmentDto? = null,
    @SerialName("llm_assessment_eligibility_issue") val llmAssessmentEligibilityIssue: String? = null,
    @SerialName("llm_assessment_current") val llmAssessmentCurrent: Boolean = false,
    @SerialName("llm_assessment_stale") val llmAssessmentStale: Boolean = false,
    @SerialName("artifact_urls") val artifactUrls: Map<String, String> = emptyMap(),
)

@Serializable
data class PhaseConfirmationRequest(
    @SerialName("frame_indices") val frameIndices: List<Int>,
)
