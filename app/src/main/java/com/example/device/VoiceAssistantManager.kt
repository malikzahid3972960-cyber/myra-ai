package com.example.device

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.data.ai.JarvisAiService
import com.example.data.ai.resolveGeminiApiKey
import com.example.data.model.AssistantLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

data class VoiceUiOption(
    val name: String,
    val displayLabel: String,
    val localeTag: String
)

data class SystemVoiceFallbackEvent(
    val language: AssistantLanguage,
    val fallbackErrorMessage: String,
    val preferDirectRecorder: Boolean = false
)

internal interface SpeechRecognizerHandle {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(recognizerIntent: Intent)
    fun stopListening()
    fun destroy()
}

private class AndroidSpeechRecognizerHandle(
    private val delegate: SpeechRecognizer
) : SpeechRecognizerHandle {
    override fun setRecognitionListener(listener: RecognitionListener) {
        delegate.setRecognitionListener(listener)
    }

    override fun startListening(recognizerIntent: Intent) {
        delegate.startListening(recognizerIntent)
    }

    override fun stopListening() {
        delegate.stopListening()
    }

    override fun destroy() {
        delegate.destroy()
    }
}

class VoiceAssistantManager internal constructor(
    context: Context,
    private val recognizerFactory: (Context) -> SpeechRecognizerHandle,
    private val availabilityChecker: (Context) -> Boolean,
    private val initTtsOnStart: Boolean,
    private val audioRecorderFactory: () -> AudioRecorderHandle = { AndroidDirectAudioRecorder() },
    private val audioTranscriber: suspend (ByteArray, AssistantLanguage) -> String = { wavBytes, lang ->
        JarvisAiService().transcribeAudio(
            apiKey = resolveGeminiApiKey(),
            wavAudioBytes = wavBytes,
            language = lang
        )
    },
    private val canUseDirectAudioFallback: () -> Boolean = { resolveGeminiApiKey().isNotBlank() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val onFinalSpeechResult: (String) -> Unit
) {
    constructor(
        context: Context,
        onFinalSpeechResult: (String) -> Unit
    ) : this(
        context = context,
        recognizerFactory = { ctx ->
            AndroidSpeechRecognizerHandle(SpeechRecognizer.createSpeechRecognizer(ctx))
        },
        availabilityChecker = { ctx ->
            SpeechRecognizer.isRecognitionAvailable(ctx)
        },
        initTtsOnStart = true,
        onFinalSpeechResult = onFinalSpeechResult
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val managerScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isLiveConversationActive = MutableStateFlow(false)
    val isLiveConversationActive: StateFlow<Boolean> = _isLiveConversationActive.asStateFlow()

    private val _isProcessingSpeech = MutableStateFlow(false)
    val isProcessingSpeech: StateFlow<Boolean> = _isProcessingSpeech.asStateFlow()

    private val _isDirectRecordingMode = MutableStateFlow(false)
    val isDirectRecordingMode: StateFlow<Boolean> = _isDirectRecordingMode.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<VoiceUiOption>>(emptyList())
    val availableVoices: StateFlow<List<VoiceUiOption>> = _availableVoices.asStateFlow()

    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

    private val _systemVoiceFallbackRequests = MutableSharedFlow<SystemVoiceFallbackEvent>(extraBufferCapacity = 2)
    val systemVoiceFallbackRequests: SharedFlow<SystemVoiceFallbackEvent> = _systemVoiceFallbackRequests.asSharedFlow()

    val isSpeechRecognitionAvailable: Boolean
        get() = availabilityChecker(appContext)

    private var speechRecognizer: SpeechRecognizerHandle? = null
    private var activeAudioRecorder: AudioRecorderHandle? = null
    private var textToSpeech: TextToSpeech? = null
    private var currentLocale: Locale = Locale.forLanguageTag("ur-PK")

    private var lastSpokenText: String = ""
    private var pendingListenLanguageAfterTts: AssistantLanguage? = null
    private var hasSpeechStarted: Boolean = false
    private var hasDeliveredFinalResult: Boolean = false
    private var hasRetriedLocaleFallback: Boolean = false
    private var hasRetriedBusy: Boolean = false
    private var preferDirectRecorder: Boolean = false
    private var urPkUnsupportedOnDevice: Boolean = false
    private var consecutiveEmptyTurns: Int = 0
    private var activeLanguage: AssistantLanguage = AssistantLanguage.URDU
    private var activeLocaleTag: String = "ur-PK"
    private var sessionGeneration: Int = 0

    private var readyWatchdogRunnable: Runnable? = null
    private var stopTimeoutRunnable: Runnable? = null
    private var pendingStartRunnable: Runnable? = null
    private var liveAutoResumeRunnable: Runnable? = null

    init {
        if (initTtsOnStart) {
            initTts()
        }
    }

    fun currentLastSpokenText(): String = lastSpokenText

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun clearWatchdogs() {
        readyWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        readyWatchdogRunnable = null
        stopTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        stopTimeoutRunnable = null
        pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStartRunnable = null
        liveAutoResumeRunnable?.let { mainHandler.removeCallbacks(it) }
        liveAutoResumeRunnable = null
    }

    private fun cancelActiveListeningForTts() {
        clearWatchdogs()
        pendingListenLanguageAfterTts = null
        sessionGeneration++
        hasSpeechStarted = false
        hasDeliveredFinalResult = false
        _partialText.value = ""
        _isListening.value = false
        _isProcessingSpeech.value = false
        _isDirectRecordingMode.value = false
        _rmsLevel.value = 0f
        destroyRecognizer()
        cancelActiveRecorder()
    }

    private fun beginQueuedOrDirectListening(language: AssistantLanguage) {
        if (_isSpeaking.value) {
            Log.d(TAG, "Waiting for active TTS utterance to finish before starting SpeechRecognizer")
            pendingListenLanguageAfterTts = language
            try {
                textToSpeech?.stop()
            } catch (_: Exception) {
            }
            pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
            val fallbackRunnable = Runnable {
                pendingStartRunnable = null
                if (_isSpeaking.value && pendingListenLanguageAfterTts != null) {
                    onTtsUtteranceFinished()
                }
            }
            pendingStartRunnable = fallbackRunnable
            mainHandler.postDelayed(fallbackRunnable, TTS_DRAIN_DELAY_MS)
            return
        }

        beginListeningImmediately(language)
    }

    private fun beginListeningImmediately(language: AssistantLanguage) {
        if (preferDirectRecorder) {
            startDirectRecordingInternal(language)
            return
        }

        if (!isSpeechRecognitionAvailable) {
            Log.w(TAG, "Inline SpeechRecognizer unavailable; requesting system voice UI / recorder fallback")
            _systemVoiceFallbackRequests.tryEmit(
                SystemVoiceFallbackEvent(
                    language = language,
                    fallbackErrorMessage = "Speech recognition service unavailable on this device."
                )
            )
            return
        }

        val initialLocale = resolvePreferredRecognitionLocale(language)
        startRecognizerSession(language = language, localeOverride = initialLocale)
    }

    private fun onTtsUtteranceFinished() {
        pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStartRunnable = null
        _isSpeaking.value = false
        val queuedLang = pendingListenLanguageAfterTts
        pendingListenLanguageAfterTts = null
        if (queuedLang != null) {
            beginListeningImmediately(queuedLang)
        } else {
            scheduleLiveTurnAutoResumeIfNeeded(delayMs = LIVE_TURN_RESUME_DELAY_MS)
        }
    }

    private fun scheduleLiveTurnAutoResumeIfNeeded(delayMs: Long) {
        liveAutoResumeRunnable?.let { mainHandler.removeCallbacks(it) }
        liveAutoResumeRunnable = null
        if (!_isLiveConversationActive.value) return
        if (_isSpeaking.value || _isListening.value || _isProcessingSpeech.value) return

        val resumeRunnable = Runnable {
            liveAutoResumeRunnable = null
            if (_isLiveConversationActive.value &&
                !_isSpeaking.value &&
                !_isListening.value &&
                !_isProcessingSpeech.value
            ) {
                hasRetriedLocaleFallback = false
                hasRetriedBusy = false
                beginListeningImmediately(activeLanguage)
            }
        }
        liveAutoResumeRunnable = resumeRunnable
        mainHandler.postDelayed(resumeRunnable, delayMs)
    }

    private fun recordFailedOrEmptyVoiceTurn(
        errorMessage: String,
        isPermissionError: Boolean
    ) {
        if (isPermissionError) {
            _isLiveConversationActive.value = false
            _voiceError.value = errorMessage
            return
        }
        if (!_isLiveConversationActive.value) {
            _voiceError.value = errorMessage
            return
        }

        consecutiveEmptyTurns++
        if (consecutiveEmptyTurns >= MAX_LIVE_CONSECUTIVE_EMPTY_TURNS) {
            _isLiveConversationActive.value = false
            _voiceError.value = LIVE_PAUSED_EMPTY_NOTICE
        } else {
            _voiceError.value = errorMessage
            scheduleLiveTurnAutoResumeIfNeeded(delayMs = LIVE_RETRY_DELAY_MS)
        }
    }

    internal fun setSpeakingForTest(spokenText: String) {
        lastSpokenText = spokenText
        _isSpeaking.value = true
    }

    internal fun notifyTtsUtteranceDoneForTest() {
        onTtsUtteranceFinished()
    }

    private fun initTts() {
        textToSpeech = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                _ttsReady.value = true
                applyBestAvailableLocale(currentLocale)
                refreshVoices()
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        runOnMain { _isSpeaking.value = true }
                    }

                    override fun onDone(utteranceId: String?) {
                        runOnMain { onTtsUtteranceFinished() }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        runOnMain { onTtsUtteranceFinished() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnMain { onTtsUtteranceFinished() }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        runOnMain { onTtsUtteranceFinished() }
                    }
                })
            } else {
                Log.w(TAG, "TTS initialization failed with status: $status")
            }
        }
    }

    private fun refreshVoices() {
        val tts = textToSpeech ?: return
        val voices = try {
            tts.voices?.toList().orEmpty()
                .filter { !it.isNetworkConnectionRequired || it.locale.language in listOf("ur", "en", "hi") }
                .sortedWith(compareBy<Voice> { it.locale.language != "ur" }.thenBy { it.locale.language }.thenBy { it.name })
                .take(24)
                .map { voice ->
                    val qualityLabel = if (voice.quality >= Voice.QUALITY_HIGH) "HD" else "Std"
                    VoiceUiOption(
                        name = voice.name,
                        displayLabel = "${voice.locale.displayLanguage} (${voice.locale.country.ifBlank { "Global" }}) • $qualityLabel",
                        localeTag = voice.locale.toLanguageTag()
                    )
                }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to enumerate TTS voices", e)
            emptyList()
        }
        _availableVoices.value = voices
    }

    fun startListening(language: AssistantLanguage) {
        runOnMain {
            if (_isProcessingSpeech.value) {
                Log.d(TAG, "Ignoring startListening while speech transcription is still in progress")
                return@runOnMain
            }
            liveAutoResumeRunnable?.let { mainHandler.removeCallbacks(it) }
            liveAutoResumeRunnable = null
            _voiceError.value = null
            if (activeLanguage != language) {
                urPkUnsupportedOnDevice = false
            }
            activeLanguage = language
            consecutiveEmptyTurns = 0
            hasRetriedLocaleFallback = false
            hasRetriedBusy = false
            beginQueuedOrDirectListening(language)
        }
    }

    fun startLiveConversation(language: AssistantLanguage) {
        runOnMain {
            _isLiveConversationActive.value = true
            urPkUnsupportedOnDevice = false
            consecutiveEmptyTurns = 0
            _voiceError.value = null
            activeLanguage = language
            hasRetriedLocaleFallback = false
            hasRetriedBusy = false
            if (!_isListening.value && !_isProcessingSpeech.value) {
                beginQueuedOrDirectListening(language)
            }
        }
    }

    fun stopLiveConversation() {
        runOnMain {
            _isLiveConversationActive.value = false
            consecutiveEmptyTurns = 0
            stopSpeaking()
            cancelListening()
        }
    }

    fun setLiveConversationActive(enabled: Boolean, language: AssistantLanguage = activeLanguage) {
        if (enabled) {
            startLiveConversation(language)
        } else {
            runOnMain {
                _isLiveConversationActive.value = false
                liveAutoResumeRunnable?.let { mainHandler.removeCallbacks(it) }
                liveAutoResumeRunnable = null
            }
        }
    }

    fun startDirectRecording(
        language: AssistantLanguage,
        rememberAsPreferred: Boolean = true
    ) {
        runOnMain {
            if (_isProcessingSpeech.value) return@runOnMain
            if (rememberAsPreferred) {
                preferDirectRecorder = true
            }
            if (_isSpeaking.value) {
                pendingListenLanguageAfterTts = language
                try {
                    textToSpeech?.stop()
                } catch (_: Exception) {
                }
                pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
                val fallbackRunnable = Runnable {
                    pendingStartRunnable = null
                    if (_isSpeaking.value && pendingListenLanguageAfterTts != null) {
                        onTtsUtteranceFinished()
                    }
                }
                pendingStartRunnable = fallbackRunnable
                mainHandler.postDelayed(fallbackRunnable, TTS_DRAIN_DELAY_MS)
            } else {
                startDirectRecordingInternal(language)
            }
        }
    }

    private fun startDirectRecordingInternal(language: AssistantLanguage) {
        clearWatchdogs()
        destroyRecognizer()
        cancelActiveRecorder()

        sessionGeneration++
        hasSpeechStarted = false
        hasDeliveredFinalResult = false
        activeLanguage = language
        _partialText.value = ""
        _voiceError.value = null
        _isProcessingSpeech.value = false

        val recorder = audioRecorderFactory()
        try {
            recorder.startRecording(
                onRmsUpdated = { rms ->
                    _rmsLevel.value = rms
                },
                onAutoSilenceDetected = {
                    runOnMain {
                        if (_isListening.value && _isDirectRecordingMode.value) {
                            stopListening()
                        }
                    }
                }
            )
            activeAudioRecorder = recorder
            _isDirectRecordingMode.value = true
            _isListening.value = true
            Log.d(TAG, "Direct AudioRecord started (language=${language.storageValue})")
        } catch (sec: SecurityException) {
            Log.e(TAG, "Microphone permission denied starting AudioRecord", sec)
            _isListening.value = false
            _isDirectRecordingMode.value = false
            _isLiveConversationActive.value = false
            _rmsLevel.value = 0f
            _voiceError.value = "ERROR_INSUFFICIENT_PERMISSIONS: مائیکروفون کی اجازت مسترد کر دی گئی ہے۔ (Microphone permission denied)"
        } catch (e: Exception) {
            Log.e(TAG, "Direct AudioRecord failed to start", e)
            _isListening.value = false
            _isDirectRecordingMode.value = false
            _rmsLevel.value = 0f
            recordFailedOrEmptyVoiceTurn(
                errorMessage = e.localizedMessage
                    ?: "مائیکروفون ریکارڈنگ شروع نہیں ہو سکی۔ براہ کرم مائیک اجازت اور آڈیو چیک کریں۔",
                isPermissionError = false
            )
        }
    }

    private fun startRecognizerSession(
        language: AssistantLanguage,
        localeOverride: String
    ) {
        clearWatchdogs()
        destroyRecognizer()
        cancelActiveRecorder()

        val currentGen = ++sessionGeneration
        hasSpeechStarted = false
        hasDeliveredFinalResult = false
        activeLanguage = language
        activeLocaleTag = localeOverride
        _isDirectRecordingMode.value = false
        _isProcessingSpeech.value = false
        _partialText.value = ""

        val recognizer = try {
            recognizerFactory(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
            _isListening.value = false
            _voiceError.value = "ERROR_CLIENT: ${e.localizedMessage ?: "وائس ریکگنیشن شروع نہیں ہو سکی۔"}"
            return
        }
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (currentGen != sessionGeneration) return
                readyWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                readyWatchdogRunnable = null
                Log.d(TAG, "onReadyForSpeech (locale=$localeOverride)")
                _isListening.value = true
            }

            override fun onBeginningOfSpeech() {
                if (currentGen != sessionGeneration) return
                readyWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
                readyWatchdogRunnable = null
                hasSpeechStarted = true
                Log.d(TAG, "onBeginningOfSpeech (locale=$localeOverride)")
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (currentGen != sessionGeneration) return
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _rmsLevel.value = normalized
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (currentGen != sessionGeneration) return
                Log.d(TAG, "onEndOfSpeech")
                _isListening.value = false
                _rmsLevel.value = 0f
            }

            override fun onError(error: Int) {
                if (currentGen != sessionGeneration || hasDeliveredFinalResult) return
                clearWatchdogs()
                Log.w(TAG, "onError=${speechErrorName(error)} ($error), locale=$localeOverride")
                _isListening.value = false
                _isProcessingSpeech.value = false
                _rmsLevel.value = 0f
                _partialText.value = ""

                // 1. If recognizer reported BUSY once, reset cleanly and retry once
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !hasRetriedBusy) {
                    Log.d(TAG, "Retrying SpeechRecognizer after ERROR_RECOGNIZER_BUSY")
                    hasRetriedBusy = true
                    startRecognizerSession(language = language, localeOverride = localeOverride)
                    return
                }

                if ((error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) &&
                    localeOverride.equals("ur-PK", ignoreCase = true)
                ) {
                    urPkUnsupportedOnDevice = true
                }

                // 2. If ur-PK failed before speech even started (e.g. pack missing), fall back once to en-US
                val fallbackLocale = resolveFallbackRecognitionLocale(localeOverride)
                val allowLocaleRetry = !hasRetriedLocaleFallback &&
                    fallbackLocale != localeOverride &&
                    !hasSpeechStarted
                if ((error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) &&
                    allowLocaleRetry
                ) {
                    Log.d(TAG, "Retrying SpeechRecognizer with fallback locale=$fallbackLocale")
                    hasRetriedLocaleFallback = true
                    startRecognizerSession(language = language, localeOverride = fallbackLocale)
                    return
                }

                destroyRecognizer()

                // 3. When SpeechRecognizer fails to match speech or lacks voice packs and Gemini audio fallback
                //    is available, seamlessly switch to Direct AI Mic (AudioRecord + Gemini transcription)
                //    so Urdu, Roman Urdu, and English speech work reliably without repeated ERROR_NO_MATCH.
                val isPermissionErr = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                val recoverableViaDirectMic = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_AUDIO ||
                    error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                    (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                        (!_isLiveConversationActive.value || consecutiveEmptyTurns == 0))
                if (recoverableViaDirectMic && canUseDirectAudioFallback()) {
                    preferDirectRecorder = true
                    _systemVoiceFallbackRequests.tryEmit(
                        SystemVoiceFallbackEvent(
                            language = language,
                            fallbackErrorMessage = formatSpeechError(error),
                            preferDirectRecorder = true
                        )
                    )
                    return
                }

                recordFailedOrEmptyVoiceTurn(
                    errorMessage = formatSpeechError(error),
                    isPermissionError = isPermissionErr
                )
            }

            override fun onResults(results: Bundle?) {
                if (currentGen != sessionGeneration || hasDeliveredFinalResult) return
                clearWatchdogs()
                _isListening.value = false
                _isProcessingSpeech.value = false
                _rmsLevel.value = 0f
                _partialText.value = ""

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull { !it.isNullOrBlank() }?.trim()

                if (!spokenText.isNullOrBlank()) {
                    Log.d(TAG, "onResults final delivered: '$spokenText'")
                    hasDeliveredFinalResult = true
                    deliverFinalSpeech(spokenText)
                } else {
                    Log.w(TAG, "onResults returned empty text")
                    recordFailedOrEmptyVoiceTurn(RETRY_SPEECH_PROMPT, isPermissionError = false)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Intentionally ignored: use final SpeechRecognizer result only.
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = buildSpeechRecognizerIntent(language = language, localeOverride = localeOverride)

        try {
            _isListening.value = true
            recognizer.startListening(intent)
            val watchdog = Runnable {
                if (currentGen == sessionGeneration && _isListening.value && !hasSpeechStarted) {
                    Log.w(TAG, "SpeechRecognizer onReadyForSpeech timed out")
                    sessionGeneration++
                    _isListening.value = false
                    _rmsLevel.value = 0f
                    destroyRecognizer()
                    val timeoutError = formatSpeechError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    if (canUseDirectAudioFallback() && (!_isLiveConversationActive.value || consecutiveEmptyTurns == 0)) {
                        preferDirectRecorder = true
                        _systemVoiceFallbackRequests.tryEmit(
                            SystemVoiceFallbackEvent(
                                language = language,
                                fallbackErrorMessage = timeoutError,
                                preferDirectRecorder = true
                            )
                        )
                    } else {
                        recordFailedOrEmptyVoiceTurn(timeoutError, isPermissionError = false)
                    }
                }
            }
            readyWatchdogRunnable = watchdog
            mainHandler.postDelayed(watchdog, READY_WATCHDOG_MS)
        } catch (e: Exception) {
            Log.e(TAG, "recognizer.startListening failed", e)
            clearWatchdogs()
            _isListening.value = false
            destroyRecognizer()
            recordFailedOrEmptyVoiceTurn(
                errorMessage = "ERROR_CLIENT: ${e.localizedMessage ?: "وائس ریکگنیشن شروع نہیں ہو سکی۔"}",
                isPermissionError = false
            )
        }
    }

    fun buildSpeechRecognizerIntent(
        language: AssistantLanguage,
        localeOverride: String? = null
    ): Intent {
        val targetLocale = localeOverride ?: resolvePreferredRecognitionLocale(language)
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLocale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLocale)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        }
    }

    fun deliverExternalSpeechResult(spokenText: String) {
        val cleaned = sanitizeRecognizedSpeech(spokenText)
        clearWatchdogs()
        _partialText.value = ""
        _isListening.value = false
        _isProcessingSpeech.value = false
        _isDirectRecordingMode.value = false
        if (cleaned.isBlank() || isSelfEchoOrNoise(cleaned, lastSpokenText = lastSpokenText)) {
            recordFailedOrEmptyVoiceTurn(RETRY_SPEECH_PROMPT, isPermissionError = false)
        } else {
            _voiceError.value = null
            deliverFinalSpeech(cleaned)
        }
    }

    fun reportErrorMessage(message: String) {
        runOnMain {
            clearWatchdogs()
            _isListening.value = false
            _isProcessingSpeech.value = false
            _isDirectRecordingMode.value = false
            _isLiveConversationActive.value = false
            _rmsLevel.value = 0f
            _voiceError.value = message
        }
    }

    private fun deliverFinalSpeech(spokenText: String) {
        val cleaned = sanitizeRecognizedSpeech(spokenText)
        if (cleaned.isBlank() || isSelfEchoOrNoise(cleaned, lastSpokenText = lastSpokenText)) {
            runOnMain {
                recordFailedOrEmptyVoiceTurn(RETRY_SPEECH_PROMPT, isPermissionError = false)
            }
            return
        }
        runOnMain {
            consecutiveEmptyTurns = 0
            _voiceError.value = null
            onFinalSpeechResult(cleaned)
        }
    }

    internal fun sanitizeRecognizedSpeech(raw: String): String {
        val cleaned = raw.trim().trim('"', '\'', '“', '”')
        if (cleaned.isBlank() || !cleaned.any { it.isLetterOrDigit() }) return ""
        val lower = cleaned.lowercase()
        val noiseMarkers = setOf(
            "silence", "[silence]", "(silence)",
            "no speech", "[no speech]", "(no speech)",
            "inaudible", "[inaudible]", "(inaudible)",
            "background noise", "[background noise]", "(background noise)",
            "music", "[music]", "none", "n/a", "empty"
        )
        if (lower in noiseMarkers) return ""
        return cleaned
    }

    private fun resolvePreferredRecognitionLocale(language: AssistantLanguage): String {
        return when (language) {
            AssistantLanguage.ENGLISH -> "en-US"
            AssistantLanguage.HINDI -> "hi-IN"
            AssistantLanguage.URDU,
            AssistantLanguage.ROMAN_URDU,
            AssistantLanguage.AUTO -> if (urPkUnsupportedOnDevice) "en-US" else "ur-PK"
        }
    }

    private fun resolveFallbackRecognitionLocale(failedLocale: String): String {
        return if (!failedLocale.equals("en-US", ignoreCase = true)) "en-US" else failedLocale
    }

    fun stopListening() {
        runOnMain {
            clearWatchdogs()
            val recorder = activeAudioRecorder
            if (_isDirectRecordingMode.value && recorder != null) {
                activeAudioRecorder = null
                _isListening.value = false
                _isDirectRecordingMode.value = false
                _rmsLevel.value = 0f
                _isProcessingSpeech.value = true

                val capturedLanguage = activeLanguage
                managerScope.launch {
                    try {
                        val result = withContext(ioDispatcher) { recorder.stopRecording() }
                        when (result) {
                            is RecordedAudioResult.AudioCaptured -> {
                                val transcript = withContext(ioDispatcher) {
                                    audioTranscriber(result.wavBytes, capturedLanguage)
                                }.trim()
                                _isProcessingSpeech.value = false
                                if (transcript.isNotBlank()) {
                                    _voiceError.value = null
                                    deliverFinalSpeech(transcript)
                                } else {
                                    recordFailedOrEmptyVoiceTurn(
                                        errorMessage = "آواز واضح سنائی نہیں دی، مائیک کے قریب دوبارہ بولیں۔",
                                        isPermissionError = false
                                    )
                                }
                            }
                            RecordedAudioResult.TooShort -> {
                                _isProcessingSpeech.value = false
                                recordFailedOrEmptyVoiceTurn(
                                    errorMessage = "ریکارڈنگ بہت مختصر تھی۔ مائیک دبا کر مکمل جملہ بولیں۔",
                                    isPermissionError = false
                                )
                            }
                            RecordedAudioResult.NoAudioSignal -> {
                                _isProcessingSpeech.value = false
                                recordFailedOrEmptyVoiceTurn(
                                    errorMessage = "مائیکروفون سے کوئی آواز موصول نہیں ہوئی۔ براہ کرم فون/براؤزر کی مائیک پرمیشن اور والیوم چیک کریں۔",
                                    isPermissionError = false
                                )
                            }
                            is RecordedAudioResult.Failed -> {
                                _isProcessingSpeech.value = false
                                recordFailedOrEmptyVoiceTurn(
                                    errorMessage = result.message,
                                    isPermissionError = result.isPermissionError
                                )
                            }
                        }
                    } catch (c: CancellationException) {
                        _isProcessingSpeech.value = false
                        throw c
                    } catch (e: Exception) {
                        Log.e(TAG, "Direct audio transcription failed", e)
                        _isProcessingSpeech.value = false
                        recordFailedOrEmptyVoiceTurn(
                            errorMessage = e.localizedMessage
                                ?: "آواز کو متن میں بدلنے میں خرابی پیش آئی۔",
                            isPermissionError = false
                        )
                    }
                }
                return@runOnMain
            }

            if (!_isListening.value && speechRecognizer == null) return@runOnMain

            _isListening.value = false
            _rmsLevel.value = 0f
            _isProcessingSpeech.value = true

            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "speechRecognizer.stopListening failed", e)
            }

            val genAtStop = sessionGeneration
            val stopTimeout = Runnable {
                if (genAtStop == sessionGeneration && _isProcessingSpeech.value && !hasDeliveredFinalResult) {
                    _isProcessingSpeech.value = false
                    _partialText.value = ""
                    destroyRecognizer()
                    recordFailedOrEmptyVoiceTurn(RETRY_SPEECH_PROMPT, isPermissionError = false)
                }
            }
            stopTimeoutRunnable = stopTimeout
            mainHandler.postDelayed(stopTimeout, STOP_TIMEOUT_MS)
        }
    }

    fun cancelListening() {
        runOnMain {
            clearWatchdogs()
            pendingListenLanguageAfterTts = null
            _isLiveConversationActive.value = false
            consecutiveEmptyTurns = 0
            sessionGeneration++
            _partialText.value = ""
            _isListening.value = false
            _isProcessingSpeech.value = false
            _isDirectRecordingMode.value = false
            _rmsLevel.value = 0f
            destroyRecognizer()
            cancelActiveRecorder()
        }
    }

    fun clearError() {
        _voiceError.value = null
    }

    fun speak(
        rawText: String,
        language: AssistantLanguage,
        speechRate: Float,
        pitch: Float,
        selectedVoiceName: String?,
        isMuted: Boolean
    ) {
        if (isMuted) {
            _isLiveConversationActive.value = false
            return
        }
        val cleanText = sanitizeForSpeech(rawText)
        val tts = textToSpeech
        if (!_ttsReady.value || tts == null || cleanText.isBlank()) {
            if (_isLiveConversationActive.value) {
                scheduleLiveTurnAutoResumeIfNeeded(delayMs = LIVE_TURN_RESUME_DELAY_MS)
            }
            return
        }

        // Stop and destroy any active SpeechRecognizer / AudioRecorder BEFORE TTS starts speaking
        // so the microphone never captures TTS output as an echo.
        cancelActiveListeningForTts()

        val containsUrduScript = cleanText.any { it in '\u0600'..'\u06FF' }
        val targetLocale = when {
            language == AssistantLanguage.ENGLISH && !containsUrduScript -> Locale.US
            language == AssistantLanguage.HINDI -> Locale.forLanguageTag("hi-IN")
            containsUrduScript || language == AssistantLanguage.URDU || language == AssistantLanguage.AUTO -> {
                Locale.forLanguageTag("ur-PK")
            }
            else -> Locale.US
        }
        currentLocale = targetLocale

        if (!selectedVoiceName.isNullOrBlank()) {
            val matchedVoice = tts.voices?.firstOrNull { it.name == selectedVoiceName }
            if (matchedVoice != null) {
                tts.voice = matchedVoice
            } else {
                applyBestAvailableLocale(targetLocale)
            }
        } else {
            applyBestAvailableLocale(targetLocale)
        }

        tts.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
        tts.setPitch(pitch.coerceIn(0.5f, 2.0f))

        lastSpokenText = cleanText
        _isSpeaking.value = true
        val utteranceId = UUID.randomUUID().toString()
        val result = tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            _isSpeaking.value = false
            if (_isLiveConversationActive.value) {
                scheduleLiveTurnAutoResumeIfNeeded(delayMs = LIVE_TURN_RESUME_DELAY_MS)
            }
        }
    }

    private fun applyBestAvailableLocale(preferred: Locale) {
        val tts = textToSpeech ?: return
        val candidates = listOf(
            preferred,
            Locale.forLanguageTag("ur-PK"),
            Locale.forLanguageTag("ur"),
            Locale.forLanguageTag("hi-IN"),
            Locale.US
        )
        for (loc in candidates) {
            val status = try {
                tts.isLanguageAvailable(loc)
            } catch (_: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (status >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = loc
                return
            }
        }
        tts.language = Locale.US
    }

    fun stopSpeaking() {
        pendingListenLanguageAfterTts = null
        pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStartRunnable = null
        liveAutoResumeRunnable?.let { mainHandler.removeCallbacks(it) }
        liveAutoResumeRunnable = null
        try {
            textToSpeech?.stop()
        } catch (_: Exception) {
        }
        _isSpeaking.value = false
    }

    fun release() {
        clearWatchdogs()
        pendingListenLanguageAfterTts = null
        managerScope.cancel()
        destroyRecognizer()
        cancelActiveRecorder()
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {
        }
        textToSpeech = null
    }

    private fun cancelActiveRecorder() {
        try {
            activeAudioRecorder?.cancelRecording()
        } catch (_: Exception) {
        }
        activeAudioRecorder = null
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    private fun sanitizeForSpeech(input: String): String {
        return input
            .replace(Regex("```[\\s\\S]*?```"), " کوڈ اسکرین پر موجود ہے۔ ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("[*_#>~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(1600)
    }

    companion object {
        private const val TAG = "VoiceAssistantManager"
        private const val READY_WATCHDOG_MS = 6000L
        private const val STOP_TIMEOUT_MS = 3500L
        private const val TTS_DRAIN_DELAY_MS = 300L
        private const val LIVE_TURN_RESUME_DELAY_MS = 350L
        private const val LIVE_RETRY_DELAY_MS = 600L
        private const val MAX_LIVE_CONSECUTIVE_EMPTY_TURNS = 2

        const val STARTUP_GREETING = "Assalam o alaikum, main Jarvis hoon"
        const val RETRY_SPEECH_PROMPT = "Dobara bolein"
        const val LIVE_PAUSED_EMPTY_NOTICE = "مسلسل آواز نہ ملنے پر Live Voice روک دیا گیا ہے۔ دوبارہ شروع کرنے کے لیے Live Voice یا مائیک دبائیں۔"

        private val NOISE_WORDS = setOf(
            "hmm", "hm", "hmmm", "hmmmm",
            "uh", "uhh", "uhhh",
            "um", "umm", "ummm",
            "ah", "ahh", "eh", "oh", "ohh",
            "mm", "mmm", "er", "err",
            "ہمم", "ہم", "اوں", "اوہ", "ہوں",
            "silence", "nospeech", "inaudible", "backgroundnoise", "music", "none", "empty"
        )

        private val KNOWN_GREETING_PHRASES = listOf(
            STARTUP_GREETING,
            "I am Jarvis. I am here to help you.",
            "I am Jarvis. I am listening. I am here to help you.",
            "I am Jarvis, I am here to help you",
            "I am Jarvis"
        )

        fun speechErrorName(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            else -> "ERROR_UNKNOWN_$error"
        }

        fun formatSpeechError(error: Int): String {
            val codeName = speechErrorName(error)
            val detail = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "مائیکروفون کی اجازت مسترد کر دی گئی ہے۔ براہ کرم Settings سے Microphone Permission دیں۔"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "اسپیچ ریکگنیشن کے لیے انٹرنیٹ خرابی پیش آئی۔"
                SpeechRecognizer.ERROR_AUDIO ->
                    "مائیکروفون آڈیو ریکارڈنگ میں خرابی پیش آئی۔"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "مائیک مصروف ہے، چند سیکنڈ بعد دوبارہ کوشش کریں۔"
                SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                    "اسپیچ سروس سے رابطہ منقطع ہو گیا، دوبارہ کوشش کریں۔"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "کوئی آواز سنائی نہیں دی، مائیک دبا کر دوبارہ بولیں۔"
                SpeechRecognizer.ERROR_NO_MATCH ->
                    "آواز واضح سمجھ نہیں آئی، دوبارہ بولیں۔"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    "منتخب زبان کی وائس سروس دستیاب نہیں۔"
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
                    "وائس درخواستوں کی حد پوری ہو گئی، تھوڑی دیر بعد کوشش کریں۔"
                else ->
                    "آواز ان پٹ میں خرابی پیش آئی، دوبارہ کوشش کریں۔"
            }
            return "$codeName: $detail"
        }

        private fun normalizeSpeechForComparison(text: String): String {
            return text
                .lowercase()
                .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun isSelfEchoOrNoise(
            recognizedText: String,
            lastAssistantReply: String = "",
            lastSpokenText: String = ""
        ): Boolean {
            val normalized = normalizeSpeechForComparison(recognizedText)
            if (normalized.isBlank() || normalized.length <= 1) return true

            val collapsedWord = normalized.replace(" ", "")
            if (collapsedWord in NOISE_WORDS) return true

            val tokens = normalized.split(" ").filter { it.isNotBlank() }
            if (tokens.size == 1 && tokens.first() in NOISE_WORDS) return true

            val echoCandidates = buildList {
                addAll(KNOWN_GREETING_PHRASES)
                if (lastAssistantReply.isNotBlank()) add(lastAssistantReply)
                if (lastSpokenText.isNotBlank()) add(lastSpokenText)
            }

            for (candidate in echoCandidates) {
                val normCandidate = normalizeSpeechForComparison(candidate)
                if (normCandidate.isBlank()) continue
                if (normalized == normCandidate) return true
                if (normalized.length >= 12 && (
                        normalized.contains(normCandidate) ||
                            (normCandidate.contains(normalized) &&
                                normalized.length.toFloat() / normCandidate.length.toFloat() >= 0.45f)
                        )
                ) {
                    return true
                }
            }

            return false
        }
    }
}
