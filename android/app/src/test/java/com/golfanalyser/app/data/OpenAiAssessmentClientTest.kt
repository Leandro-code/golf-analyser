package com.golfanalyser.app.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiAssessmentClientTest {
    @Test
    fun cancellationClosesAnInFlightOpenAiCall() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setSocketPolicy(SocketPolicy.NO_RESPONSE),
            )
            val client = OpenAiAssessmentClient(
                httpClient = okhttp3.OkHttpClient(),
                endpoint = server.url("/v1/responses"),
            )
            val generation = launch(Dispatchers.Default) {
                client.generate(
                    settings = OpenAiSettings("test-key"),
                    payload = buildJsonObject { put("context", "test") },
                    evidenceFrames = emptyList(),
                )
            }
            assertTrue(server.takeRequest(10, TimeUnit.SECONDS) != null)

            generation.cancelAndJoin()

            assertTrue(generation.isCancelled)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun streamsReadableDraftsThenReturnsStrictFinalContent() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val content = validContentJson()
            val events = buildString {
                content.chunked(19).forEach { chunk ->
                    append(sseEvent("response.output_text.delta", "delta" to chunk))
                }
                append(sseEvent("response.output_text.done", "text" to content))
                append(sseEvent("response.completed"))
            }
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(events),
            )
            val drafts = mutableListOf<LlmContentDraft>()
            val progressStages = mutableListOf<AiAssessmentStage>()
            val client = OpenAiAssessmentClient(
                httpClient = okhttp3.OkHttpClient(),
                endpoint = server.url("/v1/responses"),
            )

            val result = client.generate(
                settings = OpenAiSettings("test-key", "test-model"),
                payload = buildJsonObject {
                    put("context", "test")
                    put("coaching_focus", buildJsonObject {
                        put("goals", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("reduce_fade_or_slice")) })
                        put("custom_note", "My usual miss is a fade.")
                    })
                },
                evidenceFrames = listOf(
                    ExportedEvidenceFrame(
                        frame = SubmittedEvidenceFrameDto(
                            frameId = "frame_000010",
                            frameIndex = 10,
                            timestampSeconds = 1.0,
                            phaseRelations = listOf("Top (P4) anchor"),
                            imageFile = "frame_000010.jpg",
                        ),
                        dataUrl = "data:image/jpeg;base64,/9j/2Q==",
                    ),
                ),
                onProgress = progressStages::add,
                onDraft = drafts::add,
            )

            assertEquals("A grounded overview.", result.overview)
            assertEquals("Sequence", result.priorities.single().title)
            assertTrue(drafts.isNotEmpty())
            assertEquals("A grounded overview.", drafts.last().overview)
            assertEquals(
                listOf(AiAssessmentStage.ANALYSING_SWING, AiAssessmentStage.WRITING_ADVICE),
                progressStages,
            )
            val request = server.takeRequest()
            assertEquals("text/event-stream", request.getHeader("Accept"))
            val requestBody = request.body.readUtf8()
            assertTrue(requestBody.contains("\"stream\":true"))
            assertTrue(requestBody.contains("\"max_output_tokens\":3500"))
            assertTrue(requestBody.contains("\"reasoning\":{\"effort\":\"medium\"}"))
            assertTrue(requestBody.contains("\"verbosity\":\"low\""))
            assertTrue(requestBody.contains("\"detail\":\"high\""))
            assertTrue(requestBody.contains("golfer-provided coaching_focus"))
            assertTrue(requestBody.contains("never include IDs"))
            assertFalse(requestBody.contains("missing clubface/path data in golfer-facing prose"))
            assertTrue(requestBody.contains("reduce_fade_or_slice"))
            assertTrue(requestBody.contains("My usual miss is a fade."))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsAStreamThatEndsWithoutCompletion() {
        val accumulator = ResponsesStreamAccumulator(onDraft = {})
        accumulator.acceptEvent(sseJson("response.output_text.delta", "delta" to "{}"))

        val error = runCatching { accumulator.finishedOutput() }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("before completion"))
    }

    @Test
    fun surfacesStreamingApiErrors() {
        val accumulator = ResponsesStreamAccumulator(onDraft = {})
        val event = """{"type":"error","error":{"message":"rate limited"}}"""

        val error = runCatching { accumulator.acceptEvent(event) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("rate limited"))
    }

    @Test
    fun refusesToTreatPartialRefusalTextAsAnAssessment() {
        val accumulator = ResponsesStreamAccumulator(onDraft = {})
        accumulator.acceptEvent(sseJson("response.refusal.delta", "delta" to "Unable to assess"))
        accumulator.acceptEvent(sseJson("response.completed"))

        val error = runCatching { accumulator.finishedOutput() }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("Unable to assess"))
    }

    private fun sseEvent(type: String, value: Pair<String, String>? = null): String =
        "data: ${sseJson(type, value)}\n\n"

    private fun sseJson(type: String, value: Pair<String, String>? = null): String =
        buildJsonObject {
            put("type", type)
            value?.let { put(it.first, it.second) }
        }.toString()

    private fun validContentJson(): String = """{
        "overview":"A grounded overview.",
        "strengths":["Balanced finish"],
        "observations":[],
        "priorities":[{
            "title":"Sequence",
            "rationale":"The supplied frames support this priority.",
            "practice_cue":"Turn, then swing.",
            "explanation":"Keep the movement ordered.",
            "drills":["Pause drill"],
            "practice_plan":["Five rehearsals"],
            "supporting_frame_ids":[],
            "related_metric_keys":[],
            "confidence":0.8,
            "support_type":"ai_generated"
        }],
        "limitations":["Single camera view"]
    }""".trimIndent()
}
