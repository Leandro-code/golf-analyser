package com.golfanalyser.app.data

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiErrorMessageTest {
    @Test
    fun `invalid key has a settings action`() {
        val error = OpenAiApiException(401, "invalid_api_key", "secret server detail")

        assertEquals(
            "OpenAI rejected this API key. Check or replace it in Settings.",
            openAiErrorMessage(error),
        )
    }

    @Test
    fun `model and rate errors are distinct`() {
        assertEquals(
            "The configured OpenAI assessment model is unavailable for this API key.",
            openAiErrorMessage(OpenAiApiException(404, "model_not_found", null)),
        )
        assertEquals(
            "OpenAI rate limit or quota reached. Wait and retry, or check API billing and limits.",
            openAiErrorMessage(OpenAiApiException(429, "rate_limit_exceeded", null)),
        )
    }

    @Test
    fun `connection and timeout errors are actionable`() {
        assertEquals(
            "No connection to OpenAI. Check internet access and retry.",
            openAiErrorMessage(UnknownHostException("api.openai.com")),
        )
        assertEquals(
            "The OpenAI request timed out. Check your connection and retry.",
            openAiErrorMessage(SocketTimeoutException("timeout")),
        )
    }

    @Test
    fun `server error does not expose response details`() {
        assertEquals(
            "OpenAI is temporarily unavailable. Please retry shortly.",
            openAiErrorMessage(OpenAiApiException(503, null, "internal detail")),
        )
    }
}
