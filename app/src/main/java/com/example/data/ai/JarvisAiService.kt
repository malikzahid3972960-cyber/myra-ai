package com.example.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.AssistantLanguage
import com.example.data.repository.sanitizeGeminiApiKey
import com.example.device.DeviceDateTimeHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

const val GEMINI_API_KEY = "یہاں_key" // AI Studio کے Secrets پینل میں GEMINI_API_KEY سیٹ کریں یا یہاں لکھیں

fun resolveGeminiApiKey(rawKey: String? = null): String {
    val candidate = rawKey ?: run {
        val codeKey = sanitizeGeminiApiKey(GEMINI_API_KEY)
        if (codeKey.isNotBlank() && codeKey != "یہاں_key") {
            codeKey
        } else {
            BuildConfig.GEMINI_API_KEY
        }
    }
    val cleaned = sanitizeGeminiApiKey(candidate)
    if (cleaned.isBlank() ||
        cleaned == "یہاں_key" ||
        cleaned == "GEMINI_API_KEY_DEFAULT_VALUE"
    ) {
        return ""
    }
    return cleaned
}

data class GeneratedWebsiteBundle(
    val htmlCode: String,
    val cssCode: String,
    val jsCode: String,
    val summary: String
)

data class GeneratedDesignBundle(
    val conceptDescription: String,
    val tagline: String,
    val paletteSummary: String,
    val imagePrompt: String,
    val svgPreview: String
)

enum class GeminiErrorCategory {
    INVALID_API_KEY,
    PERMISSION_DENIED,
    BILLING_RESTRICTED,
    QUOTA_EXCEEDED,
    UNSUPPORTED_MODEL,
    NETWORK_ERROR,
    SERVER_ERROR,
    BAD_REQUEST
}

data class GeminiParsedError(
    val category: GeminiErrorCategory,
    val userMessage: String
)

class GeminiApiException(
    val category: GeminiErrorCategory,
    override val message: String,
    cause: Throwable? = null,
    val httpStatusCode: Int? = null
) : IllegalStateException(message, cause) {
    val isTimeoutOr503: Boolean
        get() = cause is SocketTimeoutException || httpStatusCode == 503
}

