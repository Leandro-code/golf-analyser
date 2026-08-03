package com.golfanalyser.app.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.golfanalyser.app.analysis.AnalysisContext
import com.golfanalyser.app.analysis.LandmarkFrame
import com.golfanalyser.app.analysis.MediaPipePoseExtractor
import com.golfanalyser.app.analysis.MetricSet
import com.golfanalyser.app.analysis.SwingMetrics
import com.golfanalyser.app.analysis.SwingPhases
import com.golfanalyser.app.analysis.VideoMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

internal fun isUsableEvidenceJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
        bytes[bytes.lastIndex - 1] == 0xFF.toByte() && bytes[bytes.lastIndex] == 0xD9.toByte()

class AnalysisRepository(
    appContext: Context,
    private val openAiClient: OpenAiAssessmentClient = OpenAiAssessmentClient(),
) {
    private val contentResolver = appContext.contentResolver
    private val rootDir = File(appContext.filesDir, "analyses").apply { mkdirs() }
    private val settings = AppSettings(appContext)
    private val poseExtractor = MediaPipePoseExtractor(appContext)

    suspend fun createAnalysis(
        videoUri: Uri,
        context: ContextPayload,
        onStatus: (AnalysisStatusResponse) -> Unit = {},
    ): AnalysisCreateResponse = withContext(Dispatchers.IO) {
        val runId = newRunId()
        val runDir = File(rootDir, runId).apply { mkdirs() }
        val original = File(runDir, "original.mp4")
        val createdAt = nowIso()
        var latestProgress = 0.0

        fun reportStatus(
            progress: Double,
            message: String,
            status: String = "processing",
            error: String? = null,
        ): AnalysisStatusResponse {
            latestProgress = progress
            val update = AnalysisStatusResponse(
                runId = runId,
                status = status,
                progress = progress,
                message = message,
                error = error,
                createdAt = createdAt,
                updatedAt = nowIso(),
            )
            writeStatus(runDir, update)
            onStatus(update)
            return update
        }

        reportStatus(0.02, "Preparing selected video")
        try {
            contentResolver.openInputStream(videoUri)?.use { input ->
                original.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to open selected video.")

            reportStatus(0.08, "Reading video details")
            val result = analyseLocal(runId, runDir, original, context) { progress, message ->
                reportStatus(progress, message)
            }
            reportStatus(0.96, "Saving analysis")
            writeResult(runDir, result)
            val completed = reportStatus(1.0, "Complete", status = "completed")
            AnalysisCreateResponse(
                runId = runId,
                statusUrl = localStatusPath(runId),
                resultUrl = localResultPath(runId),
                status = completed,
            )
        } catch (throwable: Throwable) {
            reportStatus(
                progress = latestProgress,
                message = throwable.message ?: "Analysis failed.",
                status = "failed",
                error = throwable.message ?: "Analysis failed.",
            )
            throw throwable
        }
    }

    suspend fun listAnalyses(): List<AnalysisResultResponse> = withContext(Dispatchers.IO) {
        rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { runCatching { currentResult(readResult(it)) }.getOrNull() }
            ?.sortedByDescending { it.runId }
            ?: emptyList()
    }

    suspend fun getAnalysis(runId: String): AnalysisResultResponse = withContext(Dispatchers.IO) {
        currentResult(readResult(runDir(runId)))
    }

    suspend fun confirmPhases(runId: String, frameIndices: List<Int>): AnalysisResultResponse = withContext(Dispatchers.IO) {
        require(frameIndices.size == SwingPhases.phaseNames.size) { "Expected all 9 phase markers." }
        val runDir = runDir(runId)
        val current = readResult(runDir)
        val fps = current.metadata?.get("fps")?.asDoubleOrNull() ?: 30.0
        val phases = SwingPhases.buildConfirmed(frameIndices.first(), frameIndices.drop(1), fps)
        val landmarks = readLandmarks(runDir)
        val metrics = SwingMetrics.calculate(landmarks, phases, current.context?.toAnalysisContext())
        val updated = current.copy(
            phases = phases.toDtos(),
            metricsSummary = metrics.summaryDtos(),
            qualityFlags = metrics.quality,
            llmAssessment = null,
            llmAssessmentCurrent = false,
            llmAssessmentStale = current.llmAssessment != null,
            llmAssessmentEligibilityIssue = llmEligibilityIssue(metrics),
        )
        writeResult(runDir, updated)
        updated
    }

    suspend fun createLlmAssessment(
        runId: String,
        force: Boolean = false,
        onDraft: (LlmContentDraft) -> Unit = {},
    ): AnalysisResultResponse = withContext(Dispatchers.IO) {
        val runDir = runDir(runId)
        val current = currentResult(readResult(runDir))
        current.llmAssessment?.takeIf { current.llmAssessmentCurrent && !force }?.let {
            return@withContext current
        }
        current.llmAssessmentEligibilityIssue?.let { error(it) }
        val openAi = settings.openAiSettings()
        if (!openAi.hasApiKey) {
            error("Add an OpenAI API key in Settings before generating an AI assessment.")
        }
        val submittedFrames = selectEvidenceFrames(current)
        val exportedFrames = exportEvidenceFrames(runDir, current, submittedFrames)
        val assessmentPayload = assessmentPayload(current, submittedFrames)
        val content = openAiClient.generate(openAi, assessmentPayload, exportedFrames, onDraft)
        validateFrameReferences(content, submittedFrames)
        val fingerprint = evidenceFingerprint(current, submittedFrames)
        val assessment = LlmAssessmentDto(
            schemaVersion = SCHEMA_VERSION,
            promptVersion = PROMPT_VERSION,
            model = openAi.model,
            generatedAt = nowIso(),
            context = current.context,
            submittedFrames = submittedFrames,
            qualitySnapshot = qualitySnapshot(current),
            evidenceFingerprint = fingerprint,
            content = content,
        )
        val updated = current.copy(
            llmAssessment = assessment,
            llmAssessmentCurrent = true,
            llmAssessmentStale = false,
            llmAssessmentEligibilityIssue = null,
        )
        writeResult(runDir, updated)
        updated
    }

    fun openAiSettings(): OpenAiSettings = settings.openAiSettings()

    fun saveOpenAiSettings(openAiSettings: OpenAiSettings) {
        settings.saveOpenAiSettings(openAiSettings)
    }

    suspend fun cachedArtifact(
        runId: String,
        artifactName: String,
        artifactPath: String,
    ): String? = artifactPath.takeIf { it.startsWith("file:") || it.startsWith("content:") }

    suspend fun downloadArtifact(
        runId: String,
        artifactName: String,
        artifactPath: String,
        protectedUri: String? = null,
    ): String = artifactPath.takeIf { it.startsWith("file:") || it.startsWith("content:") }
        ?: error("Artifact is not a local file.")

    suspend fun clearDownloadedReplays(protectedUri: String? = null) = Unit

    suspend fun invalidateRunArtifacts(runId: String, protectedUri: String? = null) = Unit

    suspend fun pollUntilComplete(
        runId: String,
        onStatus: (AnalysisStatusResponse) -> Unit,
    ): AnalysisResultResponse {
        val runDir = runDir(runId)
        while (true) {
            val status = readStatus(runDir)
            onStatus(status)
            when (status.status) {
                "completed" -> return readResult(runDir)
                "failed" -> error(status.error ?: status.message)
                else -> delay(250)
            }
        }
    }

    fun artifactUrl(path: String?): String = path.orEmpty()

    private fun analyseLocal(
        runId: String,
        runDir: File,
        original: File,
        context: ContextPayload,
        onProgress: (progress: Double, message: String) -> Unit,
    ): AnalysisResultResponse {
        val metadata = readVideoMetadata(original)
        onProgress(0.1, "Starting pose detection")
        val landmarks = extractLandmarks(metadata) { processedFrames, totalFrames ->
            val frameProgress = processedFrames.toDouble() / totalFrames
            onProgress(
                0.1 + (frameProgress * 0.78),
                "Detecting pose: $processedFrames of $totalFrames frames",
            )
        }
        onProgress(0.9, "Detecting swing phases")
        writeLandmarks(runDir, landmarks)
        val phases = SwingPhases.detect(landmarks, metadata.fps)
        onProgress(0.93, "Calculating swing metrics")
        val metrics = SwingMetrics.calculate(landmarks, phases, context.toAnalysisContext())
        return AnalysisResultResponse(
            runId = runId,
            status = "completed",
            context = context,
            metadata = metadata.toJson(),
            landmarks = landmarks,
            phases = phases.toDtos(),
            metricsSummary = metrics.summaryDtos(),
            qualityFlags = metrics.quality,
            assessment = SwingAssessmentDto(
                findings = emptyList(),
                qualityLimitations = listOf(
                    "On-device deterministic pose analysis is local-first. AI assessment requires a configured OpenAI key.",
                ),
            ),
            llmAssessment = null,
            llmAssessmentEligibilityIssue = llmEligibilityIssue(metrics),
            llmAssessmentCurrent = false,
            llmAssessmentStale = false,
            artifactUrls = mapOf(
                ArtifactCache.ANNOTATED_VIDEO to original.toURI().toString(),
                "original_video" to original.toURI().toString(),
            ),
        )
    }

    private fun extractLandmarks(
        metadata: VideoMetadata,
        onProgress: (processedFrames: Int, totalFrames: Int) -> Unit,
    ): List<LandmarkFrame> {
        val file = File(metadata.sourcePath)
        return poseExtractor.extract(file, metadata, onProgress)
    }

    private fun readVideoMetadata(file: File): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?: 30.0
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                ?: ((durationMs / 1000.0) * fps).roundToInt().coerceAtLeast(1)
            VideoMetadata(
                sourcePath = file.absolutePath,
                fps = fps,
                frameCount = frameCount,
                width = width,
                height = height,
                durationSeconds = durationMs / 1000.0,
            )
        } finally {
            retriever.release()
        }
    }

    private fun ContextPayload.toAnalysisContext(): AnalysisContext =
        AnalysisContext(handedness, cameraView, clubFamily, swingType)

    private fun VideoMetadata.toJson(): Map<String, JsonElement> = linkedMapOf(
        "source_path" to JsonPrimitive(sourcePath),
        "fps" to JsonPrimitive(fps),
        "frame_count" to JsonPrimitive(frameCount),
        "width" to JsonPrimitive(width),
        "height" to JsonPrimitive(height),
        "duration_seconds" to JsonPrimitive(durationSeconds),
    )

    private fun List<com.golfanalyser.app.analysis.SwingPhase>.toDtos(): List<SwingPhaseDto> =
        map { phase ->
            SwingPhaseDto(
                name = phase.name,
                frameIndex = phase.frameIndex,
                timestampSeconds = phase.timestampSeconds,
                confidence = phase.confidence,
                detectionMethod = phase.detectionMethod,
            )
        }

    private fun MetricSet.summaryDtos(): Map<String, MetricDto> =
        metrics.filterKeys { it != "wrist_path_trajectory" }.mapValues { (_, metric) ->
            MetricDto(
                name = metric.name,
                value = metric.value,
                unit = metric.unit,
                description = metric.description,
                frameIndex = metric.frameIndex,
            )
        }

    private fun llmEligibilityIssue(metrics: MetricSet): String? {
        val qualityIssues = metrics.quality["phase_quality_issues"].asListSize()
        val poseRate = metrics.quality["pose_detection_rate"].asDoubleOrNull() ?: 0.0
        return when {
            qualityIssues > 0 -> "Regenerate or confirm phase timing before requesting an AI swing assessment."
            poseRate < 0.65 -> "AI swing assessment is withheld until pose evidence and phase timing are reliable."
            else -> null
        }
    }

    private fun currentResult(result: AnalysisResultResponse): AnalysisResultResponse {
        val issue = llmEligibilityIssue(result)
        val current = result.llmAssessment != null &&
            issue == null &&
            result.llmAssessment.evidenceFingerprint == evidenceFingerprint(result)
        return result.copy(
            llmAssessmentCurrent = current,
            llmAssessmentStale = result.llmAssessment != null && !current,
            llmAssessmentEligibilityIssue = issue,
        )
    }

    private fun llmEligibilityIssue(result: AnalysisResultResponse): String? {
        if (result.context == null || result.assessment == null) {
            return "AI swing assessment requires a contextual analysis."
        }
        val phaseScoped = result.qualityFlags["phase_scoped_metrics"].asBooleanOrFalse()
        if (!phaseScoped) return "Regenerate phase timing before requesting an AI swing assessment."
        if (result.phases.any { it.detectionMethod in SUPERSEDED_PHASE_METHODS }) {
            return "Regenerate phase timing before requesting an AI swing assessment."
        }
        if (result.phases.map { SwingPhases.canonicalPhaseName(it.name) }.toSet().containsAll(reviewPhaseNames(result)).not()) {
            return "Required review phase markers are unavailable for this analysis."
        }
        val qualityIssues = result.qualityFlags["phase_quality_issues"].asListSize()
        val poseRate = result.qualityFlags["pose_detection_rate"].asDoubleOrNull() ?: 0.0
        if (qualityIssues > 0 || poseRate < 0.65) {
            return "AI swing assessment is withheld until pose evidence and phase timing are reliable."
        }
        if (result.artifactUrls["original_video"].isNullOrBlank()) {
            return "The original replay required for AI assessment is unavailable."
        }
        return null
    }

    private fun selectEvidenceFrames(result: AnalysisResultResponse): List<SubmittedEvidenceFrameDto> {
        val byName = result.phases.associateBy { SwingPhases.canonicalPhaseName(it.name) }
        val address = byName.getValue(SwingPhases.ADDRESS_PHASE).frameIndex
        val finish = byName.getValue(SwingPhases.FINISH_PHASE).frameIndex
        val fps = result.metadata?.get("fps").asDoubleOrNull() ?: 30.0
        val neighborFrames = max(1, (fps * NEIGHBOR_SECONDS).roundToInt())
        val relationMap = linkedMapOf<Int, MutableSet<String>>()

        fun addFrame(index: Int, relation: String) {
            val clipped = index.coerceIn(address, finish)
            relationMap.getOrPut(clipped) { sortedSetOf() }.add(relation)
        }

        addFrame(address, "${SwingPhases.ADDRESS_PHASE} anchor")
        reviewPhaseNames(result).forEach { phaseName ->
            if (phaseName == SwingPhases.ADDRESS_PHASE) return@forEach
            val phase = byName.getValue(phaseName)
            val offsets = if (
                phaseName in setOf(
                    SwingPhases.TOP_PHASE,
                    SwingPhases.P6_PHASE,
                    SwingPhases.IMPACT_PHASE,
                    SwingPhases.FINISH_PHASE,
                )
            ) {
                listOf(-neighborFrames, 0, neighborFrames)
            } else {
                listOf(0)
            }
            offsets.forEach { offset ->
                val actualIndex = (phase.frameIndex + offset).coerceIn(address, finish)
                val actualOffset = (actualIndex - phase.frameIndex) / fps.coerceAtLeast(1e-9)
                addFrame(
                    phase.frameIndex + offset,
                    if (actualIndex == phase.frameIndex) {
                        "$phaseName anchor"
                    } else {
                        "$phaseName ${"%+.2f".format(actualOffset)}s"
                    },
                )
            }
        }
        return relationMap.entries.map { (frameIndex, relations) ->
            SubmittedEvidenceFrameDto(
                frameId = "frame_${frameIndex.toString().padStart(6, '0')}",
                frameIndex = frameIndex,
                timestampSeconds = kotlin.math.round((frameIndex / fps.coerceAtLeast(1e-9)) * 10_000.0) / 10_000.0,
                phaseRelations = relations.toList(),
                imageFile = "frame_${frameIndex.toString().padStart(6, '0')}.jpg",
            )
        }
    }

    private fun reviewPhaseNames(result: AnalysisResultResponse): List<String> {
        val names = mutableListOf(
            SwingPhases.ADDRESS_PHASE,
            SwingPhases.P3_PHASE,
            SwingPhases.TOP_PHASE,
            SwingPhases.P5_PHASE,
        )
        if (result.context?.cameraView == "down_the_line") {
            names += SwingPhases.P6_PHASE
        }
        names += listOf(SwingPhases.IMPACT_PHASE, SwingPhases.P8_PHASE, SwingPhases.FINISH_PHASE)
        return names
    }

    private fun exportEvidenceFrames(
        runDir: File,
        result: AnalysisResultResponse,
        submittedFrames: List<SubmittedEvidenceFrameDto>,
    ): List<ExportedEvidenceFrame> {
        val original = File(Uri.parse(result.artifactUrls.getValue("original_video")).path.orEmpty())
        require(original.isFile) { "The original replay required for AI assessment is unavailable." }
        val evidenceDir = File(runDir, "llm_frames").apply { mkdirs() }
        val preparedFrames = submittedFrames.map { evidenceFrame ->
            val destination = File(evidenceDir, evidenceFrame.imageFile)
            require(destination.parentFile == evidenceDir && destination.name == evidenceFrame.imageFile) {
                "Invalid AI evidence image path."
            }
            val cachedJpeg = runCatching { destination.readBytes() }
                .getOrNull()
                ?.takeIf(::isUsableEvidenceJpeg)
            val jpeg = cachedJpeg ?: exportEvidenceJpeg(original, evidenceFrame)
            Triple(evidenceFrame, destination, jpeg)
        }

        preparedFrames.forEach { (_, destination, jpeg) ->
            val existing = runCatching { destination.readBytes() }.getOrNull()
            if (existing == null || !existing.contentEquals(jpeg)) {
                val staged = File(evidenceDir, "${destination.name}.pending")
                staged.writeBytes(jpeg)
                if (destination.exists() && !destination.delete()) {
                    staged.delete()
                    error("Unable to replace cached AI evidence ${destination.name}.")
                }
                if (!staged.renameTo(destination)) {
                    staged.delete()
                    error("Unable to save cached AI evidence ${destination.name}.")
                }
            }
        }
        val expectedNames = submittedFrames.mapTo(mutableSetOf()) { it.imageFile }
        evidenceDir.listFiles { file -> file.name.startsWith("frame_") && file.extension == "jpg" }
            ?.filterNot { it.name in expectedNames }
            ?.forEach { it.delete() }

        return preparedFrames.map { (evidenceFrame, _, jpeg) ->
            ExportedEvidenceFrame(
                frame = evidenceFrame,
                dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP),
            )
        }
    }

    private fun exportEvidenceJpeg(
        original: File,
        evidenceFrame: SubmittedEvidenceFrameDto,
    ): ByteArray {
        val bitmap = decodeEvidenceBitmap(original, evidenceFrame)
            ?: error("Unable to export AI evidence frame ${evidenceFrame.frameIndex} after retrying the video decoder.")
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    "Unable to encode AI evidence frame ${evidenceFrame.frameIndex}."
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeEvidenceBitmap(
        original: File,
        evidenceFrame: SubmittedEvidenceFrameDto,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            repeat(2) {
                retrieveVideoBitmap(original) { retriever ->
                    retriever.getFrameAtIndex(evidenceFrame.frameIndex)
                }?.let { return it }
            }
        }

        val targetUs = (evidenceFrame.timestampSeconds * 1_000_000).roundToLong()
        val retryOffsetsUs = listOf(0L, 0L, -8_000L, 8_000L)
        retryOffsetsUs.forEach { offsetUs ->
            retrieveVideoBitmap(original) { retriever ->
                retriever.getFrameAtTime(
                    (targetUs + offsetUs).coerceAtLeast(0L),
                    MediaMetadataRetriever.OPTION_CLOSEST,
                )
            }?.let { return it }
        }
        return null
    }

    private fun retrieveVideoBitmap(
        original: File,
        retrieve: (MediaMetadataRetriever) -> Bitmap?,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(original.absolutePath)
            retrieve(retriever)
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun assessmentPayload(
        result: AnalysisResultResponse,
        submittedFrames: List<SubmittedEvidenceFrameDto>,
    ): JsonObject = buildJsonObject {
        put("context", json.encodeToJsonElement(result.context))
        put("quality", JsonObject(qualitySnapshot(result)))
        put("phases", json.encodeToJsonElement(result.phases))
        put("submitted_frames", json.encodeToJsonElement(submittedFrames))
        put("measurements", measurements(result))
    }

    private fun qualitySnapshot(result: AnalysisResultResponse): Map<String, JsonElement> {
        val keys = listOf(
            "pose_detection_rate",
            "frames_with_pose",
            "frames_total",
            "phase_scoped_metrics",
            "phase_markers_confirmed",
            "phase_quality_issues",
        )
        return keys.mapNotNull { key -> result.qualityFlags[key]?.let { key to it } }.toMap()
    }

    private fun measurements(result: AnalysisResultResponse): JsonArray = buildJsonArray {
        result.metricsSummary.forEach { (key, metric) ->
            add(
                buildJsonObject {
                    put("metric_key", key)
                    put("name", metric.name)
                    metric.value?.let { put("value", it) }
                    metric.unit?.let { put("unit", it) }
                    metric.description?.let { put("description", it) }
                    metric.frameIndex?.let { put("frame_index", it) }
                },
            )
        }
    }

    private fun evidenceFingerprint(
        result: AnalysisResultResponse,
        submittedFrames: List<SubmittedEvidenceFrameDto> = selectEvidenceFrames(result),
    ): String {
        val payload = buildJsonObject {
            put("context", json.encodeToJsonElement(result.context))
            put("phases", json.encodeToJsonElement(result.phases))
            put("submitted_frames", json.encodeToJsonElement(submittedFrames))
            put("metrics", measurements(result))
            put("quality", JsonObject(qualitySnapshot(result)))
        }.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun validateFrameReferences(
        content: LlmContentDto,
        submittedFrames: List<SubmittedEvidenceFrameDto>,
    ) {
        val knownIds = submittedFrames.map { it.frameId }.toSet()
        val usedIds = (content.observations.flatMap { it.supportingFrameIds } +
            content.priorities.flatMap { it.supportingFrameIds }).toSet()
        val unknownIds = usedIds - knownIds
        if (unknownIds.isNotEmpty()) {
            error("OpenAI returned unsupported evidence frame IDs: ${unknownIds.sorted().joinToString(", ")}")
        }
    }

    private fun writeResult(runDir: File, result: AnalysisResultResponse) {
        File(runDir, RESULT_FILE).writeText(json.encodeToString(result))
    }

    private fun readResult(runDir: File): AnalysisResultResponse =
        json.decodeFromString(File(runDir, RESULT_FILE).readText())

    private fun writeStatus(runDir: File, status: AnalysisStatusResponse) {
        File(runDir, STATUS_FILE).writeText(json.encodeToString(status))
    }

    private fun readStatus(runDir: File): AnalysisStatusResponse =
        json.decodeFromString(File(runDir, STATUS_FILE).readText())

    private fun writeLandmarks(runDir: File, landmarks: List<LandmarkFrame>) {
        File(runDir, LANDMARKS_FILE).writeText(json.encodeToString(landmarks))
    }

    private fun readLandmarks(runDir: File): List<LandmarkFrame> =
        json.decodeFromString(File(runDir, LANDMARKS_FILE).readText())

    private fun runDir(runId: String): File {
        require(File(runId).name == runId) { "Invalid analysis id." }
        return File(rootDir, runId)
    }

    private fun newRunId(): String =
        "swing_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now()) + "_" + System.nanoTime().toString(16).takeLast(6)

    private fun nowIso(): String = Instant.now().toString()
    private fun localStatusPath(runId: String): String = "local://analyses/$runId/status"
    private fun localResultPath(runId: String): String = "local://analyses/$runId"

    private fun JsonElement?.asDoubleOrNull(): Double? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()
    private fun JsonElement?.asBooleanOrFalse(): Boolean = (this as? JsonPrimitive)?.content == "true"
    private fun JsonElement?.asListSize(): Int = when (this) {
        is JsonArray -> size
        else -> 0
    }

    companion object {
        private const val RESULT_FILE = "result.json"
        private const val STATUS_FILE = "status.json"
        private const val LANDMARKS_FILE = "landmarks.json"
        private const val SCHEMA_VERSION = "1.0.0"
        private const val PROMPT_VERSION = "1.0.0"
        private const val NEIGHBOR_SECONDS = 0.1
        private val SUPERSEDED_PHASE_METHODS = setOf(
            "minimum_wrist_y_coordinate",
            "closest_wrist_return_to_address_after_top",
            "detected_active_swing_onset",
        )
    }
}
