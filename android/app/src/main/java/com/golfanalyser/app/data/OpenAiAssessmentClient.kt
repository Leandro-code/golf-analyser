package com.golfanalyser.app.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

class OpenAiAssessmentClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(210, TimeUnit.SECONDS)
        .build(),
    private val endpoint: HttpUrl = "https://api.openai.com/v1/responses".toHttpUrl(),
) {
    suspend fun generate(
        settings: OpenAiSettings,
        payload: JsonObject,
        evidenceFrames: List<ExportedEvidenceFrame>,
        onDraft: (LlmContentDraft) -> Unit = {},
    ): LlmContentDto {
        val callingJob = currentCoroutineContext().job
        try {
            return runInterruptible(Dispatchers.IO) {
        val body = requestPayload(settings.model, payload, evidenceFrames).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()
        val call = httpClient.newCall(request)
        val cancellationHandle = callingJob.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    val error = parseOpenAiError(responseBody)
                    throw OpenAiApiException(
                        statusCode = response.code,
                        errorCode = error?.get("code")?.jsonPrimitive?.contentOrNull,
                        apiMessage = error?.get("message")?.jsonPrimitive?.contentOrNull,
                    )
                }
                val responseBody = response.body
                    ?: throw IOException("OpenAI swing assessment response was empty.")
                val accumulator = ResponsesStreamAccumulator(onDraft)
                val dataLines = mutableListOf<String>()
                responseBody.source().use { source ->
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) {
                            accumulator.acceptEvent(dataLines.joinToString("\n"))
                            dataLines.clear()
                        } else if (line.startsWith("data:")) {
                            dataLines += line.removePrefix("data:").removePrefix(" ")
                        }
                    }
                }
                if (dataLines.isNotEmpty()) accumulator.acceptEvent(dataLines.joinToString("\n"))
                val outputText = accumulator.finishedOutput()
                return@runInterruptible try {
                    Json.decodeFromString<LlmContentDto>(outputText)
                } catch (exc: Exception) {
                    throw IOException("OpenAI returned an invalid structured swing assessment: ${exc.message}", exc)
                }
            }
        } finally {
            cancellationHandle.dispose()
        }
            }
        } catch (io: IOException) {
            if (!callingJob.isActive) {
                throw CancellationException("OpenAI assessment cancelled.").also { it.initCause(io) }
            }
            throw io
        }
    }

    private fun requestPayload(
        model: String,
        evidencePayload: JsonObject,
        evidenceFrames: List<ExportedEvidenceFrame>,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("instructions", INSTRUCTIONS)
        put("store", false)
        put("stream", true)
        putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("name", "golf_swing_assessment")
                put("strict", true)
                put("schema", contentSchema())
            }
        }
        putJsonArray("input") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "input_text")
                        put(
                            "text",
                            "Produce the primary swing assessment from the supplied evidence packet. " +
                                "Use only frame IDs supplied below when citing visual support.\n\n" +
                                evidencePayload.toString(),
                        )
                    }
                    evidenceFrames.forEach { frame ->
                        addJsonObject {
                            put("type", "input_text")
                            put(
                                "text",
                                "Evidence frame ${frame.frame.frameId}: video frame ${frame.frame.frameIndex}, " +
                                    "${"%.3f".format(frame.frame.timestampSeconds)}s; relations: " +
                                    frame.frame.phaseRelations.joinToString(", ") + ".",
                            )
                        }
                        addJsonObject {
                            put("type", "input_image")
                            put("image_url", frame.dataUrl)
                            put("detail", "high")
                        }
                    }
                }
            }
        }
    }

    private fun parseOpenAiError(body: String): JsonObject? = runCatching {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
    }.getOrNull()

    private fun contentSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") {
            add("overview")
            add("strengths")
            add("observations")
            add("priorities")
            add("limitations")
        }
        putJsonObject("properties") {
            putJsonObject("overview") { put("type", "string") }
            put("strengths", stringArraySchema())
            putJsonObject("observations") {
                put("type", "array")
                put("items", observationSchema())
            }
            putJsonObject("priorities") {
                put("type", "array")
                put("maxItems", 3)
                put("items", prioritySchema())
            }
            put("limitations", stringArraySchema())
        }
    }

    private fun observationSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") {
            add("title")
            add("observation")
            add("supporting_frame_ids")
            add("related_metric_keys")
            add("confidence")
        }
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "string") }
            putJsonObject("observation") { put("type", "string") }
            put("supporting_frame_ids", stringArraySchema())
            put("related_metric_keys", stringArraySchema())
            put("confidence", confidenceSchema())
        }
    }

    private fun prioritySchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") {
            add("title")
            add("rationale")
            add("practice_cue")
            add("explanation")
            add("drills")
            add("practice_plan")
            add("supporting_frame_ids")
            add("related_metric_keys")
            add("confidence")
            add("support_type")
        }
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "string") }
            putJsonObject("rationale") { put("type", "string") }
            putJsonObject("practice_cue") { put("type", "string") }
            putJsonObject("explanation") {
                putJsonArray("type") {
                    add("string")
                    add("null")
                }
            }
            put("drills", stringArraySchema(maxItems = 5))
            put("practice_plan", stringArraySchema(maxItems = 5))
            put("supporting_frame_ids", stringArraySchema())
            put("related_metric_keys", stringArraySchema())
            put("confidence", confidenceSchema())
            putJsonObject("support_type") {
                put("type", "string")
                putJsonArray("enum") { add("ai_generated") }
            }
        }
    }

    private fun stringArraySchema(maxItems: Int? = null): JsonObject = buildJsonObject {
        put("type", "array")
        if (maxItems != null) put("maxItems", maxItems)
        putJsonObject("items") { put("type", "string") }
    }

    private fun confidenceSchema(): JsonObject = buildJsonObject {
        put("type", "number")
        put("minimum", 0.0)
        put("maximum", 1.0)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObject(
        key: String,
        builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = put(key, buildJsonObject(builder))

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArray(
        key: String,
        builder: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit,
    ) = put(key, buildJsonArray(builder))

    private fun kotlinx.serialization.json.JsonArrayBuilder.add(value: String) =
        add(JsonPrimitive(value))

    private fun kotlinx.serialization.json.JsonArrayBuilder.addJsonObject(
        builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = add(buildJsonObject(builder))

    companion object {
        private val Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val INSTRUCTIONS = """
            You are the primary AI swing assessment writer for a golf swing review workbench.
            You receive still images selected from an ordered full-swing replay,
            capture context, deterministic 2D pose measurements, and local quality metadata.

            Ground every observation and recommendation in supplied frame IDs and/or metrics.
            Treat supplied measurements as facts; do not recalculate or contradict them from
            the images. Prioritise up to three useful practice actions. Do not compare against
            target ranges, hidden standards, or normative thresholds. Use support_type
            ai_generated for every priority because no source-backed reference ranges are
            provided.

            For each priority, include explanation, drills, and practice_plan fields that help
            the golfer understand what the priority means, rehearse it, and check progress.
            Keep drills and plan steps concrete, short, and grounded in the supplied evidence.
            Do not introduce claims that are unsupported by the supplied 2D pose frames,
            measurements, or quality metadata.

            Limit analysis to visible 2D pose observations. Do not claim club path, clubface
            angle, strike/contact quality, ball flight, distance, power, overall score,
            medical diagnosis, or comparison to a professional golfer. Treat shaft-parallel
            phase labels as body-pose timing proxies, not observed shaft measurements.
            Include limitations when images or 2D pose evidence cannot support a conclusion.
        """.trimIndent()
    }
}

class OpenAiApiException(
    val statusCode: Int,
    val errorCode: String?,
    val apiMessage: String?,
) : IOException("OpenAI request failed with HTTP $statusCode.")

fun openAiErrorMessage(throwable: Throwable): String {
    val cause = generateSequence(throwable) { it.cause }.firstOrNull {
        it is OpenAiApiException || it is UnknownHostException ||
            it is ConnectException || it is SocketTimeoutException
    } ?: throwable
    return when (cause) {
        is OpenAiApiException -> when {
            cause.statusCode == 401 || cause.statusCode == 403 ->
                "OpenAI rejected this API key. Check or replace it in Settings."
            cause.statusCode == 404 || cause.errorCode == "model_not_found" ->
                "The configured OpenAI assessment model is unavailable for this API key."
            cause.statusCode == 429 ->
                "OpenAI rate limit or quota reached. Wait and retry, or check API billing and limits."
            cause.statusCode == 408 ->
                "The OpenAI request timed out. Check your connection and retry."
            cause.statusCode >= 500 ->
                "OpenAI is temporarily unavailable. Please retry shortly."
            else -> "OpenAI could not process this assessment request (HTTP ${cause.statusCode})."
        }
        is SocketTimeoutException ->
            "The OpenAI request timed out. Check your connection and retry."
        is UnknownHostException, is ConnectException ->
            "No connection to OpenAI. Check internet access and retry."
        else -> throwable.message ?: "Unable to generate AI assessment."
    }
}

internal class ResponsesStreamAccumulator(
    private val onDraft: (LlmContentDraft) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val outputText = StringBuilder()
    private val refusalText = StringBuilder()
    private val draftParser = LlmDraftParser()
    private var completed = false
    private var lastDraft: LlmContentDraft? = null
    private var lastDraftAt = 0L

    fun acceptEvent(data: String) {
        if (data.isBlank() || data == "[DONE]") return
        val event = try {
            Json.parseToJsonElement(data).jsonObject
        } catch (exc: Exception) {
            throw IOException("OpenAI returned an invalid streaming event.", exc)
        }
        when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "response.output_text.delta" -> {
                outputText.append(event.stringValue("delta"))
                emitDraft(force = false)
            }
            "response.output_text.done" -> {
                event["text"]?.jsonPrimitive?.contentOrNull?.let { finalText ->
                    if (finalText.isNotEmpty()) {
                        outputText.clear()
                        outputText.append(finalText)
                    }
                }
                emitDraft(force = true)
            }
            "response.refusal.delta" -> refusalText.append(event.stringValue("delta"))
            "response.completed" -> completed = true
            "error", "response.error", "response.failed", "response.incomplete" -> {
                throw IOException(openAiEventError(event))
            }
        }
    }

    fun finishedOutput(): String {
        if (refusalText.isNotBlank()) {
            throw IOException("OpenAI refused the swing assessment request: $refusalText")
        }
        if (!completed) {
            throw IOException("OpenAI swing assessment stream ended before completion.")
        }
        if (outputText.isBlank()) {
            throw IOException("OpenAI did not return a structured swing assessment.")
        }
        emitDraft(force = true)
        return outputText.toString()
    }

    private fun emitDraft(force: Boolean) {
        val draft = draftParser.parse(outputText.toString())
        if (!draft.hasVisibleContent || draft == lastDraft) return
        val now = nanoTime()
        if (!force && lastDraft != null && now - lastDraftAt < DRAFT_INTERVAL_NANOS) return
        lastDraft = draft
        lastDraftAt = now
        onDraft(draft)
    }

    private fun JsonObject.stringValue(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("OpenAI streaming event is missing $key.")

    private fun openAiEventError(event: JsonObject): String {
        val error = event["error"] as? JsonObject
            ?: (event["response"] as? JsonObject)?.get("error") as? JsonObject
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull
        return "OpenAI swing assessment stream failed${message?.let { ": $it" }.orEmpty()}."
    }

    private companion object {
        const val DRAFT_INTERVAL_NANOS = 75_000_000L
    }
}

data class ExportedEvidenceFrame(
    val frame: SubmittedEvidenceFrameDto,
    val dataUrl: String,
)
