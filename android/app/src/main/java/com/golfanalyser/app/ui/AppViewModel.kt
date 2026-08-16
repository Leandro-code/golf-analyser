package com.golfanalyser.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.golfanalyser.app.data.AnalysisRepository
import com.golfanalyser.app.data.AnalysisResultResponse
import com.golfanalyser.app.data.AnalysisStatusResponse
import com.golfanalyser.app.data.AnalysisStorageUsage
import com.golfanalyser.app.data.AnalysisWorkCoordinator
import com.golfanalyser.app.data.AiAssessmentStage
import com.golfanalyser.app.data.ArtifactCache
import com.golfanalyser.app.data.ContextPayload
import com.golfanalyser.app.data.CoachingFocusDto
import com.golfanalyser.app.data.LlmContentDraft
import com.golfanalyser.app.data.OpenAiSettings
import com.golfanalyser.app.data.openAiErrorMessage
import com.golfanalyser.app.data.buildPhaseConfirmationPayload
import com.golfanalyser.app.data.normalized
import com.golfanalyser.app.data.toggle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

val phaseOrder = listOf(
    "Address",
    "Takeaway",
    "Lead arm parallel backswing (P3)",
    "Top (P4)",
    "Lead arm parallel downswing (P5)",
    "Shaft parallel downswing (P6)",
    "Impact approximation (P7)",
    "Shaft parallel follow-through (P8)",
    "Finish",
)

sealed interface ReplayState {
    data object NotLoaded : ReplayState
    data object Downloading : ReplayState
    data class Ready(val uri: String) : ReplayState
    data class Failed(val message: String) : ReplayState
}

data class AppUiState(
    val screen: Screen = Screen.NewSwing,
    val selectedVideo: Uri? = null,
    val context: ContextPayload = ContextPayload(
        handedness = "right",
        cameraView = "face_on",
        clubFamily = "iron",
    ),
    val status: AnalysisStatusResponse? = null,
    val result: AnalysisResultResponse? = null,
    val history: List<AnalysisResultResponse> = emptyList(),
    val storageUsage: AnalysisStorageUsage = AnalysisStorageUsage(0L, 0, emptyMap()),
    val phaseFrames: Map<String, String> = emptyMap(),
    val openAiApiKey: String = "",
    val openAiModel: String = "",
    val hasSavedOpenAiApiKey: Boolean = false,
    val replayState: ReplayState = ReplayState.NotLoaded,
    val isBusy: Boolean = false,
    val isGeneratingAi: Boolean = false,
    val aiAssessmentStage: AiAssessmentStage? = null,
    val aiAssessmentDraft: LlmContentDraft? = null,
    val coachingFocus: CoachingFocusDto = CoachingFocusDto(),
    val message: String? = null,
    val error: String? = null,
)

enum class Screen {
    NewSwing,
    Processing,
    Result,
    History,
    PhaseReview,
    Settings,
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnalysisRepository(application)
    private val analysisWork = AnalysisWorkCoordinator(application)
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState
    private var pollingJob: Job? = null
    private var replayJob: Job? = null
    private var aiGenerationJob: Job? = null
    private var screenBeforeSettings: Screen = Screen.NewSwing

    init {
        val openAi = repository.openAiSettings()
        val coachingFocus = repository.coachingFocus()
        _uiState.update {
            it.copy(
                openAiApiKey = openAi.apiKey,
                openAiModel = openAi.model,
                hasSavedOpenAiApiKey = openAi.hasApiKey,
                coachingFocus = coachingFocus,
            )
        }
        resumePendingAnalysis()
    }

    fun selectVideo(uri: Uri) {
        _uiState.update { it.copy(selectedVideo = uri, error = null, message = null) }
    }

    fun updateContext(context: ContextPayload) {
        _uiState.update { it.copy(context = context) }
    }

