package com.example.data.ai

import com.example.data.model.AssistantLanguage
import com.example.data.repository.sanitizeGeminiApiKey
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JarvisAiServiceTest {

    @Test
    fun sanitizeGeminiApiKey_stripsWhitespaceNewlinesDirectionalMarksAndQuotes() {
        val dirty = "  \" \u200F\u200E AIzaSyValid_Key-1234567890abcdef \r\n \t \"  "
        assertEquals("AIzaSyValid_Key-1234567890abcdef", sanitizeGeminiApiKey(dirty))
    }

    @Test
    fun classifyGeminiHttpError_doesNotMisclassifyNonKey400AsInvalidKey() {
        val body = """
            {
              "error": {
                "code": 400,
                "message": "Invalid value at 'generation_config.thinking_budget'",
                "status": "INVALID_ARGUMENT"
              }
            }
        """.trimIndent()
        val parsed = JarvisAiService.classifyGeminiHttpError(400, body)
        assertEquals(GeminiErrorCategory.BAD_REQUEST, parsed.category)
        assertFalse(parsed.userMessage.contains("API key درست نہیں"))
        assertTrue(parsed.userMessage.contains("thinking_budget"))
    }

    @Test
    fun classifyGeminiHttpError_identifiesInvalidApiKeyAccurately() {
        val body = """
            {
              "error": {
                "code": 400,
                "message": "API key not valid. Please pass a valid API key.",
                "status": "INVALID_ARGUMENT",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "API_KEY_INVALID",
                    "domain": "googleapis.com"
                  }
                ]
              }
            }
        """.trimIndent()
        val parsed = JarvisAiService.classifyGeminiHttpError(400, body)
        assertEquals(GeminiErrorCategory.INVALID_API_KEY, parsed.category)
        assertTrue(parsed.userMessage.contains("Invalid API Key"))
    }

    @Test
    fun classifyGeminiHttpError_identifiesPermissionProblem403() {
        val body = """
            {
              "error": {
                "code": 403,
                "message": "Generative Language API has not been used in project 12345 before or it is disabled.",
                "status": "PERMISSION_DENIED",
                "details": [
                  {
                    "reason": "SERVICE_DISABLED"
                  }
                ]
              }
            }
        """.trimIndent()
        val parsed = JarvisAiService.classifyGeminiHttpError(403, body)
        assertEquals(GeminiErrorCategory.PERMISSION_DENIED, parsed.category)
        assertTrue(parsed.userMessage.contains("Permission Denied"))
        assertFalse(parsed.userMessage.contains("API key درست نہیں"))
    }

    @Test
    fun classifyGeminiHttpError_identifiesBillingRestriction() {
        val body = """
            {
              "error": {
                "code": 403,
                "message": "Requests from this project require an active billing account.",
                "status": "PERMISSION_DENIED",
                "details": [
                  {
                    "reason": "BILLING_DISABLED"
                  }
                ]
              }
            }
        """.trimIndent()
        val parsed = JarvisAiService.classifyGeminiHttpError(403, body)
        assertEquals(GeminiErrorCategory.BILLING_RESTRICTED, parsed.category)
        assertTrue(parsed.userMessage.contains("Billing Restriction"))
    }

    @Test
    fun classifyGeminiHttpError_identifiesQuotaExceeded429() {
        val body = """
            {
              "error": {
                "code": 429,
                "message": "Quota exceeded for quota metric 'Generate Content API requests per minute'.",
                "status": "RESOURCE_EXHAUSTED"
              }
            }
        """.trimIndent()
        val parsed = JarvisAiService.classifyGeminiHttpError(429, body)
        assertEquals(GeminiErrorCategory.QUOTA_EXCEEDED, parsed.category)
        assertTrue(parsed.userMessage.contains("Quota Exceeded"))
    }

    @Test
    fun classifyGeminiHttpError_identifiesUnsupportedModel404() {
        val body = """
            {
              "error": {
                "code": 404,
                "message": "models/gemini-old-model is not found for API version v1beta.",
                "status": "NOT_FOUND"
              }
            }
        """.trimIndent()
        val parsed = JarvisAiService.classifyGeminiHttpError(404, body, modelName = "gemini-old-model")
        assertEquals(GeminiErrorCategory.UNSUPPORTED_MODEL, parsed.category)
        assertTrue(parsed.userMessage.contains("gemini-old-model"))
    }

    @Test
    fun classifyGeminiHttpError_identifiesServerError503() {
        val body = """
            {
              "error": {
                "code": 503,
                "message": "The model is overloaded. Please try again later.",
                "status": "UNAVAILABLE"
              }
            }
        """.trimIndent()
        val parsed = JarvisAiService.classifyGeminiHttpError(503, body)
        assertEquals(GeminiErrorCategory.SERVER_ERROR, parsed.category)
    }

    @Test
    fun testConnection_andStreamChatReply_sendKeyInHeaderNotUrl_andParseSse() = runTest {
        val testKey = "AIzaSyTestHeaderKey_1234567890abcdef"
        var capturedHeaderKey: String? = null
        var capturedPathAndQuery: String? = null

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            capturedHeaderKey = exchange.requestHeaders.getFirst("x-goog-api-key")
            capturedPathAndQuery = exchange.requestURI.toString()

            val path = exchange.requestURI.path
            val responseBytes = if (path.contains(":streamGenerateContent")) {
                (
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"السلام \"}]}}]}\n\n" +
                        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"علیکم!\"}]}}]}\n\n" +
                        "data: [DONE]\n\n"
                    ).toByteArray(Charsets.UTF_8)
            } else {
                """{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}""".toByteArray(Charsets.UTF_8)
            }

            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}/v1beta"
            val service = JarvisAiService(baseUrl = baseUrl)

            val connectionMsg = service.testConnection(" \" $testKey \n \" ")
            assertTrue(connectionMsg.contains("کامیاب"))
            assertEquals(testKey, capturedHeaderKey)
            assertFalse(capturedPathAndQuery.orEmpty().contains(testKey))

            val chunks = service.streamChatReply(
                apiKey = testKey,
                history = listOf("user" to "پہلا سوال", "user" to "دوسرا سوال"),
                userPrompt = "ہیلو",
                language = AssistantLanguage.URDU
            ).toList()

            assertEquals(listOf("السلام ", "السلام علیکم!"), chunks)
            assertEquals(testKey, capturedHeaderKey)
            assertFalse(capturedPathAndQuery.orEmpty().contains(testKey))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testConnection_unreachablePort_mapsToNetworkErrorWithoutExposingKey() = runTest {
        val secretKey = "AIzaSySecretKeyShouldNotLeak123456"
        val service = JarvisAiService(baseUrl = "http://127.0.0.1:1/v1beta")
        var caught: GeminiApiException? = null
        try {
            service.testConnection(secretKey)
        } catch (e: GeminiApiException) {
            caught = e
        }
        assertEquals(GeminiErrorCategory.NETWORK_ERROR, caught?.category)
        assertFalse(caught?.message.orEmpty().contains(secretKey))
    }

    @Test
    fun streamChatReply_sendsLowThinkingLevel_andIgnoresThoughtParts() = runTest {
        val testKey = "AIzaSyTestThinkingKey_1234567890abcdef"
        var capturedRequestBody = ""

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            capturedRequestBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
            val responseBytes = (
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"internal thinking\",\"thought\":true}]}}]}\n\n" +
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"جی بالکل!\"}]}}]}\n\n" +
                    "data: [DONE]\n\n"
                ).toByteArray(Charsets.UTF_8)

            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}/v1beta"
            val service = JarvisAiService(baseUrl = baseUrl)

            val chunks = service.streamChatReply(
                apiKey = testKey,
                history = emptyList(),
                userPrompt = "سلام",
                language = AssistantLanguage.URDU
            ).toList()

            assertEquals(listOf("جی بالکل!"), chunks)
            assertTrue(capturedRequestBody.contains("\"thinkingLevel\":\"LOW\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun defaults_matchRequiredModelTimeoutAndRetryPolicy() {
        assertEquals("gemini-3.5-flash-lite", JarvisAiService.CHAT_MODEL)
        assertEquals(60L, JarvisAiService.DEFAULT_TIMEOUT_SECONDS)
        assertEquals(5_000L, JarvisAiService.DEFAULT_RETRY_DELAY_MS)
        assertEquals(3, JarvisAiService.DEFAULT_MAX_RETRIES)
    }

    @Test
    fun testConnection_retriesThreeTimesOn503_andSucceedsOnFourthAttempt() = runTest {
        val testKey = "AIzaSyTestRetryKey_1234567890abcdef"
        var attemptCount = 0
        val retryMessages = mutableListOf<String>()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            attemptCount++
            if (attemptCount <= 3) {
                val errBytes = """{"error":{"code":503,"message":"Service overloaded","status":"UNAVAILABLE"}}"""
                    .toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(503, errBytes.size.toLong())
                exchange.responseBody.use { it.write(errBytes) }
            } else {
                val okBytes = """{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}"""
                    .toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, okBytes.size.toLong())
                exchange.responseBody.use { it.write(okBytes) }
            }
        }
        server.start()

        try {
            val service = JarvisAiService(
                baseUrl = "http://127.0.0.1:${server.address.port}/v1beta",
                retryDelayMs = 10L
            )
            val result = service.testConnection(testKey) { status ->
                retryMessages.add(status)
            }
            assertEquals(4, attemptCount)
            assertEquals(3, retryMessages.size)
            assertTrue(retryMessages.all { it == JarvisAiService.RETRY_STATUS_MESSAGE })
            assertTrue(result.contains("کامیاب"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testConnection_allRetriesFailWith503_throwsBusyMessageWithOriginalError() = runTest {
        val testKey = "AIzaSyTestRetryFailKey_1234567890abcd"
        var attemptCount = 0

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            attemptCount++
            val errBytes = """{"error":{"code":503,"message":"High server load right now","status":"UNAVAILABLE"}}"""
                .toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(503, errBytes.size.toLong())
            exchange.responseBody.use { it.write(errBytes) }
        }
        server.start()

        try {
            val service = JarvisAiService(
                baseUrl = "http://127.0.0.1:${server.address.port}/v1beta",
                retryDelayMs = 10L
            )
            var caught: GeminiApiException? = null
            try {
                service.testConnection(testKey)
            } catch (e: GeminiApiException) {
                caught = e
            }
            assertEquals(4, attemptCount)
            assertTrue(caught?.message.orEmpty().contains(JarvisAiService.SERVER_BUSY_MESSAGE))
            assertTrue(caught?.message.orEmpty().contains("High server load right now"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamChatReply_retriesOn503_emitsRetryStatus_andRecovers() = runTest {
        val testKey = "AIzaSyTestStreamRetry_1234567890abcd"
        var attemptCount = 0
        val retryMessages = mutableListOf<String>()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            attemptCount++
            if (attemptCount <= 2) {
                val errBytes = """{"error":{"code":503,"message":"Temporarily busy","status":"UNAVAILABLE"}}"""
                    .toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(503, errBytes.size.toLong())
                exchange.responseBody.use { it.write(errBytes) }
            } else {
                val responseBytes = (
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"خوش آمدید!\"}]}}]}\n\n" +
                        "data: [DONE]\n\n"
                    ).toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, responseBytes.size.toLong())
                exchange.responseBody.use { it.write(responseBytes) }
            }
        }
        server.start()

        try {
            val service = JarvisAiService(
                baseUrl = "http://127.0.0.1:${server.address.port}/v1beta",
                retryDelayMs = 10L
            )
            val chunks = service.streamChatReply(
                apiKey = testKey,
                history = emptyList(),
                userPrompt = "ہیلو",
                language = AssistantLanguage.URDU,
                onRetryStatus = { msg -> retryMessages.add(msg) }
            ).toList()

            assertEquals(3, attemptCount)
            assertEquals(2, retryMessages.size)
            assertTrue(retryMessages.all { it == JarvisAiService.RETRY_STATUS_MESSAGE })
            assertEquals(listOf("خوش آمدید!"), chunks)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamChatReply_sendsRequestedSystemInstruction_currentDateTime_andLast12HistoryMessages() = runTest {
        val testKey = "AIzaSyTestHistoryKey_1234567890abcdef"
        var capturedRequestBody = ""

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            capturedRequestBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
            val responseBytes = (
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"360\"}]}}]}\n\n" +
                    "data: [DONE]\n\n"
                ).toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        server.start()

        try {
            val service = JarvisAiService(baseUrl = "http://127.0.0.1:${server.address.port}/v1beta")
            assertEquals(12, JarvisAiService.MAX_CHAT_HISTORY_MESSAGES)

            // 14 prior alternating messages (7 user + 7 assistant); only last 12 should be sent + current prompt = 13 contents entries
            val history14 = (1..14).map { idx ->
                val role = if (idx % 2 == 1) "user" else "assistant"
                role to "msg-$idx"
            }

            val chunks = service.streamChatReply(
                apiKey = testKey,
                history = history14,
                userPrompt = "what is 15 percent of 2400",
                language = AssistantLanguage.URDU
            ).toList()

            assertEquals(listOf("360"), chunks)

            val payload = org.json.JSONObject(capturedRequestBody)
            val sysText = payload.getJSONObject("systemInstruction")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            assertTrue(sysText.contains("You are Jarvis, a helpful personal AI assistant for a Pakistani user."))
            assertTrue(sysText.contains("Do not refuse ordinary topics such as horoscopes"))
            assertTrue(sysText.contains("Never say you have no access to the date, time, calendar or phone; always use the value given below."))
            assertTrue(sysText.contains("Current date and time:"))

            val contents = payload.getJSONArray("contents")
            assertEquals(13, contents.length())
            // msg-1 and msg-2 were excluded because takeLast(12) starts at msg-3
            assertEquals("msg-3", contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
            assertEquals("what is 15 percent of 2400", contents.getJSONObject(12).getJSONArray("parts").getJSONObject(0).getString("text"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun classifyGeminiHttpError_preservesExactNonJsonErrorText() {
        val parsed = JarvisAiService.classifyGeminiHttpError(502, "Upstream Bad Gateway from Edge Node")
        assertTrue(parsed.userMessage.contains("Upstream Bad Gateway from Edge Node"))
    }

    @Test
    fun transcribeAudio_sendsAudioWavInlineData_andReturnsTranscript() = runTest {
        var capturedRequestBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1beta/models/${JarvisAiService.CHAT_MODEL}:generateContent") { exchange ->
            capturedRequestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val json = """
                {"candidates":[{"content":{"parts":[{"text":"Assalam o Alaikum Jarvis"}]}}]}
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, json.size.toLong())
            exchange.responseBody.use { it.write(json) }
        }
        server.start()

        try {
            val service = JarvisAiService(baseUrl = "http://127.0.0.1:${server.address.port}/v1beta")
            val wav = ByteArray(128) { 42 }
            val result = service.transcribeAudio(
                apiKey = "AIzaSyTestKey1234567890ABCD",
                wavAudioBytes = wav,
                language = AssistantLanguage.URDU
            )
            assertEquals("Assalam o Alaikum Jarvis", result)
            val payload = org.json.JSONObject(capturedRequestBody)
            val parts = payload.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
            val inlineData = parts.getJSONObject(0).getJSONObject("inlineData")
            assertEquals("audio/wav", inlineData.getString("mimeType"))
            assertTrue(inlineData.getString("data").isNotBlank())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun transcribeAudio_filtersSilenceMarkerAndSendsStrictTranscriberInstruction() = runTest {
        var capturedRequestBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1beta/models/${JarvisAiService.CHAT_MODEL}:generateContent") { exchange ->
            capturedRequestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val json = """
                {"candidates":[{"content":{"parts":[{"text":"[silence]"}]}}]}
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, json.size.toLong())
            exchange.responseBody.use { it.write(json) }
        }
        server.start()

        try {
            val service = JarvisAiService(baseUrl = "http://127.0.0.1:${server.address.port}/v1beta")
            val wav = ByteArray(128) { 42 }
            val result = service.transcribeAudio(
                apiKey = "AIzaSyTestKey1234567890ABCD",
                wavAudioBytes = wav,
                language = AssistantLanguage.ENGLISH
            )
            assertEquals("", result)
            val payload = org.json.JSONObject(capturedRequestBody)
            val sysText = payload.getJSONObject("systemInstruction")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
            assertTrue(sysText.contains("literal speech-to-text transcriber"))
        } finally {
            server.stop(0)
        }
    }
}
