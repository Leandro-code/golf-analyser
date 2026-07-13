package com.golfanalyser.app.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiAssessmentClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(210, TimeUnit.SECONDS)
        .build(),
) {
    fun generate(
        settings: OpenAiSettings,
        payload: JsonObject,
        evidenceFrames: List<ExportedEvidenceFrame>,
    ): LlmContentDto {
        val body = requestPayload(settings.model, payload, evidenceFrames).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("OpenAI swing assessment request failed: HTTP ${response.code} $responseBody")
            }
            val text = extractOutputText(Json.parseToJsonElement(responseBody))
                ?: throw IOException("OpenAI did not return a structured swing assessment.")
            return Json.decodeFromString<LlmContentDto>(text)
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

    private fun extractOutputText(response: JsonElement): String? {
        val root = response.jsonObject
        root["output_text"]?.jsonPrimitive?.contentOrNull?.let { return it }
        val output = root["output"] as? JsonArray ?: return null
        output.forEach { item ->
            val content = item.jsonObject["content"] as? JsonArray ?: return@forEach
            content.forEach { contentItem ->
                val obj = contentItem.jsonObject
                obj["text"]?.jsonPrimitive?.contentOrNull?.let { return it }
            }
        }
        return null
    }

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

data class ExportedEvidenceFrame(
    val frame: SubmittedEvidenceFrameDto,
    val dataUrl: String,
)
