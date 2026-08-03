package com.golfanalyser.app.ui

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.golfanalyser.app.data.AnalysisResultResponse
import com.golfanalyser.app.data.AssessmentFindingDto
import com.golfanalyser.app.data.ContextPayload
import com.golfanalyser.app.analysis.LandmarkFrame
import com.golfanalyser.app.analysis.LandmarkPoint
import com.golfanalyser.app.data.LlmAssessmentDto
import com.golfanalyser.app.data.LlmContentDraft
import com.golfanalyser.app.data.MetricDto
import com.golfanalyser.app.data.SwingPhaseDto
import com.golfanalyser.app.data.confidenceLabel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

@Composable
fun GolfAnalyserApp(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF245C4F),
            secondary = Color(0xFF4C635A),
            surface = Color(0xFFFAFAF5),
            background = Color(0xFFFAFAF5),
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (state.screen) {
                Screen.NewSwing -> NewSwingScreen(state, viewModel)
                Screen.Processing -> ProcessingScreen(state)
                Screen.Result -> ResultScreen(state, viewModel)
                Screen.History -> HistoryScreen(state, viewModel)
                Screen.PhaseReview -> PhaseReviewScreen(state, viewModel)
                Screen.Settings -> SettingsScreen(state, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    title: String,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { actions() },
            )
        },
        content = content,
    )
}

