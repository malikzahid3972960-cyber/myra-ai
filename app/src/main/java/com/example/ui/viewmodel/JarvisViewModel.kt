package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.ai.JarvisAiService
import com.example.data.ai.resolveGeminiApiKey
import com.example.data.local.AppDatabase
import com.example.data.model.AssistantLanguage
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.repository.JarvisRepository
import com.example.data.repository.LocalUiSettings
import com.example.data.repository.PreferencesDataStore
import com.example.device.DeviceDateTimeHelper
import com.example.device.ImmediatePromptAction
import com.example.device.PhoneAppBridge
import com.example.device.VoiceAssistantManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DataUiState<out T> {
    data object Loading : DataUiState<Nothing>
    data class Success<T>(val data: T) : DataUiState<T>
    data class Error(val message: String) : DataUiState<Nothing>
}

data class ChatAttachment(
    val fileName: String,
    val bitmap: Bitmap? = null,
    val textContent: String? = null
)

class JarvisViewModel(
    private val repository: JarvisRepository,
    private val aiService: JarvisAiService,
    private val dataStore: PreferencesDataStore,
    private val apiKeyProvider: () -> String = { resolveGeminiApiKey() }
) : ViewModel() {

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _streamingReply = MutableStateFlow<String?>(null)
    val streamingReply: StateFlow<String?> = _streamingReply.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _chatError = MutableStateFlow<String?>(null)
    val chatError: StateFlow<String?> = _chatError.asStateFlow()

    private val _attachment = MutableStateFlow<ChatAttachment?>(null)
    val attachment: StateFlow<ChatAttachment?> = _attachment.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _speakEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val speakEvents: SharedFlow<String> = _speakEvents.asSharedFlow()

    private val _stagedVoicePrompt = MutableStateFlow<String?>(null)
    val stagedVoicePrompt: StateFlow<String?> = _stagedVoicePrompt.asStateFlow()

    private val _startupGreetingVisible = MutableStateFlow(true)
    val startupGreetingVisible: StateFlow<Boolean> = _startupGreetingVisible.asStateFlow()

    private val _voiceNotices = MutableStateFlow<List<String>>(emptyList())
    val voiceNotices: StateFlow<List<String>> = _voiceNotices.asStateFlow()

    private var hasTriggeredStartupVoiceGreeting: Boolean = false
    private var lastAssistantReplyText: String = ""

    val localSettings: StateFlow<LocalUiSettings> = dataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalUiSettings())

    val conversationsState: StateFlow<DataUiState<List<Conversation>>> = repository.observeConversations()
        .map<List<Conversation>, DataUiState<List<Conversation>>> { DataUiState.Success(it) }
        .catch { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Error observing local conversations", error)
            emit(DataUiState.Error(error.message ?: "گفتگو لوڈ کرنے میں خرابی پیش آئی۔"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Loading)

    @OptIn(ExperimentalCoroutinesApi::class)
    val messagesState: StateFlow<DataUiState<List<ChatMessage>>> = _activeConversationId
        .flatMapLatest { convId ->
            if (convId == null) {
                flowOf(DataUiState.Success(emptyList()))
            } else {
                repository.observeMessages(convId)
                    .map<List<ChatMessage>, DataUiState<List<ChatMessage>>> { DataUiState.Success(it) }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        Log.w(TAG, "Error observing messages", error)
                        emit(DataUiState.Error(error.message ?: "پیغامات لوڈ کرنے میں خرابی۔"))
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), DataUiState.Success(emptyList()))

    fun selectConversation(conversationId: String) {
        _activeConversationId.value = conversationId
        _chatError.value = null
        lastAssistantReplyText = ""
        dismissStartupGreeting()
        _voiceNotices.value = emptyList()
    }

    fun startNewConversation() {
        _activeConversationId.value = null
        _chatError.value = null
        lastAssistantReplyText = ""
        _voiceNotices.value = emptyList()
    }

    fun setAttachment(attachment: ChatAttachment?) {
        _attachment.value = attachment
    }

    fun clearError() {
        _chatError.value = null
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun dismissStartupGreeting() {
        _startupGreetingVisible.value = false
        hasTriggeredStartupVoiceGreeting = true
    }

    fun consumeStartupVoiceGreetingNeeded(): Boolean {
        if (hasTriggeredStartupVoiceGreeting || !_startupGreetingVisible.value) return false
        val hasMessages = (messagesState.value as? DataUiState.Success)?.data?.isNotEmpty() == true
        if (hasMessages) {
            hasTriggeredStartupVoiceGreeting = true
            return false
        }
        hasTriggeredStartupVoiceGreeting = true
        return true
    }

    fun postVoiceNoticeInChat(message: String) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return
        val current = _voiceNotices.value
        if (current.lastOrNull() != trimmed) {
            _voiceNotices.value = (current + trimmed).takeLast(4)
        }
    }

    fun clearVoiceNotices() {
        _voiceNotices.value = emptyList()
    }

    fun updateSettings(transform: (LocalUiSettings) -> LocalUiSettings) {
        viewModelScope.launch {
            val current = dataStore.settingsFlow.first()
            dataStore.updateSettings(transform(current))
        }
    }

    fun stageVoiceInput(
        spokenText: String,
        lastSpokenText: String = ""
    ): Boolean {
        dismissStartupGreeting()
        val cleaned = spokenText.trim()
        val lastAssistantReply = (messagesState.value as? DataUiState.Success)?.data.orEmpty()
            .lastOrNull { it.role == "assistant" }
            ?.content
            .orEmpty()
            .ifBlank { lastAssistantReplyText }

        if (cleaned.isBlank() ||
            !cleaned.any { it.isLetterOrDigit() } ||
            VoiceAssistantManager.isSelfEchoOrNoise(
                recognizedText = cleaned,
                lastAssistantReply = lastAssistantReply,
                lastSpokenText = lastSpokenText
            )
        ) {
            _stagedVoicePrompt.value = null
            postVoiceNoticeInChat(VoiceAssistantManager.RETRY_SPEECH_PROMPT)
            return false
        }

        _voiceNotices.value = emptyList()
        _stagedVoicePrompt.value = cleaned
        return true
    }

    fun consumeStagedVoicePrompt() {
        _stagedVoicePrompt.value = null
    }

    fun sendUserMessage(
        rawText: String,
        phoneAppBridge: PhoneAppBridge
    ) {
        val trimmed = rawText.trim()
        val currentAttachment = _attachment.value
        val hasMeaningfulText = trimmed.isNotBlank() && trimmed.any { it.isLetterOrDigit() }
        if (!hasMeaningfulText && currentAttachment == null) return
        if (_isGenerating.value) return
        dismissStartupGreeting()
        _voiceNotices.value = emptyList()

        val promptToSend = if (hasMeaningfulText) trimmed else {
            "اس منسلک ${if (currentAttachment?.bitmap != null) "تصویر" else "فائل (${currentAttachment?.fileName})"} کا تجزیہ کریں۔"
        }

        val currentSettings = localSettings.value
        val activeLang = AssistantLanguage.fromStorageValue(currentSettings.preferredLanguage)
        val priorMessages = (messagesState.value as? DataUiState.Success)?.data.orEmpty()
            .map { it.role to it.content }

        // Handle app-opening commands locally via openApp(query) without sending to Gemini.
        if (currentAttachment == null && phoneAppBridge.isAppOpenIntent(promptToSend)) {
            _chatError.value = null
            viewModelScope.launch {
                val convId = _activeConversationId.value ?: repository.createConversation(
                    title = promptToSend.take(48),
                    language = activeLang.storageValue,
                    initialPreview = promptToSend
                ).also { _activeConversationId.value = it }

                val shouldUpdateTitle = priorMessages.isEmpty()
                repository.addMessage(
                    conversationId = convId,
                    role = "user",
                    content = promptToSend,
                    language = activeLang.storageValue,
                    updateConversationTitle = if (shouldUpdateTitle) promptToSend.take(48) else null
                )

                val launchResult = phoneAppBridge.openAppWithResult(promptToSend)
                repository.addMessage(
                    conversationId = convId,
                    role = "assistant",
                    content = launchResult.verificationMessage,
                    language = activeLang.storageValue,
                    actionType = if (launchResult.success) "open_app" else null,
                    actionTarget = launchResult.matchedPackageName,
                    actionStatus = if (launchResult.success) "launched" else null
                )
                _statusMessage.value = launchResult.verificationMessage
                lastAssistantReplyText = launchResult.verificationMessage
                _speakEvents.emit(launchResult.verificationMessage)
            }
            return
        }

        // Answer direct date, day, and time questions immediately from the device clock.
        val directDateTimeReply = if (currentAttachment == null) {
            DeviceDateTimeHelper.resolveDirectDateTimeQuery(promptToSend, activeLang)
        } else {
            null
        }
        if (directDateTimeReply != null) {
            _chatError.value = null
            viewModelScope.launch {
                val convId = _activeConversationId.value ?: repository.createConversation(
                    title = promptToSend.take(48),
                    language = activeLang.storageValue,
                    initialPreview = promptToSend
                ).also { _activeConversationId.value = it }

                val shouldUpdateTitle = priorMessages.isEmpty()
                repository.addMessage(
                    conversationId = convId,
                    role = "user",
                    content = promptToSend,
                    language = activeLang.storageValue,
                    updateConversationTitle = if (shouldUpdateTitle) promptToSend.take(48) else null
                )
                repository.addMessage(
                    conversationId = convId,
                    role = "assistant",
                    content = directDateTimeReply,
                    language = activeLang.storageValue
                )
                lastAssistantReplyText = directDateTimeReply
                _speakEvents.emit(directDateTimeReply)
            }
            return
        }

        val bridgeAction = phoneAppBridge.handlePromptCommand(promptToSend)

        viewModelScope.launch {
            _isGenerating.value = true
            _chatError.value = null
            _attachment.value = null

            try {
                // Read and trim the GEMINI_API_KEY constant from code
                val apiKey = resolveGeminiApiKey(apiKeyProvider())
                if (apiKey.isBlank()) {
                    val missingKeyMsg = "Gemini API key خالی ہے، براہِ کرم AI Studio کے Secrets پینل یا کوڈ میں GEMINI_API_KEY سیٹ کریں۔"
                    _chatError.value = missingKeyMsg
                    _speakEvents.emit(missingKeyMsg)
                    return@launch
                }

                val convId = _activeConversationId.value ?: repository.createConversation(
                    title = promptToSend.take(48),
                    language = activeLang.storageValue,
                    initialPreview = promptToSend
                ).also { _activeConversationId.value = it }

                val shouldUpdateTitle = priorMessages.isEmpty()
                repository.addMessage(
                    conversationId = convId,
                    role = "user",
                    content = promptToSend,
                    language = activeLang.storageValue,
                    attachmentName = currentAttachment?.fileName,
                    updateConversationTitle = if (shouldUpdateTitle) promptToSend.take(48) else null
                )

                val studioShortcut = bridgeAction as? ImmediatePromptAction.StudioShortcut

                _streamingReply.value = ""
                var hasVisibleTokens = false
                val fullResponse = StringBuilder()
                aiService.streamChatReply(
                    apiKey = apiKey,
                    history = priorMessages,
                    userPrompt = promptToSend,
                    language = activeLang,
                    attachedBitmap = currentAttachment?.bitmap,
                    attachedTextFileName = currentAttachment?.fileName,
                    attachedTextContent = currentAttachment?.textContent,
                    onRetryStatus = { retryStatus ->
                        if (!hasVisibleTokens) {
                            _streamingReply.value = retryStatus
                        }
                    }
                ).collect { accumulated ->
                    hasVisibleTokens = true
                    fullResponse.clear()
                    fullResponse.append(accumulated)
                    _streamingReply.value = accumulated
                }

                val finalReply = fullResponse.toString().trim()
                if (finalReply.isNotBlank()) {
                    repository.addMessage(
                        conversationId = convId,
                        role = "assistant",
                        content = finalReply,
                        language = activeLang.storageValue,
                        actionType = studioShortcut?.actionType,
                        actionTarget = studioShortcut?.actionTarget,
                        actionStatus = if (studioShortcut != null) "ready" else null
                    )
                    _streamingReply.value = null
                    lastAssistantReplyText = finalReply
                    _speakEvents.emit(finalReply)
                } else {
                    _streamingReply.value = null
                    _chatError.value = "Jarvis کو خالی جواب موصول ہوا۔"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process Jarvis chat message", e)
                _streamingReply.value = null
                val errMsg = e.localizedMessage ?: "جواب حاصل کرنے میں خرابی پیش آئی۔"
                _chatError.value = errMsg
                _speakEvents.emit(errMsg)
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun clearCurrentChat() {
        val convId = _activeConversationId.value ?: return
        lastAssistantReplyText = ""
        _voiceNotices.value = emptyList()
        viewModelScope.launch {
            try {
                repository.clearConversationMessages(convId)
                _statusMessage.value = "گفتگو صاف کر دی گئی ہے۔"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _chatError.value = e.localizedMessage ?: "گفتگو صاف نہیں ہو سکی۔"
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            try {
                repository.deleteConversation(conversationId)
                if (_activeConversationId.value == conversationId) {
                    _activeConversationId.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _chatError.value = e.localizedMessage ?: "گفتگو حذف نہیں ہو سکی۔"
            }
        }
    }

    companion object {
        private const val TAG = "JarvisViewModel"

        fun provideFactory(
            context: Context,
            dataStore: PreferencesDataStore
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val dao = AppDatabase.getInstance(context).jarvisDao()
                JarvisViewModel(
                    repository = JarvisRepository(dao),
                    aiService = JarvisAiService(),
                    dataStore = dataStore
                )
            }
        }
    }
}
