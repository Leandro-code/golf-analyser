package com.golfanalyser.app.data

import android.content.ContentResolver
import android.net.Uri
import com.golfanalyser.app.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import kotlin.math.roundToInt

private val json = Json {
    ignoreUnknownKeys = true
}

class AnalysisRepository(
    private val contentResolver: ContentResolver,
    cacheDir: File,
    private val baseUrl: String = BuildConfig.DEFAULT_API_BASE_URL,
    apiToken: String = BuildConfig.API_TOKEN,
) {
    private val api: GolfAnalyserApi
    private val artifactCache = ArtifactCache(File(cacheDir, "artifacts"))
    private val client: OkHttpClient

    init {
        client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(210, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = if (apiToken.isNotBlank()) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $apiToken")
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                },
            )
            .build()
        api = Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GolfAnalyserApi::class.java)
    }

    suspend fun createAnalysis(videoUri: Uri, context: ContextPayload): AnalysisCreateResponse {
        val name = contentResolver.displayName(videoUri) ?: "swing.mp4"
        val body = ContentUriRequestBody(contentResolver, videoUri)
        val part = MultipartBody.Part.createFormData("video", name, body)
        return api.createAnalysis(
            video = part,
            handedness = context.handedness.formPart(),
            cameraView = context.cameraView.formPart(),
            clubFamily = context.clubFamily.formPart(),
            swingType = context.swingType.formPart(),
        )
    }

    suspend fun listAnalyses(): List<AnalysisResultResponse> = api.listAnalyses()

    suspend fun getAnalysis(runId: String): AnalysisResultResponse = api.getAnalysis(runId)

    suspend fun confirmPhases(runId: String, frameIndices: List<Int>): AnalysisResultResponse =
        api.confirmPhases(runId, PhaseConfirmationRequest(frameIndices))

    suspend fun createLlmAssessment(runId: String): AnalysisResultResponse =
        api.createLlmAssessment(runId)

    suspend fun cachedArtifact(
        runId: String,
        artifactName: String,
        artifactPath: String,
    ): String? = withContext(Dispatchers.IO) {
        artifactCache.cachedFile(runId, artifactName, artifactPath)?.toURI()?.toString()
    }

    suspend fun downloadArtifact(
        runId: String,
        artifactName: String,
        artifactPath: String,
        protectedUri: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val cached = artifactCache.cachedFile(runId, artifactName, artifactPath)
        if (cached != null) return@withContext cached.toURI().toString()

        val request = Request.Builder()
            .url(artifactUrl(artifactPath))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Unable to download replay: HTTP ${response.code}")
            }
            val body = response.body ?: error("Unable to download replay: empty response.")
            val protectedFile = protectedUri?.let { File(Uri.parse(it).path.orEmpty()) }
            artifactCache.write(
                runId = runId,
                artifactName = artifactName,
                artifactPath = artifactPath,
                input = body.byteStream(),
                protectedFile = protectedFile,
            ).toURI().toString()
        }
    }

    suspend fun clearDownloadedReplays(protectedUri: String? = null) = withContext(Dispatchers.IO) {
        val protectedFile = protectedUri?.let { File(Uri.parse(it).path.orEmpty()) }
        artifactCache.clearAll(protectedFile)
    }

    suspend fun invalidateRunArtifacts(runId: String, protectedUri: String? = null) = withContext(Dispatchers.IO) {
        val protectedFile = protectedUri?.let { File(Uri.parse(it).path.orEmpty()) }
        artifactCache.invalidateRun(runId, protectedFile)
    }

    suspend fun pollUntilComplete(
        runId: String,
        onStatus: (AnalysisStatusResponse) -> Unit,
    ): AnalysisResultResponse {
        while (true) {
            val status = api.getStatus(runId)
            onStatus(status)
            when (status.status) {
                "completed" -> return api.getAnalysis(runId)
                "failed" -> error(status.error ?: status.message)
                else -> delay(1_200)
            }
        }
    }

    fun artifactUrl(path: String?): String {
        if (path.isNullOrBlank()) return ""
        return baseUrl.trimEnd('/') + path
    }
}

fun buildPhaseConfirmationPayload(frameMap: Map<String, String>, phaseOrder: List<String>): List<Int> =
    phaseOrder.map { phase ->
        frameMap[phase]?.toIntOrNull()
            ?: error("Missing frame for $phase")
    }

fun confidenceLabel(confidence: Double): String = "${(confidence * 100).roundToInt()}%"

private fun String.formPart() = toRequestBody("text/plain".toMediaType())