@Composable
private fun NewSwingScreen(state: AppUiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.selectVideo(uri)
        }
    }
    AppScaffold(
        title = "New swing",
        actions = {
            TextButton(onClick = viewModel::showSettings) { Text("Settings") }
            TextButton(onClick = viewModel::loadHistory) { Text("History") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Text(
                    "AI Swing Review",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Import a full swing, add capture details, then generate an AI assessment from measured pose evidence.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            item {
                Button(
                    onClick = { picker.launch(arrayOf("video/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.selectedVideo == null) "Choose swing video" else "Change selected video")
                }
                if (state.selectedVideo != null) {
                    Text("Video selected", modifier = Modifier.padding(top = 8.dp))
                }
            }
            item {
                ContextControls(state.context, viewModel::updateContext)
            }
            item {
                if (state.error != null) ErrorBanner(state.error)
                Button(
                    onClick = viewModel::submitAnalysis,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Preparing...")
                    } else {
                        Text("Analyse swing")
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextControls(context: ContextPayload, onChange: (ContextPayload) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Capture details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ChipGroup(
            label = "Handedness",
            value = context.handedness,
            options = listOf("right", "left"),
            onChange = { onChange(context.copy(handedness = it)) },
        )
        ChipGroup(
            label = "Camera",
            value = context.cameraView,
            options = listOf("face_on", "down_the_line"),
            onChange = { onChange(context.copy(cameraView = it)) },
        )
        ChipGroup(
            label = "Club",
            value = context.clubFamily,
            options = listOf("driver", "wood_or_hybrid", "iron", "wedge"),
            onChange = { onChange(context.copy(clubFamily = it)) },
        )
    }
}

@Composable
private fun ChipGroup(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { option ->
                FilterChip(
                    selected = option == value,
                    onClick = { onChange(option) },
                    label = { Text(option.readable()) },
                )
            }
        }
    }
}

@Composable
private fun ProcessingScreen(state: AppUiState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text("${((state.status?.progress ?: 0.0) * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium)
            LinearProgressIndicator(
                progress = { (state.status?.progress ?: 0.0).toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(state.status?.message ?: state.message ?: "Processing swing")
        }
    }
}

@Composable
private fun HistoryScreen(state: AppUiState, viewModel: AppViewModel) {
    AppScaffold(
        title = "Saved analyses",
        actions = {
            TextButton(onClick = viewModel::showSettings) { Text("Settings") }
            TextButton(onClick = viewModel::clearDownloadedReplays) { Text("Clear replays") }
            TextButton(onClick = viewModel::showNewSwing) { Text("New") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.isBusy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            if (state.error != null) item { ErrorBanner(state.error) }
            items(state.history) { result ->
                Card(onClick = { viewModel.openHistoryResult(result.runId) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(result.runId, fontWeight = FontWeight.Bold)
                        Text(result.context?.let { "${it.cameraView.readable()} / ${it.clubFamily.readable()}" } ?: "Legacy run")
                        if (result.llmAssessmentCurrent) Text("AI assessment ready", color = MaterialTheme.colorScheme.primary)
                        if (result.llmAssessmentStale) Text("AI assessment is stale", color = Color(0xFF8A3B12))
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultScreen(state: AppUiState, viewModel: AppViewModel) {
    val result = state.result ?: return
    AppScaffold(
        title = "Swing analysis",
        actions = {
            TextButton(onClick = viewModel::showSettings) { Text("Settings") }
            TextButton(onClick = viewModel::loadHistory) { Text("History") }
            TextButton(onClick = viewModel::showNewSwing) { Text("New") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                ReplayPanel(state.replayState, result, viewModel::retryReplayDownload)
            }
            item {
                ResultHeader(result)
            }
            if (state.error != null) item { ErrorBanner(state.error) }
            if (state.message != null) item { InfoBanner(state.message) }
            item {
                AiAssessmentCard(result, state.isGeneratingAi, state.aiAssessmentDraft, viewModel)
            }
            item {
                ActionRow(
                    onPhaseReview = viewModel::openPhaseReview,
                    onGenerateAi = {
                        if (result.llmAssessmentCurrent) {
                            viewModel.regenerateAiAssessment()
                        } else {
                            viewModel.generateAiAssessment()
                        }
                    },
                    aiButtonText = if (result.llmAssessmentCurrent) "Regenerate AI" else "AI assessment",
                    aiEnabled = result.llmAssessmentEligibilityIssue == null && !state.isGeneratingAi,
                )
            }
            item {
                MeasurementsCard(result.metricsSummary)
            }
            item {
                PhaseList(result.phases)
            }
            result.assessment?.findings?.takeIf { it.isNotEmpty() }?.let { findings ->
                item { ReferenceChecks(findings) }
            }
        }
    }
}

@Composable
private fun ReplayPanel(replayState: ReplayState, result: AnalysisResultResponse, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (replayState) {
            ReplayState.NotLoaded -> {
                Button(onClick = onRetry) {
                    Text("Download replay")
                }
            }
            ReplayState.Downloading -> {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text("Downloading replay...", color = Color.White)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is ReplayState.Failed -> {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(replayState.message, color = Color.White)
                    Button(onClick = onRetry) {
                        Text("Retry download")
                    }
                }
            }
            is ReplayState.Ready -> VideoPlayer(replayState.uri, result)
        }
    }
}

@Composable
private fun ResultHeader(result: AnalysisResultResponse) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Swing analysis", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        result.context?.let {
            Text("${it.cameraView.readable()} / ${it.clubFamily.readable()} / ${it.handedness} handed")
        }
        val phaseIssues = result.qualityFlags["phase_quality_issues"].toString()
        if (phaseIssues != "null" && phaseIssues != "[]") {
            WarningBanner("Swing markers need review before coaching can be relied on.")
        }
    }
}

@Composable
private fun VideoPlayer(url: String, result: AnalysisResultResponse) {
    val context = LocalContext.current
    var videoAspectRatio by remember(url) { mutableStateOf(9f / 16f) }
    var currentPositionMs by remember(url) { mutableStateOf(0L) }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            currentPositionMs = player.currentPosition
            delay(80)
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val pixelRatio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                    videoAspectRatio = (videoSize.width * pixelRatio) / videoSize.height
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(videoAspectRatio)
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = {
                it.player = player
                it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
        )
        PoseOverlay(
            result = result,
            currentPositionMs = currentPositionMs,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PoseOverlay(
    result: AnalysisResultResponse,
    currentPositionMs: Long,
    modifier: Modifier = Modifier,
) {
    val fps = result.metadata?.get("fps")?.let { (it as? JsonPrimitive)?.doubleOrNull } ?: 30.0
    val frameIndex = ((currentPositionMs / 1000.0) * fps).toInt()
    val frame = result.landmarks.minByOrNull { kotlin.math.abs(it.frameIndex - frameIndex) }
        ?.takeIf { it.poseDetected }
    val phase = result.phases.minByOrNull { kotlin.math.abs(it.frameIndex - frameIndex) }
    Canvas(modifier = modifier) {
        if (frame == null) return@Canvas
        val points = frame.landmarks.associateBy { it.name }
        SKELETON_CONNECTIONS.forEach { (first, second) ->
            val a = points[first]
            val b = points[second]
            if (a != null && b != null) {
                drawLine(
                    color = Color(0xFFE7F5E8),
                    start = a.offset(size.width, size.height),
                    end = b.offset(size.width, size.height),
                    strokeWidth = 4f,
                )
            }
        }
        points.values.forEach { point ->
            drawCircle(
                color = Color(0xFF75D0A2),
                radius = 5f,
                center = point.offset(size.width, size.height),
                style = Stroke(width = 2f),
            )
        }
        phase?.takeIf { kotlin.math.abs(it.frameIndex - frame.frameIndex) <= maxOf(1, fps.toInt() / 12) }?.let {
            drawCircle(
                color = Color(0xFFFFD166),
                radius = 14f,
                center = points["left_wrist"]?.offset(size.width, size.height)
                    ?: points["right_wrist"]?.offset(size.width, size.height)
                    ?: return@let,
                style = Stroke(width = 4f),
            )
        }
    }
}

private fun LandmarkPoint.offset(width: Float, height: Float) =
    androidx.compose.ui.geometry.Offset((x * width).toFloat(), (y * height).toFloat())

private val SKELETON_CONNECTIONS = listOf(
    "left_shoulder" to "right_shoulder",
    "left_shoulder" to "left_elbow",
    "left_elbow" to "left_wrist",
    "right_shoulder" to "right_elbow",
    "right_elbow" to "right_wrist",
    "left_shoulder" to "left_hip",
    "right_shoulder" to "right_hip",
    "left_hip" to "right_hip",
    "left_hip" to "left_knee",
    "left_knee" to "left_ankle",
    "right_hip" to "right_knee",
    "right_knee" to "right_ankle",
)

@Composable
private fun AiAssessmentCard(
    result: AnalysisResultResponse,
    isGenerating: Boolean,
    draft: LlmContentDraft?,
    viewModel: AppViewModel,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AI Swing Assessment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val assessment = if (result.llmAssessmentCurrent) result.llmAssessment else null
            when {
                isGenerating -> AiAssessmentLoading(draft, assessment)
                result.llmAssessmentStale -> WarningBanner("Saved AI assessment is stale. Regenerate after reviewing timing.")
                result.llmAssessmentEligibilityIssue != null -> {
                    WarningBanner(result.llmAssessmentEligibilityIssue)
                    Button(onClick = viewModel::openPhaseReview, modifier = Modifier.fillMaxWidth()) {
                        Text("Review phase timing")
                    }
                }
                assessment == null -> {
                    Text("Generate a model-written report from selected stills, local pose measurements, and quality checks.")
                    Button(
                        onClick = viewModel::generateAiAssessment,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Generate AI assessment")
                    }
                }
                else -> LlmAssessmentContent(assessment)
            }
        }
    }
}

@Composable
private fun AiAssessmentLoading(draft: LlmContentDraft?, previousAssessment: LlmAssessmentDto?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .width(28.dp)
                    .height(28.dp),
                strokeWidth = 3.dp,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (previousAssessment == null) "Generating AI assessment" else "Regenerating AI assessment",
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (draft?.hasVisibleContent == true) {
                        "The report below is still being generated."
                    } else {
                        "Preparing swing evidence and waiting for the model response."
                    },
                )
            }
        }
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (draft?.hasVisibleContent == true) {
            LlmAssessmentDraftContent(draft)
        } else if (previousAssessment != null) {
            Text("The current assessment will remain saved until its replacement is ready.")
            LlmAssessmentContent(previousAssessment)
        } else {
            Text("This can take about a minute for the first request.")
        }
    }
}

@Composable
private fun LlmAssessmentDraftContent(draft: LlmContentDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (draft.overview.isNotBlank()) Text(draft.overview)
        draft.priorities.forEachIndexed { index, priority ->
            if (
                priority.title.isNotBlank() || priority.rationale.isNotBlank() ||
                priority.practiceCue.isNotBlank() || priority.drills.isNotEmpty() ||
                priority.practicePlan.isNotEmpty()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (priority.title.isNotBlank()) {
                        Text("${index + 1}. ${priority.title}", fontWeight = FontWeight.Bold)
                    }
                    if (priority.rationale.isNotBlank()) Text(priority.rationale)
                    if (priority.practiceCue.isNotBlank()) InfoBanner("Practice cue: ${priority.practiceCue}")
                    AssessmentBulletList("Drills", priority.drills)
                    AssessmentBulletList("Plan", priority.practicePlan)
                }
            }
        }
        AssessmentBulletList("Strengths", draft.strengths)
        AssessmentBulletList("Limitations", draft.limitations)
    }
}

@Composable
private fun LlmAssessmentContent(assessment: LlmAssessmentDto) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(assessment.content.overview)
        assessment.content.priorities.forEachIndexed { index, priority ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${index + 1}. ${priority.title} (${confidenceLabel(priority.confidence)})", fontWeight = FontWeight.Bold)
                Text(priority.rationale)
                InfoBanner("Practice cue: ${priority.practiceCue}")
                AssessmentBulletList("Drills", priority.drills)
                AssessmentBulletList("Plan", priority.practicePlan)
            }
        }
        AssessmentBulletList("Strengths", assessment.content.strengths)
        AssessmentBulletList("Limitations", assessment.content.limitations)
    }
}

@Composable
private fun AssessmentBulletList(label: String, items: List<String>) {
    val visibleItems = items.filter(String::isNotBlank)
    if (visibleItems.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontWeight = FontWeight.Bold)
            visibleItems.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text("\u2022")
                    Spacer(Modifier.width(8.dp))
                    Text(item, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    onPhaseReview: () -> Unit,
    onGenerateAi: () -> Unit,
    aiButtonText: String,
    aiEnabled: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onPhaseReview, modifier = Modifier.weight(1f)) {
            Text("Review timing")
        }
        Button(onClick = onGenerateAi, enabled = aiEnabled, modifier = Modifier.weight(1f)) {
            Text(aiButtonText)
        }
    }
}