    fun showNewSwing() {
        pollingJob?.cancel()
        replayJob?.cancel()
        cancelAiGeneration()
        _uiState.update {
            it.copy(
                screen = Screen.NewSwing,
                status = null,
                result = null,
                phaseFrames = emptyMap(),
                replayState = ReplayState.NotLoaded,
                isBusy = false,
                isGeneratingAi = false,
                aiAssessmentDraft = null,
                error = null,
                message = null,
            )
        }
    }

    fun showSettings() {
        cancelAiGeneration()
        if (_uiState.value.screen != Screen.Settings) {
            screenBeforeSettings = _uiState.value.screen
        }
        val openAi = repository.openAiSettings()
        _uiState.update {
            it.copy(
                screen = Screen.Settings,
                openAiApiKey = openAi.apiKey,
                openAiModel = openAi.model,
                hasSavedOpenAiApiKey = openAi.hasApiKey,
                error = null,
                message = null,
            )
        }
    }

    fun closeSettings() {
        val openAi = repository.openAiSettings()
        val destination = when (screenBeforeSettings) {
            Screen.Processing, Screen.Settings -> Screen.NewSwing
            Screen.Result -> if (_uiState.value.result == null) Screen.NewSwing else Screen.Result
            else -> screenBeforeSettings
        }
        _uiState.update {
            it.copy(
                screen = destination,
                openAiApiKey = openAi.apiKey,
                openAiModel = openAi.model,
                hasSavedOpenAiApiKey = openAi.hasApiKey,
                error = null,
                message = null,
            )
        }
    }

    fun updateOpenAiApiKey(value: String) {
        _uiState.update { it.copy(openAiApiKey = value) }
    }

    fun saveSettings() {
        val settings = OpenAiSettings(
            apiKey = _uiState.value.openAiApiKey.trim(),
        )
        repository.saveOpenAiSettings(settings)
        val savedSettings = repository.openAiSettings()
        _uiState.update {
            it.copy(
                openAiApiKey = savedSettings.apiKey,
                openAiModel = savedSettings.model,
                hasSavedOpenAiApiKey = savedSettings.hasApiKey,
                message = if (savedSettings.hasApiKey) "API key saved." else "API key removed.",
                error = null,
            )
        }
    }

    fun showResult(result: AnalysisResultResponse) {
        replayJob?.cancel()
        cancelAiGeneration()
        _uiState.update {
            it.copy(
                screen = Screen.Result,
                result = result,
                phaseFrames = result.phases.associate { phase -> phase.name to phase.frameIndex.toString() },
                replayState = ReplayState.NotLoaded,
                isBusy = false,
                isGeneratingAi = false,
                aiAssessmentDraft = null,
                coachingFocus = result.llmAssessment
                    ?.let { it.coachingFocus ?: CoachingFocusDto() }
                    ?: repository.coachingFocus(),
                error = null,
                message = null,
            )
        }
        resolveReplay(result)
    }

