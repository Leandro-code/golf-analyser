package com.golfanalyser.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.golfanalyser.app.data.AnalysisRepository
import com.golfanalyser.app.data.AnalysisResultResponse
import com.golfanalyser.app.data.AnalysisStatusResponse
import com.golfanalyser.app.data.ArtifactCache
import com.golfanalyser.app.data.ContextPayload
import com.golfanalyser.app.data.OpenAiSettings
import com.golfanalyser.app.data.buildPhaseConfirmationPayload
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
    val phaseFrames: Map<String, String> = emptyMap(),
    val openAiApiKey: String = "",
    val openAiModel: String = "",
    val replayState: ReplayState = ReplayState.NotLoaded,
    val isBusy: Boolean = false,
    val isGeneratingAi: Boolean = false,
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
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState
    private var pollingJob: Job? = null
    private var replayJob: Job? = null

    init {
        val openAi = repository.openAiSettings()
        _uiState.update {
            it.copy(openAiApiKey = openAi.apiKey, openAiModel = openAi.model)
        }
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
        _uiState.update {
            it.copy(
                screen = Screen.NewSwing,
                status = null,
                result = null,
                phaseFrames = emptyMap(),
                replayState = ReplayState.NotLoaded,
                isBusy = false,
                isGeneratingAi = false,
                error = null,
                message = null,
            )
        }
    }

    fun showSettings() {
        val openAi = repository.openAiSettings()
        _uiState.update {
            it.copy(
                screen = Screen.Settings,
                openAiApiKey = openAi.apiKey,
                openAiModel = openAi.model,
                error = null,
                message = null,
            )
        }
    }

    fun updateOpenAiApiKey(value: String) {
        _uiState.update { it.copy(openAiApiKey = value) }
    }

    fun updateOpenAiModel(value: String) {
        _uiState.update { it.copy(openAiModel = value) }
    }

    fun saveSettings() {
        repository.saveOpenAiSettings(
            OpenAiSettings(
                apiKey = _uiState.value.openAiApiKey,
                model = _uiState.value.openAiModel,
            ),
        )
        _uiState.update { it.copy(message = "Settings saved.", error = null) }
    }

    fun showResult(result: AnalysisResultResponse) {
        replayJob?.cancel()
        _uiState.update {
            it.copy(
                screen = Screen.Result,
                result = result,
                phaseFrames = result.phases.associate { phase -> phase.name to phase.frameIndex.toString() },
                replayState = ReplayState.NotLoaded,
                isBusy = false,
                isGeneratingAi = false,
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
                _uiState.update { it.copy(isBusy = true, error = null, message = "Uploading video...") }
                val created = repository.createAnalysis(video, _uiState.value.context)
                _uiState.update {
                    it.copy(
                        screen = Screen.Processing,
                        status = created.status,
                        isBusy = false,
                        message = created.status.message,
                    )
                }
                repository.pollUntilComplete(created.runId) { status ->
                    _uiState.update {
                        it.copy(status = status, message = status.message, screen = Screen.Processing)
                    }
                }
            }.onSuccess { result ->
                showResult(result)
            }.onFailure { throwable ->
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

    fun loadHistory() {
        viewModelScope.launch {
            runCatching {
                _uiState.update { it.copy(screen = Screen.History, isBusy = true, error = null) }
                repository.listAnalyses()
            }.onSuccess { history ->
                _uiState.update { it.copy(history = history, isBusy = false) }
            }.onFailure { throwable ->
                _uiState.update { it.copy(isBusy = false, error = throwable.message ?: "Unable to load history.") }
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
        val result = _uiState.value.result ?: return
        if (_uiState.value.isGeneratingAi) return
        viewModelScope.launch {
            runCatching {
                _uiState.update { it.copy(isGeneratingAi = true, error = null, message = "Generating AI assessment...") }
                repository.createLlmAssessment(result.runId)
            }.onSuccess { updated ->
                _uiState.update {
                    it.copy(
                        result = updated,
                        isGeneratingAi = false,
                        message = "AI assessment generated.",
                        error = null,
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isGeneratingAi = false,
                        error = throwable.message ?: "Unable to generate AI assessment.",
                        message = null,
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