@Composable
private fun MeasurementsCard(metrics: Map<String, MetricDto>) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Measured pose data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            metrics.values.take(8).forEach { metric ->
                Text("${metric.name}: ${metric.value.displayValue()} ${metric.unit.orEmpty()}")
            }
        }
    }
}

@Composable
private fun PhaseList(phases: List<SwingPhaseDto>) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Swing phases", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            phases.forEach { phase ->
                Text("${phase.name}: frame ${phase.frameIndex} at ${"%.2f".format(phase.timestampSeconds)}s")
            }
        }
    }
}

@Composable
private fun ReferenceChecks(findings: List<AssessmentFindingDto>) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Reference checks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            findings.forEach { finding ->
                Text("${finding.reference.name}: ${finding.status.readable()}", fontWeight = FontWeight.SemiBold)
                finding.note?.let { Text(it) }
            }
        }
    }
}

@Composable
private fun PhaseReviewScreen(state: AppUiState, viewModel: AppViewModel) {
    AppScaffold(
        title = "Review timing",
        actions = { TextButton(onClick = { state.result?.let(viewModel::showResult) }) { Text("Cancel") } },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Confirm the frame for each swing phase. Saving regenerates metrics, keyframes, replay, and coaching eligibility.")
            phaseOrder.forEach { phase ->
                OutlinedTextField(
                    value = state.phaseFrames[phase].orEmpty(),
                    onValueChange = { viewModel.updatePhaseFrame(phase, it) },
                    label = { Text(phase) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.error != null) ErrorBanner(state.error)
            Button(
                onClick = viewModel::savePhaseFrames,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isBusy) "Saving..." else "Save phase timing")
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, viewModel: AppViewModel) {
    AppScaffold(
        title = "Settings",
        actions = { TextButton(onClick = viewModel::showNewSwing) { Text("Done") } },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("AI provider", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = state.openAiApiKey,
                onValueChange = viewModel::updateOpenAiApiKey,
                label = { Text("OpenAI API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.openAiModel,
                onValueChange = viewModel::updateOpenAiModel,
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.message != null) InfoBanner(state.message)
            if (state.error != null) ErrorBanner(state.error)
            Button(onClick = viewModel::saveSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Save settings")
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) = Banner(message, Color(0xFFF8E8E2), Color(0xFF5D2C20))

@Composable
private fun WarningBanner(message: String) = Banner(message, Color(0xFFFFF3D8), Color(0xFF70410E))

@Composable
private fun InfoBanner(message: String) = Banner(message, Color(0xFFE3F0E9), Color(0xFF1D5941))

@Composable
private fun Banner(message: String, background: Color, foreground: Color) {
    Text(
        text = message,
        color = foreground,
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

private fun String.readable(): String = replace('_', ' ').replaceFirstChar { it.titlecase() }

private fun kotlinx.serialization.json.JsonElement?.displayValue(): String {
    val primitive = this as? JsonPrimitive ?: return toString()
    return primitive.contentOrNull
        ?: primitive.intOrNull?.toString()
        ?: primitive.doubleOrNull?.toString()
        ?: primitive.booleanOrNull?.toString()
        ?: primitive.toString()
}
