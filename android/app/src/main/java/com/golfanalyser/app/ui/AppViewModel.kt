package com.golfanalyser.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.golfanalyser.app.data.AnalysisRepository
import com.golfanalyser.app.data.AnalysisResultResponse
import com.golfanalyser.app.data.AnalysisStatusResponse
import com.golfanalyser.app.data.ContextPayload
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
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AnalysisRepository(application.contentResolver)
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState
    private var pollingJob: Job? = null

    fun selectVideo(uri: Uri) {
        _uiState.update { it.copy(selectedVideo = uri, error = null, message = null) }
    }

    fun updateContext(context: ContextPayload) {
        _uiState.update { it.copy(context = context) }
    }

    fun showNewSwing() {
        pollingJob?.cancel()
        _uiState.update {
            it.copy(
                screen = Screen.NewSwing,
                status = null,
                result = null,
                phaseFrames = emptyMap(),
                isBusy = false,
                error = null,
                message = null,
            )
        }
    }

    fun showResult(result: AnalysisResultResponse) {
        _uiState.update {
            it.copy(
                screen = Screen.Result,
                result = result,
                phaseFrames = result.phases.associate { phase -> phase.name to phase.frameIndex.toString() },
                isBusy = false,
                error = null,
                message = null,
            )
        }
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
            }.onFailure { throwable ->
                _uiState.update { it.copy(isBusy = false, error = throwable.message ?: "Unable to save phase timing.") }
            }
        }
    }

    fun generateAiAssessment() {
        val result = _uiState.value.result ?: return
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
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isGeneratingAi = false,
                        error = throwable.message ?: "Unable to generate AI assessment.",
                    )
                }
            }
        }
    }

    fun artifactUrl(path: String?): String = repository.artifactUrl(path)
}