class JarvisAiService(
    private val baseUrl: String = GEMINI_BASE_URL,
    private val requestTimeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES
) {

    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return Dns.SYSTEM.lookup(hostname).sortedBy { it !is Inet4Address }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .dns(ipv4FirstDns)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(requestTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(requestTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(requestTimeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    internal fun buildSystemPromptText(nowMillis: Long = System.currentTimeMillis()): String {
        val currentDateTime = DeviceDateTimeHelper.buildSystemInstructionDateTime(nowMillis)
        return """
            You are Jarvis, a helpful personal AI assistant for a Pakistani user. Answer every question directly, clearly and accurately, in the same language the user writes in. If the user writes in Urdu script, reply in Urdu script. If they write in Roman Urdu or English, reply in that language. Do not refuse ordinary topics such as horoscopes, general knowledge, cooking, health basics, study help, coding, translation, or everyday advice. For horoscopes, give the daily horoscope for the user's sign if known, or ask for the sign once, and add a short note that it is for entertainment. Do not use markdown symbols such as asterisks or hashes. Keep answers short unless the user asks for detail. If you truly do not know something, say so once and give the best general information you have. Never say you have no access to the date, time, calendar or phone; always use the value given below.

            Current date and time: $currentDateTime.
        """.trimIndent()
    }

    suspend fun testConnection(
        apiKey: String,
        onRetryStatus: (suspend (String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = validateAndSanitizeKey(apiKey)
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", "Reply with only: OK")))
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", 16)
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
            )
        }

        val url = "${baseUrl.trimEnd('/')}/models/$CHAT_MODEL:generateContent"
        val request = buildAuthorizedPostRequest(url, cleanKey, payload.toString())

        executeWithRetry(onRetryStatus = onRetryStatus) { isFinalAttempt ->
            executeWithNetworkErrorMapping(cleanKey, logTimeoutAsError = isFinalAttempt) {
                httpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val parsed = classifyGeminiHttpError(response.code, bodyStr, cleanKey, CHAT_MODEL)
                        throw GeminiApiException(
                            category = parsed.category,
                            message = parsed.userMessage,
                            httpStatusCode = response.code
                        )
                    }
                    val reply = extractTextFromCandidateJson(bodyStr).trim()
                    if (reply.isBlank()) {
                        "کنکشن کامیاب! Gemini ماڈل ($CHAT_MODEL) جواب دینے کے لیے تیار ہے۔"
                    } else {
                        "کنکشن کامیاب! Gemini ماڈل ($CHAT_MODEL) درست کام کر رہا ہے۔"
                    }
                }
            }
        }
    }

    fun streamChatReply(
        apiKey: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        language: AssistantLanguage,
        attachedBitmap: Bitmap? = null,
        attachedTextFileName: String? = null,
        attachedTextContent: String? = null,
        onRetryStatus: (suspend (String) -> Unit)? = null
    ): Flow<String> = flow {
        val cleanKey = validateAndSanitizeKey(apiKey)

        val combinedPrompt = buildString {
            if (!attachedTextFileName.isNullOrBlank() && !attachedTextContent.isNullOrBlank()) {
                append("[Attached File: ").append(attachedTextFileName).append("]\n")
                append(attachedTextContent.take(12000)).append("\n\n")
            }
            append(userPrompt)
        }

        val normalizedHistory = normalizeConversationHistory(history.takeLast(MAX_CHAT_HISTORY_MESSAGES))
        val contentsArray = JSONArray()
        normalizedHistory.forEach { (geminiRole, mergedText) ->
            contentsArray.put(
                JSONObject().apply {
                    put("role", geminiRole)
                    put("parts", JSONArray().put(JSONObject().put("text", mergedText)))
                }
            )
        }

        val currentParts = JSONArray()
        if (attachedBitmap != null) {
            val base64Image = bitmapToBase64Jpeg(attachedBitmap)
            currentParts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", base64Image)
                )
            )
        }
        currentParts.put(JSONObject().put("text", combinedPrompt))

        // Ensure strict alternation if the last item in history was also a user turn
        if (contentsArray.length() > 0) {
            val lastObj = contentsArray.optJSONObject(contentsArray.length() - 1)
            if (lastObj?.optString("role") == "user") {
                val lastParts = lastObj.optJSONArray("parts")
                if (lastParts != null) {
                    for (i in 0 until currentParts.length()) {
                        lastParts.put(currentParts.get(i))
                    }
                }
            } else {
                contentsArray.put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", currentParts)
                    }
                )
            }
        } else {
            contentsArray.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", currentParts)
                }
            )
        }

        val requestPayload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", buildSystemPromptText())))
            )
            put("contents", contentsArray)
            put(
                "generationConfig",
                JSONObject().put(
                    "thinkingConfig",
                    JSONObject().put("thinkingLevel", "LOW")
                )
            )
        }

        val streamUrl = "${baseUrl.trimEnd('/')}/models/$CHAT_MODEL:streamGenerateContent?alt=sse"
        val nonStreamUrl = "${baseUrl.trimEnd('/')}/models/$CHAT_MODEL:generateContent"

        val accumulated = StringBuilder()
        var lastErrorWasSocketTimeout = false

        var lastStreamFinishOrBlockReason: String? = null

        executeWithRetry(
            canRetry = { error -> accumulated.isEmpty() && error.isTimeoutOr503 },
            onRetryStatus = onRetryStatus
        ) { isFinalAttempt ->
            val useNonStreamFallback = isFinalAttempt && lastErrorWasSocketTimeout
            val request = if (useNonStreamFallback) {
                buildAuthorizedPostRequest(
                    url = nonStreamUrl,
                    cleanKey = cleanKey,
                    jsonBody = requestPayload.toString()
                )
            } else {
                buildAuthorizedPostRequest(
                    url = streamUrl,
                    cleanKey = cleanKey,
                    jsonBody = requestPayload.toString(),
                    acceptHeader = "text/event-stream"
                )
            }

            try {
                executeWithNetworkErrorMapping(cleanKey, logTimeoutAsError = isFinalAttempt) {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val errBody = response.body?.string().orEmpty()
                            val parsed = classifyGeminiHttpError(response.code, errBody, cleanKey, CHAT_MODEL)
                            throw GeminiApiException(
                                category = parsed.category,
                                message = parsed.userMessage,
                                httpStatusCode = response.code
                            )
                        }
                        if (useNonStreamFallback) {
                            val bodyStr = response.body?.string().orEmpty()
                            val fullReply = extractTextFromCandidateJson(bodyStr)
                            if (fullReply.isNotBlank()) {
                                accumulated.append(fullReply)
                                emit(accumulated.toString())
                            } else {
                                lastStreamFinishOrBlockReason = extractCandidateBlockReason(bodyStr)
                            }
                        } else {
                            val source = response.body?.source()
                                ?: throw GeminiApiException(
                                    GeminiErrorCategory.SERVER_ERROR,
                                    "Gemini سرور سے خالی جواب موصول ہوا (Empty response body)۔"
                                )

                            while (!source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                if (line.startsWith("data:")) {
                                    val dataJson = line.removePrefix("data:").trim()
                                    if (dataJson.isNotEmpty() && dataJson != "[DONE]") {
                                        val chunkText = extractTextFromCandidateJson(dataJson)
                                        if (chunkText.isNotEmpty()) {
                                            accumulated.append(chunkText)
                                            emit(accumulated.toString())
                                        } else {
                                            extractCandidateBlockReason(dataJson)?.let {
                                                lastStreamFinishOrBlockReason = it
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: GeminiApiException) {
                lastErrorWasSocketTimeout = e.cause is SocketTimeoutException
                throw e
            }
        }

        if (accumulated.isBlank()) {
            val detail = lastStreamFinishOrBlockReason?.let { " ($it)" }.orEmpty()
            throw GeminiApiException(
                GeminiErrorCategory.SERVER_ERROR,
                "Gemini نے کوئی جواب واپس نہیں کیا$detail۔"
            )
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateWebsite(
        apiKey: String,
        businessName: String,
        industry: String,
        requirements: String,
        colorPalette: String
    ): GeneratedWebsiteBundle = withContext(Dispatchers.IO) {
        val cleanKey = validateAndSanitizeKey(apiKey)

        val prompt = """
            Create a complete, modern, responsive business website.
            Business Name: $businessName
            Industry: ${industry.ifBlank { "General Business" }}
            Visual Color Palette: $colorPalette
            User Requirements: $requirements

            Return valid JSON with exact keys:
            - htmlCode: Full semantic HTML5 body/page markup (avoid external CDN scripts that fail offline; use clean inline SVG icons if needed).
            - cssCode: Polished responsive CSS with modern typography, glass/card styling, mobile navigation support, and media queries.
            - jsCode: Clean interactive vanilla JavaScript (navigation toggle, interactive CTA/contact form feedback, smooth section transitions).
            - summary: Short 1-2 sentence overview in Urdu (اردو) of the built website.
        """.trimIndent()

        val schema = JSONObject().apply {
            put("type", "OBJECT")
            put(
                "properties",
                JSONObject()
                    .put("htmlCode", JSONObject().put("type", "STRING"))
                    .put("cssCode", JSONObject().put("type", "STRING"))
                    .put("jsCode", JSONObject().put("type", "STRING"))
                    .put("summary", JSONObject().put("type", "STRING"))
            )
            put("required", JSONArray(listOf("htmlCode", "cssCode", "jsCode", "summary")))
        }

        val rawJson = executeStructuredGenerateContent(
            apiKey = cleanKey,
            model = CHAT_MODEL,
            prompt = prompt,
            responseSchema = schema
        )

        val json = JSONObject(rawJson)
        GeneratedWebsiteBundle(
            htmlCode = json.getString("htmlCode"),
            cssCode = json.getString("cssCode"),
            jsCode = json.getString("jsCode"),
            summary = json.optString("summary", "ویب سائٹ کامیابی سے تیار ہو گئی ہے۔")
        )
    }

    suspend fun generateDesignConcept(
        apiKey: String,
        brandName: String,
        style: String,
        colorScheme: String,
        notes: String
    ): GeneratedDesignBundle = withContext(Dispatchers.IO) {
        val cleanKey = validateAndSanitizeKey(apiKey)

        val prompt = """
            Design a professional brand identity and logo concept.
            Brand Name: $brandName
            Design Style: $style
            Color Scheme: $colorScheme
            Additional Notes: ${notes.ifBlank { "Modern, memorable, scalable emblem" }}

            Return valid JSON with exact keys:
            - conceptDescription: Clear design rationale, symbolism, and typography recommendation in Urdu (اردو).
            - tagline: Crisp brand tagline.
            - paletteSummary: Curated hex color palette.
            - imagePrompt: Detailed English prompt for generating a clean, centered graphic logo mark on a solid dark or contrasting background, no watermark, vector-crisp aesthetic.
            - svgPreview: Clean standalone <svg viewBox="0 0 240 240" xmlns="http://www.w3.org/2000/svg">...</svg> geometric brand mark preview.
        """.trimIndent()

        val schema = JSONObject().apply {
            put("type", "OBJECT")
            put(
                "properties",
                JSONObject()
                    .put("conceptDescription", JSONObject().put("type", "STRING"))
                    .put("tagline", JSONObject().put("type", "STRING"))
                    .put("paletteSummary", JSONObject().put("type", "STRING"))
                    .put("imagePrompt", JSONObject().put("type", "STRING"))
                    .put("svgPreview", JSONObject().put("type", "STRING"))
            )
            put(
                "required",
                JSONArray(listOf("conceptDescription", "tagline", "paletteSummary", "imagePrompt", "svgPreview"))
            )
        }

        val rawJson = executeStructuredGenerateContent(
            apiKey = cleanKey,
            model = CHAT_MODEL,
            prompt = prompt,
            responseSchema = schema
        )

        val json = JSONObject(rawJson)
        GeneratedDesignBundle(
            conceptDescription = json.getString("conceptDescription"),
            tagline = json.optString("tagline", ""),
            paletteSummary = json.optString("paletteSummary", colorScheme),
            imagePrompt = json.getString("imagePrompt"),
            svgPreview = json.optString("svgPreview", "")
        )
    }

    suspend fun generateLogoBitmap(apiKey: String, imagePrompt: String): Bitmap = withContext(Dispatchers.IO) {
        val cleanKey = validateAndSanitizeKey(apiKey)

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", imagePrompt)))
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject().put("responseModalities", JSONArray().put("IMAGE"))
            )
        }

        val url = "${baseUrl.trimEnd('/')}/models/$IMAGE_MODEL:generateContent"
        val request = buildAuthorizedPostRequest(url, cleanKey, payload.toString())

        executeWithRetry { isFinalAttempt ->
            executeWithNetworkErrorMapping(cleanKey, logTimeoutAsError = isFinalAttempt) {
                httpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val parsed = classifyGeminiHttpError(response.code, bodyStr, cleanKey, IMAGE_MODEL)
                        throw GeminiApiException(
                            category = parsed.category,
                            message = parsed.userMessage,
                            httpStatusCode = response.code
                        )
                    }
                    val root = JSONObject(bodyStr)
                    val candidates = root.optJSONArray("candidates")
                    val parts = candidates?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")

                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            val inlineData = part.optJSONObject("inlineData") ?: continue
                            val base64 = inlineData.optString("data")
                            if (base64.isNotBlank()) {
                                val bytes = Base64.decode(base64, Base64.DEFAULT)
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null) return@use bmp
                            }
                        }
                    }
                    throw GeminiApiException(
                        GeminiErrorCategory.SERVER_ERROR,
                        "تصویر ماڈل ($IMAGE_MODEL) نے لوگو تصویر واپس نہیں کی۔"
                    )
                }
            }
        }
    }

    suspend fun transcribeAudio(
        apiKey: String,
        wavAudioBytes: ByteArray,
        language: AssistantLanguage
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = validateAndSanitizeKey(apiKey)
        if (wavAudioBytes.size <= 44) return@withContext ""
        val base64Audio = Base64.encodeToString(wavAudioBytes, Base64.NO_WRAP)
        val languageHint = when (language) {
            AssistantLanguage.ENGLISH -> "English"
            AssistantLanguage.HINDI -> "Hindi or English"
            AssistantLanguage.ROMAN_URDU -> "Roman Urdu, Urdu, or English"
            AssistantLanguage.URDU, AssistantLanguage.AUTO -> "Urdu, Roman Urdu, or English"
        }
        val prompt = "Transcribe the exact spoken words from this audio in their original language ($languageHint). " +
            "Output ONLY the user's exact spoken words with no quotes, markdown, greeting, or assistant reply. " +
            "If there is only silence or background noise with no clear speech, return strictly an empty string."

        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "You are a literal speech-to-text transcriber, NOT a conversational assistant. Output only the exact words spoken in the audio. Never reply, never greet, never say 'I am Jarvis', and return empty text if no speech is heard."
                        )
                    )
                )
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "parts",
                            JSONArray()
                                .put(
                                    JSONObject().put(
                                        "inlineData",
                                        JSONObject()
                                            .put("mimeType", "audio/wav")
                                            .put("data", base64Audio)
                                    )
                                )
                                .put(JSONObject().put("text", prompt))
                        )
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", 256)
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
            )
        }

        val url = "${baseUrl.trimEnd('/')}/models/$CHAT_MODEL:generateContent"
        val request = buildAuthorizedPostRequest(url, cleanKey, payload.toString())

        executeWithRetry { isFinalAttempt ->
            executeWithNetworkErrorMapping(cleanKey, logTimeoutAsError = isFinalAttempt) {
                httpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val parsed = classifyGeminiHttpError(response.code, bodyStr, cleanKey, CHAT_MODEL)
                        throw GeminiApiException(
                            category = parsed.category,
                            message = parsed.userMessage,
                            httpStatusCode = response.code
                        )
                    }
                    sanitizeTranscriptResult(extractTextFromCandidateJson(bodyStr))
                }
            }
        }
    }

    private fun sanitizeTranscriptResult(raw: String): String {
        val cleaned = raw.trim().trim('"', '\'', '“', '”')
        if (cleaned.isBlank() || !cleaned.any { it.isLetterOrDigit() }) return ""
        val lower = cleaned.lowercase()
        val nonSpeechMarkers = setOf(
            "silence", "[silence]", "(silence)",
            "no speech", "[no speech]", "(no speech)",
            "inaudible", "[inaudible]", "(inaudible)",
            "background noise", "[background noise]", "(background noise)",
            "music", "[music]", "none", "n/a", "empty"
        )
        if (lower in nonSpeechMarkers) return ""
        return cleaned
    }

    private suspend fun executeStructuredGenerateContent(
        apiKey: String,
        model: String,
        prompt: String,
        responseSchema: JSONObject
    ): String {
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", responseSchema)
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
            )
        }

        val url = "${baseUrl.trimEnd('/')}/models/$model:generateContent"
        val request = buildAuthorizedPostRequest(url, apiKey, payload.toString())

        return executeWithRetry { isFinalAttempt ->
            executeWithNetworkErrorMapping(apiKey, logTimeoutAsError = isFinalAttempt) {
                httpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val parsed = classifyGeminiHttpError(response.code, bodyStr, apiKey, model)
                        throw GeminiApiException(
                            category = parsed.category,
                            message = parsed.userMessage,
                            httpStatusCode = response.code
                        )
                    }
                    val text = extractTextFromCandidateJson(bodyStr)
                    if (text.isBlank()) {
                        throw GeminiApiException(
                            GeminiErrorCategory.SERVER_ERROR,
                            "Gemini سے خالی JSON جواب موصول ہوا۔"
                        )
                    }
                    text
                }
            }
        }
    }

    private suspend inline fun <T> executeWithRetry(
        crossinline canRetry: (GeminiApiException) -> Boolean = { it.isTimeoutOr503 },
        noinline onRetryStatus: (suspend (String) -> Unit)? = null,
        block: suspend (isFinalAttempt: Boolean) -> T
    ): T {
        var attempt = 0
        while (true) {
            val isFinalAttempt = attempt >= maxRetries
            try {
                return block(isFinalAttempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GeminiApiException) {
                if (!canRetry(e)) {
                    throw e
                }
                if (attempt >= maxRetries) {
                    Log.e(TAG, "Gemini failed after $maxRetries retries: ${e.message}", e)
                    throw GeminiApiException(
                        category = e.category,
                        message = "$SERVER_BUSY_MESSAGE\nاصل پیغام: ${e.message}",
                        cause = e.cause ?: e,
                        httpStatusCode = e.httpStatusCode
                    )
                }
                attempt++
                Log.w(TAG, "Gemini transient timeout/503 (retry $attempt/$maxRetries): ${e.message}")
                onRetryStatus?.invoke(RETRY_STATUS_MESSAGE)
                if (retryDelayMs > 0L) {
                    delay(retryDelayMs)
                }
            }
        }
    }

    private fun validateAndSanitizeKey(rawKey: String): String {
        val cleanKey = resolveGeminiApiKey(rawKey)
        if (cleanKey.isBlank()) {
            throw GeminiApiException(
                GeminiErrorCategory.INVALID_API_KEY,
                "Gemini API key خالی ہے، براہِ کرم AI Studio کے Secrets پینل یا کوڈ میں GEMINI_API_KEY سیٹ کریں۔"
            )
        }
        val isAsciiPrintable = cleanKey.all { it in '\u0021'..'\u007E' }
        if (!isAsciiPrintable || cleanKey.length < 16) {
            throw GeminiApiException(
                GeminiErrorCategory.INVALID_API_KEY,
                "API key کی ساخت درست نہیں لگتی، براہِ کرم Secrets پینل یا کوڈ میں مکمل GEMINI_API_KEY دوبارہ لکھیں۔"
            )
        }
        return cleanKey
    }

    private fun buildAuthorizedPostRequest(
        url: String,
        cleanKey: String,
        jsonBody: String,
        acceptHeader: String? = null
    ): Request {
        return try {
            Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", cleanKey)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .apply {
                    if (!acceptHeader.isNullOrBlank()) {
                        addHeader("Accept", acceptHeader)
                    }
                }
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()
        } catch (e: IllegalArgumentException) {
            throw GeminiApiException(
                GeminiErrorCategory.INVALID_API_KEY,
                "API key میں غیر مجاز حروف موجود ہیں، براہِ کرم کوڈ میں GEMINI_API_KEY دوبارہ لکھیں۔",
                e
            )
        }
    }

    private inline fun <T> executeWithNetworkErrorMapping(
        cleanKey: String,
        logTimeoutAsError: Boolean = true,
        block: () -> T
    ): T {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiApiException) {
            throw e
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Gemini DNS failure")
            throw GeminiApiException(
                GeminiErrorCategory.NETWORK_ERROR,
                "انٹرنیٹ یا DNS سے رابطہ نہیں ہو سکا (generativelanguage.googleapis.com تک رسائی نہیں)۔ انٹرنیٹ چیک کریں۔",
                e
            )
        } catch (e: SocketTimeoutException) {
            if (logTimeoutAsError) {
                Log.e(TAG, "Gemini timeout")
            } else {
                Log.w(TAG, "Gemini stream timeout; switching to fallback")
            }
            throw GeminiApiException(
                GeminiErrorCategory.NETWORK_ERROR,
                "Gemini سرور سے جواب آنے میں وقت زیادہ لگ گیا (Connection Timeout)۔ دوبارہ کوشش کریں۔",
                e
            )
        } catch (e: ConnectException) {
            Log.e(TAG, "Gemini connect failure")
            throw GeminiApiException(
                GeminiErrorCategory.NETWORK_ERROR,
                "Gemini سرور سے کنکشن قائم نہیں ہو سکا۔ نیٹ ورک یا VPN چیک کریں۔",
                e
            )
        } catch (e: SSLException) {
            Log.e(TAG, "Gemini SSL failure")
            throw GeminiApiException(
                GeminiErrorCategory.NETWORK_ERROR,
                "محفوظ کنکشن (SSL/TLS) قائم نہیں ہو سکا۔ تاریخ/وقت اور نیٹ ورک چیک کریں۔",
                e
            )
        } catch (e: IOException) {
            val safeDetail = redactKey(e.localizedMessage.orEmpty(), cleanKey)
            Log.e(TAG, "Gemini IO failure: $safeDetail")
            throw GeminiApiException(
                GeminiErrorCategory.NETWORK_ERROR,
                "نیٹ ورک خرابی: ${safeDetail.ifBlank { "کنکشن منقطع ہو گیا" }}",
                e
            )
        } catch (e: Exception) {
            val safeDetail = redactKey(e.localizedMessage.orEmpty(), cleanKey)
            Log.e(TAG, "Gemini unexpected failure: ${e::class.java.simpleName}")
            throw GeminiApiException(
                GeminiErrorCategory.SERVER_ERROR,
                "Gemini درخواست مکمل نہیں ہو سکی: ${safeDetail.ifBlank { e::class.java.simpleName }}",
                e
            )
        }
    }

    private fun normalizeConversationHistory(
        history: List<Pair<String, String>>
    ): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        for ((rawRole, rawText) in history) {
            val text = rawText.trim()
            if (text.isBlank()) continue
            val role = if (rawRole == "assistant" || rawRole == "model") "model" else "user"
            // Gemini conversations should start with a user message
            if (result.isEmpty() && role == "model") continue

            if (result.isNotEmpty() && result.last().first == role) {
                val prev = result.removeAt(result.lastIndex)
                result.add(role to "${prev.second}\n$text")
            } else {
                result.add(role to text)
            }
        }
        return result
    }

    private fun extractTextFromCandidateJson(jsonStr: String): String {
        return try {
            val root = JSONObject(jsonStr)
            val parts = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return ""
            buildString {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought", false)) continue
                    val text = part.optString("text")
                    append(text)
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractCandidateBlockReason(jsonStr: String): String? {
        return try {
            val root = JSONObject(jsonStr)
            val errObj = root.optJSONObject("error")
            if (errObj != null) {
                val msg = errObj.optString("message")
                if (msg.isNotBlank()) return msg
            }
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            if (blockReason.isNotBlank()) return "blockReason=$blockReason"
            val finishReason = root.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason").orEmpty()
            if (finishReason.isNotBlank() && finishReason != "STOP") return "finishReason=$finishReason"
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "JarvisAiService"
        const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val CHAT_MODEL = "gemini-3.5-flash-lite"
        const val IMAGE_MODEL = "gemini-nano-banana-2.1"
        const val MAX_CHAT_HISTORY_MESSAGES = 12
        const val DEFAULT_TIMEOUT_SECONDS = 60L
        const val DEFAULT_RETRY_DELAY_MS = 5_000L
        const val DEFAULT_MAX_RETRIES = 3
        const val RETRY_STATUS_MESSAGE = "سرور جواب دینے میں دیر لگا رہا ہے، دوبارہ کوشش ہو رہی ہے..."
        const val SERVER_BUSY_MESSAGE = "ابھی سرور مصروف ہے، کچھ دیر بعد دوبارہ کوشش کریں۔"

        private val INVALID_KEY_REASONS = setOf(
            "API_KEY_INVALID",
            "API_KEY_NOT_FOUND",
            "API_KEY_EMPTY"
        )

        private val PERMISSION_REASONS = setOf(
            "SERVICE_DISABLED",
            "API_KEY_SERVICE_BLOCKED",
            "API_KEY_ANDROID_APP_BLOCKED",
            "API_KEY_HTTP_REFERRER_BLOCKED",
            "API_KEY_IP_ADDRESS_BLOCKED",
            "ACCESS_TOKEN_SCOPE_INSUFFICIENT"
        )

        fun redactKey(input: String, key: String): String {
            if (key.isBlank() || input.isBlank()) return input
            return input.replace(key, "[REDACTED]")
        }

        fun classifyGeminiHttpError(
            statusCode: Int,
            rawBody: String,
            apiKeyForRedaction: String = "",
            modelName: String = CHAT_MODEL
        ): GeminiParsedError {
            var errorStatus = ""
            var apiMsg = ""
            val reasons = mutableSetOf<String>()

            try {
                val errObj = JSONObject(rawBody).optJSONObject("error")
                if (errObj != null) {
                    errorStatus = errObj.optString("status")
                    apiMsg = errObj.optString("message")
                    val details = errObj.optJSONArray("details")
                    if (details != null) {
                        for (i in 0 until details.length()) {
                            val reason = details.optJSONObject(i)?.optString("reason").orEmpty()
                            if (reason.isNotBlank()) reasons.add(reason.uppercase())
                        }
                    }
                }
            } catch (_: Exception) {
                // Non-JSON upstream error body
            }

            val safeMsg = redactKey(apiMsg.trim(), apiKeyForRedaction)
            val fallbackRaw = redactKey(rawBody.trim(), apiKeyForRedaction).take(300)
            val exactDetail = safeMsg.ifBlank { fallbackRaw }
            val msgLower = safeMsg.lowercase()
            val bodyLower = redactKey(rawBody, apiKeyForRedaction).lowercase()

            // 1. Billing restrictions (must check before generic quota/permission where explicit)
            val isBillingRestricted = reasons.contains("BILLING_DISABLED") ||
                msgLower.contains("billing") ||
                msgLower.contains("free tier is not available") ||
                msgLower.contains("payment") ||
                msgLower.contains("account is suspended") ||
                (errorStatus.equals("FAILED_PRECONDITION", ignoreCase = true) &&
                    (msgLower.contains("tier") || msgLower.contains("country") || msgLower.contains("credit")))

            if (isBillingRestricted) {
                return GeminiParsedError(
                    category = GeminiErrorCategory.BILLING_RESTRICTED,
                    userMessage = "بلنگ یا پلان کی پابندی (Billing Restriction): ${exactDetail.ifBlank { "HTTP $statusCode" }}"
                )
            }

            // 2. Explicit Invalid API Key (never blanket-catch all 400 or 403!)
            val isInvalidKey = reasons.any { it in INVALID_KEY_REASONS } ||
                bodyLower.contains("api_key_invalid") ||
                msgLower.contains("api key not valid") ||
                msgLower.contains("invalid api key") ||
                msgLower.contains("api key expired") ||
                msgLower.contains("api_key_expired") ||
                (statusCode == 401 && !msgLower.contains("permission"))

            if (isInvalidKey) {
                return GeminiParsedError(
                    category = GeminiErrorCategory.INVALID_API_KEY,
                    userMessage = "API key درست نہیں ہے (Invalid API Key)۔ براہِ کرم کوڈ میں درست GEMINI_API_KEY دوبارہ لکھیں۔${if (exactDetail.isNotBlank()) " ($exactDetail)" else ""}"
                )
            }

            // 3. API Key Permission / Restrictions / API Disabled
            val isPermissionProblem = reasons.any { it in PERMISSION_REASONS } ||
                errorStatus.equals("PERMISSION_DENIED", ignoreCase = true) ||
                statusCode == 403 ||
                msgLower.contains("has not been used in project") ||
                msgLower.contains("is disabled") ||
                msgLower.contains("are blocked") ||
                msgLower.contains("permission denied") ||
                msgLower.contains("user location is not supported")

            if (isPermissionProblem) {
                return GeminiParsedError(
                    category = GeminiErrorCategory.PERMISSION_DENIED,
                    userMessage = "API Key اجازت یا پابندی کا مسئلہ (Permission Denied): ${exactDetail.ifBlank { "HTTP $statusCode" }}"
                )
            }

            // 4. Quota Exceeded / Rate Limit
            val isQuotaExceeded = statusCode == 429 ||
                errorStatus.equals("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                reasons.contains("RATE_LIMIT_EXCEEDED") ||
                msgLower.contains("quota") ||
                msgLower.contains("rate limit") ||
                msgLower.contains("too many requests")

            if (isQuotaExceeded) {
                return GeminiParsedError(
                    category = GeminiErrorCategory.QUOTA_EXCEEDED,
                    userMessage = "Gemini API کوٹہ / حد پوری ہو گئی (Quota Exceeded): ${exactDetail.ifBlank { "HTTP $statusCode" }}"
                )
            }

            // 5. Unsupported Model
            val isUnsupportedModel = statusCode == 404 ||
                errorStatus.equals("NOT_FOUND", ignoreCase = true) ||
                msgLower.contains("not found for api version") ||
                msgLower.contains("not supported for generatecontent") ||
                (msgLower.contains("model") &&
                    (msgLower.contains("not found") || msgLower.contains("unsupported") || msgLower.contains("does not exist")))

            if (isUnsupportedModel) {
                return GeminiParsedError(
                    category = GeminiErrorCategory.UNSUPPORTED_MODEL,
                    userMessage = "ماڈل دستیاب یا سپورٹڈ نہیں ($modelName): ${exactDetail.ifBlank { "HTTP $statusCode" }}"
                )
            }

            // 6. Server / Configuration Errors
            if (statusCode in 500..599 ||
                errorStatus.equals("INTERNAL", ignoreCase = true) ||
                errorStatus.equals("UNAVAILABLE", ignoreCase = true) ||
                errorStatus.equals("DEADLINE_EXCEEDED", ignoreCase = true)
            ) {
                return GeminiParsedError(
                    category = GeminiErrorCategory.SERVER_ERROR,
                    userMessage = "Gemini سرور یا کنفیگریشن کی خرابی ($statusCode): ${exactDetail.ifBlank { "HTTP $statusCode" }}"
                )
            }

            // 7. Genuine Bad Request / Invalid Argument or non-JSON error body
            return GeminiParsedError(
                category = GeminiErrorCategory.BAD_REQUEST,
                userMessage = if (exactDetail.isNotBlank()) {
                    "Gemini error ($statusCode): $exactDetail"
                } else {
                    "Gemini HTTP error ($statusCode)"
                }
            )
        }
    }
}