    fun submitAnalysis() {
        val video = _uiState.value.selectedVideo
        if (video == null) {
            _uiState.update { it.copy(error = "Choose a swing video first.") }
            return
        }
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            runCatching {
                _uiState.update {
                    it.copy(
                        screen = Screen.Processing,
                        status = null,
                        isBusy = true,
                        error = null,
                        message = "Preparing selected video",
                    )
                }
                val created = repository.reserveAnalysis(video, _uiState.value.context)
                _uiState.update { it.copy(status = created.status, message = created.status.message) }
                analysisWork.enqueue(created.runId)
                repository.pollUntilComplete(created.runId) { status ->
                    _uiState.update {
                        it.copy(status = status, message = status.message, screen = Screen.Processing)
                    }
                }
            }.onSuccess { result ->
                showResult(result)
            }.onFailure { throwable ->
                if (throwable is CancellationException) return@onFailure
                _uiState.update {
                    it.copy(
                        screen = Screen.NewSwing,
                        isBusy = false,
                        error = throwable.message ?: "Analysis failed.",
                    )
                }
            }
        }
    }

    fun cancelPoseAnalysis() {
        val runId = _uiState.value.status?.runId ?: return
        pollingJob?.cancel()
        pollingJob = null
        viewModelScope.launch {
            repository.cancelAnalysis(runId)
            analysisWork.cancel(runId)
            _uiState.update {
                it.copy(
                    screen = Screen.NewSwing,
                    status = null,
                    isBusy = false,
                    message = "Analysis cancelled. The selected video remains available to retry.",
                    error = null,
                )
            }
        }
    }

    fun loadHistory() {
        cancelAiGeneration()
        viewModelScope.launch {
            runCatching {
                _uiState.update { it.copy(screen = Screen.History, isBusy = true, error = null) }
                repository.listAnalyses() to repository.storageUsage()
            }.onSuccess { (history, usage) ->
                _uiState.update { it.copy(history = history, storageUsage = usage, isBusy = false) }
            }.onFailure { throwable ->
                _uiState.update { it.copy(isBusy = false, error = throwable.message ?: "Unable to load history.") }
            }
        }
    }

    fun deleteAnalysis(runId: String) {
        if (_uiState.value.isBusy) return
        cancelAiGeneration()
        replayJob?.cancel()
        viewModelScope.launch {
            runCatching {
                _uiState.update { it.copy(isBusy = true, error = null, message = "Deleting analysis...") }
                repository.deleteAnalysis(runId)
                repository.listAnalyses() to repository.storageUsage()
            }.onSuccess { (history, usage) ->
                _uiState.update { state ->
                    state.copy(
                        screen = Screen.History,
                        history = history,
                        storageUsage = usage,
                        result = if (state.result?.runId == runId) null else state.result,
                        replayState = if (state.result?.runId == runId) ReplayState.NotLoaded else state.replayState,
                        isBusy = false,
                        message = "Analysis deleted.",
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(isBusy = false, message = null, error = throwable.message ?: "Unable to delete analysis.")
                }
            }
        }
    }

    fun deleteAllLocalData() {
        if (_uiState.value.isBusy) return
        pollingJob?.cancel()
        replayJob?.cancel()
        cancelAiGeneration()
        viewModelScope.launch {
            runCatching {
                _uiState.update { it.copy(isBusy = true, error = null, message = "Deleting local data...") }
                repository.deleteAllLocalData()
            }.onSuccess {
                val openAi = repository.openAiSettings()
                _uiState.update {
                    it.copy(
                        screen = Screen.NewSwing,
                        selectedVideo = null,
                        status = null,
                        result = null,
                        history = emptyList(),
                        storageUsage = AnalysisStorageUsage(0L, 0, emptyMap()),
                        phaseFrames = emptyMap(),
                        openAiApiKey = openAi.apiKey,
                        openAiModel = openAi.model,
                        hasSavedOpenAiApiKey = openAi.hasApiKey,
                        replayState = ReplayState.NotLoaded,
                        isBusy = false,
                        message = "All local analyses and settings were deleted.",
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(isBusy = false, message = null, error = throwable.message ?: "Unable to delete local data.")
                }
            }
        }
    }

    fun openHistoryResult(runId: String) {
        viewModelScope.launch {
            runCatching {
                _uiState.update { it.copy(screen = Screen.History, isBusy = true, error = null, message = null) }
                repository.getAnalysis(runId)
            }.onSuccess { result ->
                showResult(result)
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        screen = Screen.History,
                        isBusy = false,
                        error = throwable.message ?: "Unable to open analysis.",
                    )
                }
            }
        }
    }

    fun openPhaseReview() {
        cancelAiGeneration()
        val result = _uiState.value.result ?: return
        _uiState.update {
            it.copy(
                screen = Screen.PhaseReview,
                phaseFrames = result.phases.associate { phase -> phase.name to phase.frameIndex.toString() },
                error = null,
                message = null,
            )
        }
    }

    fun updatePhaseFrame(phase: String, frame: String) {
        _uiState.update { state ->
            state.copy(phaseFrames = state.phaseFrames + (phase to frame))
        }
    }

    fun savePhaseFrames() {
        cancelAiGeneration()
        val result = _uiState.value.result ?: return
        viewModelScope.launch {
            runCatching {
                _uiState.update { it.copy(isBusy = true, error = null, message = "Saving phase timing...") }
                val frames = buildPhaseConfirmationPayload(_uiState.value.phaseFrames, phaseOrder)
                repository.confirmPhases(result.runId, frames)
            }.onSuccess { updated ->
                _uiState.update {
                    it.copy(
                        screen = Screen.Result,
                        result = updated,
                        phaseFrames = updated.phases.associate { phase -> phase.name to phase.frameIndex.toString() },
                        isBusy = false,
                        message = "Phase timing saved.",
                    )
                }
                viewModelScope.launch {
                    repository.invalidateRunArtifacts(updated.runId)
                    showResult(updated)
                }
            }.onFailure { throwable ->
                _uiState.update { it.copy(isBusy = false, error = throwable.message ?: "Unable to save phase timing.") }
            }
        }
    }

    fun generateAiAssessment() {
        startAiAssessment(force = false)
    }

    fun toggleCoachingGoal(goal: String) {
        if (_uiState.value.isGeneratingAi) return
        _uiState.update { it.copy(coachingFocus = it.coachingFocus.toggle(goal)) }
    }

    fun updateCoachingNote(note: String) {
        if (_uiState.value.isGeneratingAi) return
        _uiState.update {
            it.copy(coachingFocus = it.coachingFocus.copy(customNote = note).normalized())
        }
    }

    fun regenerateAiAssessment() {
        startAiAssessment(force = true)
    }

    fun cancelAiAssessment() {
        if (!_uiState.value.isGeneratingAi) return
        cancelAiGeneration()
        _uiState.update { it.copy(message = "AI assessment cancelled.", error = null) }
    }

    private fun startAiAssessment(force: Boolean) {
        val result = _uiState.value.result ?: return
        if (_uiState.value.isGeneratingAi) return
        if (!_uiState.value.hasSavedOpenAiApiKey) {
            showSettings()
            _uiState.update {
                it.copy(message = "Enter an OpenAI API key to generate an AI assessment.")
            }
            return
        }
        val runId = result.runId
        val action = if (force) "Regenerating" else "Generating"
        val completedAction = if (force) "regenerated" else "generated"
        aiGenerationJob = viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        isGeneratingAi = true,
                        aiAssessmentStage = AiAssessmentStage.PREPARING_IMAGES,
                        aiAssessmentDraft = null,
                        error = null,
                        message = "$action AI assessment...",
                    )
                }
                val focus = _uiState.value.coachingFocus.normalized()
                val updated = repository.createLlmAssessment(
                    runId = runId,
                    coachingFocus = focus,
                    force = force,
                    onProgress = { stage ->
                        _uiState.update { state ->
                            if (state.result?.runId == runId && state.isGeneratingAi) {
                                state.copy(aiAssessmentStage = stage)
                            } else {
                                state
                            }
                        }
                    },
                    onDraft = { draft ->
                        _uiState.update { state ->
                            if (state.result?.runId == runId && state.isGeneratingAi) {
                                state.copy(
                                    aiAssessmentStage = AiAssessmentStage.WRITING_ADVICE,
                                    aiAssessmentDraft = draft,
                                )
                            } else {
                                state
                            }
                        }
                    },
                )
                _uiState.update {
                    if (it.result?.runId == runId) {
                        it.copy(
                            result = updated,
                            isGeneratingAi = false,
                            aiAssessmentStage = null,
                            aiAssessmentDraft = null,
                            message = "AI assessment $completedAction.",
                            error = null,
                        )
                    } else {
                        it
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _uiState.update {
                    if (it.result?.runId == runId) {
                        it.copy(
                            isGeneratingAi = false,
                            aiAssessmentStage = null,
                            aiAssessmentDraft = null,
                            error = openAiErrorMessage(throwable),
                            message = null,
                        )
                    } else {
                        it
                    }
                }
            } finally {
                if (aiGenerationJob == kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    aiGenerationJob = null
                }
            }
        }
    }

    private fun cancelAiGeneration() {
        aiGenerationJob?.cancel()
        aiGenerationJob = null
        _uiState.update {
            if (it.isGeneratingAi || it.aiAssessmentDraft != null) {
                it.copy(isGeneratingAi = false, aiAssessmentStage = null, aiAssessmentDraft = null)
            } else {
                it
            }
        }
    }

    private fun resumePendingAnalysis() {
        pollingJob = viewModelScope.launch {
            val pending = runCatching { repository.latestPendingAnalysis() }.getOrNull() ?: return@launch
            _uiState.update {
                it.copy(
                    screen = Screen.Processing,
                    status = pending,
                    isBusy = true,
                    message = pending.message,
                    error = null,
                )
            }
            analysisWork.enqueue(pending.runId)
            try {
                val result = repository.pollUntilComplete(pending.runId) { status ->
                    _uiState.update { it.copy(status = status, message = status.message) }
                }
                showResult(result)
            } catch (cancelled: CancellationException) {
                if (_uiState.value.status?.runId == pending.runId) {
                    _uiState.update {
                        it.copy(screen = Screen.NewSwing, status = null, isBusy = false, message = "Analysis cancelled.")
                    }
                }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        screen = Screen.NewSwing,
                        status = null,
                        isBusy = false,
                        error = throwable.message ?: "Analysis failed.",
                    )
                }
            }
        }
    }

    fun artifactUrl(path: String?): String = repository.artifactUrl(path)

    fun retryReplayDownload() {
        val result = _uiState.value.result ?: return
        resolveReplay(result, forceDownload = true)
    }

    fun clearDownloadedReplays() {
        viewModelScope.launch {
            runCatching {
                _uiState.update { it.copy(isBusy = true, error = null, message = null) }
                repository.clearDownloadedReplays(currentReplayUri())
            }.onSuccess {
                _uiState.update { state ->
                    val current = state.replayState
                    state.copy(
                        isBusy = false,
                        replayState = if (current is ReplayState.Ready) ReplayState.NotLoaded else current,
                        message = "Downloaded replays cleared.",
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        error = throwable.message ?: "Unable to clear downloaded replays.",
                    )
                }
            }
        }
    }

    private fun resolveReplay(result: AnalysisResultResponse, forceDownload: Boolean = false) {
        val artifactPath = result.artifactUrls[ArtifactCache.ANNOTATED_VIDEO]
        if (artifactPath.isNullOrBlank()) {
            _uiState.update { it.copy(replayState = ReplayState.NotLoaded) }
            return
        }
        replayJob?.cancel()
        replayJob = viewModelScope.launch {
            runCatching {
                if (!forceDownload) {
                    repository.cachedArtifact(
                        runId = result.runId,
                        artifactName = ArtifactCache.ANNOTATED_VIDEO,
                        artifactPath = artifactPath,
                    )?.let { return@runCatching it }
                }
                _uiState.update { it.copy(replayState = ReplayState.Downloading) }
                repository.downloadArtifact(
                    runId = result.runId,
                    artifactName = ArtifactCache.ANNOTATED_VIDEO,
                    artifactPath = artifactPath,
                    protectedUri = currentReplayUri(),
                )
            }.onSuccess { uri ->
                _uiState.update { state ->
                    if (state.result?.runId == result.runId) {
                        state.copy(replayState = ReplayState.Ready(uri))
                    } else {
                        state
                    }
                }
            }.onFailure { throwable ->
                _uiState.update { state ->
                    if (state.result?.runId == result.runId) {
                        state.copy(
                            replayState = ReplayState.Failed(
                                throwable.message ?: "Unable to download replay.",
                            ),
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun currentReplayUri(): String? =
        (_uiState.value.replayState as? ReplayState.Ready)?.uri
}
