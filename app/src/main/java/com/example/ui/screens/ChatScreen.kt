package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ai.resolveGeminiApiKey
import com.example.data.model.AssistantLanguage
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.device.PhoneAppBridge
import com.example.device.VoiceAssistantManager
import com.example.ui.components.MyraCoreMode
import com.example.ui.components.MyraCoreOrb
import com.example.ui.components.VoiceWaveformStrip
import com.example.ui.theme.MyraCyanBright
import com.example.ui.theme.MyraCyanPrimary
import com.example.ui.theme.MyraVioletGlow
import com.example.ui.theme.MyraVioletSecondary
import com.example.ui.viewmodel.ChatAttachment
import com.example.ui.viewmodel.DataUiState
import com.example.ui.viewmodel.JarvisViewModel
import com.example.util.ClipboardHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: JarvisViewModel,
    voiceManager: VoiceAssistantManager,
    phoneAppBridge: PhoneAppBridge,
    snackbarHostState: SnackbarHostState,
    onNavigateToSettings: () -> Unit,
    onNavigateToWebBuilder: (String) -> Unit,
    onNavigateToDesignStudio: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by viewModel.localSettings.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val conversationsState by viewModel.conversationsState.collectAsStateWithLifecycle()
    val activeConvId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val streamingReply by viewModel.streamingReply.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val chatError by viewModel.chatError.collectAsStateWithLifecycle()
    val attachment by viewModel.attachment.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val stagedVoicePrompt by viewModel.stagedVoicePrompt.collectAsStateWithLifecycle()
    val startupGreetingVisible by viewModel.startupGreetingVisible.collectAsStateWithLifecycle()
    val voiceNotices by viewModel.voiceNotices.collectAsStateWithLifecycle()

    val isListening by voiceManager.isListening.collectAsStateWithLifecycle()
    val isLiveConversation by voiceManager.isLiveConversationActive.collectAsStateWithLifecycle()
    val isProcessingSpeech by voiceManager.isProcessingSpeech.collectAsStateWithLifecycle()
    val isDirectRecordingMode by voiceManager.isDirectRecordingMode.collectAsStateWithLifecycle()
    val isSpeaking by voiceManager.isSpeaking.collectAsStateWithLifecycle()
    val rmsLevel by voiceManager.rmsLevel.collectAsStateWithLifecycle()
    val voiceError by voiceManager.voiceError.collectAsStateWithLifecycle()

    var inputField by remember { mutableStateOf(TextFieldValue("")) }
    var pendingVoiceSendText by remember { mutableStateOf<String?>(null) }
    var pendingLiveVoiceAfterPermission by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showLangMenu by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var isMicPermissionDenied by remember { mutableStateOf(false) }
    var isMicPermissionPermanentlyDenied by remember { mutableStateOf(false) }

    val activeLanguage = AssistantLanguage.fromStorageValue(settings.preferredLanguage)
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, voiceManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                voiceManager.stopLiveConversation()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val coreMode = when {
        isListening -> MyraCoreMode.LISTENING
        isGenerating || isProcessingSpeech -> MyraCoreMode.THINKING
        isSpeaking -> MyraCoreMode.SPEAKING
        else -> MyraCoreMode.IDLE
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isMicPermissionDenied = false
            isMicPermissionPermanentlyDenied = false
            voiceManager.clearError()
            if (pendingLiveVoiceAfterPermission) {
                pendingLiveVoiceAfterPermission = false
                if (settings.isMuted) {
                    viewModel.updateSettings { it.copy(isMuted = false) }
                }
                voiceManager.startLiveConversation(activeLanguage)
            } else {
                voiceManager.startListening(activeLanguage)
            }
        } else {
            pendingLiveVoiceAfterPermission = false
            val hostActivity = findActivity(context)
            val permanentlyDenied = hostActivity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    hostActivity,
                    Manifest.permission.RECORD_AUDIO
                )
            isMicPermissionDenied = true
            isMicPermissionPermanentlyDenied = permanentlyDenied
            val deniedMessage = "مائیکروفون کی اجازت نہیں ملی۔ فون کی Settings > Apps > Jarvis > Permissions میں Microphone آن کریں، یا Chrome کے ایڈریس بار میں Lock آئیکن سے Microphone Allow کریں۔"
            voiceManager.reportErrorMessage(deniedMessage)
            viewModel.postVoiceNoticeInChat(deniedMessage)
        }
    }

    val systemVoiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        when (activityResult.resultCode) {
            Activity.RESULT_OK -> {
                val spoken = activityResult.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull { !it.isNullOrBlank() }
                    ?.trim()
                if (!spoken.isNullOrBlank()) {
                    voiceManager.deliverExternalSpeechResult(spoken)
                } else {
                    voiceManager.reportErrorMessage("آواز واضح سنائی نہیں دی، مائیک کے قریب بولیں یا ٹائپ کریں۔")
                }
            }
            RecognizerIntent.RESULT_AUDIO_ERROR,
            RecognizerIntent.RESULT_CLIENT_ERROR,
            RecognizerIntent.RESULT_NETWORK_ERROR,
            RecognizerIntent.RESULT_NO_MATCH,
            RecognizerIntent.RESULT_SERVER_ERROR -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    voiceManager.startDirectRecording(activeLanguage)
                } else {
                    voiceManager.reportErrorMessage("وائس سروس دستیاب نہیں ہوئی، مائیک ریکارڈنگ کے لیے اجازت دیں۔")
                }
            }
            else -> {
                // User cancelled the system voice sheet; reset cleanly without stale error
                voiceManager.cancelListening()
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val bmp = decodeScaledBitmap(context, uri)
            if (bmp != null) {
                viewModel.setAttachment(
                    ChatAttachment(
                        fileName = queryFileName(context, uri) ?: "image.jpg",
                        bitmap = bmp
                    )
                )
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar("منتخب تصویر لوڈ نہیں ہو سکی۔")
                }
            }
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = queryFileName(context, uri) ?: "document.txt"
            val content = readTextFromUri(context, uri)
            if (content != null) {
                viewModel.setAttachment(
                    ChatAttachment(
                        fileName = name,
                        textContent = content
                    )
                )
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar("منتخب فائل ٹیکسٹ یا کوڈ کے طور پر پڑھی نہیں جا سکی۔")
                }
            }
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    LaunchedEffect(voiceManager) {
        voiceManager.systemVoiceFallbackRequests.collect { fallbackEvent ->
            val hasRecordPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val voiceIntent = voiceManager.buildSpeechRecognizerIntent(fallbackEvent.language).apply {
                putExtra(RecognizerIntent.EXTRA_PROMPT, "اب بولیں (Speak now)...")
            }
            val canLaunchSystemVoice = try {
                voiceIntent.resolveActivity(context.packageManager) != null
            } catch (_: Exception) {
                false
            }
            if (hasRecordPermission) {
                // Prefer reliable direct audio capture when inline SpeechRecognizer hits ERROR_NO_MATCH/client/audio failure or no system activity exists
                if (fallbackEvent.preferDirectRecorder || !canLaunchSystemVoice) {
                    if (fallbackEvent.fallbackErrorMessage.startsWith("ERROR_")) {
                        val code = fallbackEvent.fallbackErrorMessage.substringBefore(':')
                        viewModel.postVoiceNoticeInChat("$code کے بعد Direct AI Mic چالو ہے — اب صاف آواز میں بولیں۔")
                    }
                    voiceManager.startDirectRecording(fallbackEvent.language)
                } else {
                    try {
                        systemVoiceLauncher.launch(voiceIntent)
                    } catch (_: Exception) {
                        voiceManager.startDirectRecording(fallbackEvent.language)
                    }
                }
            } else {
                voiceManager.reportErrorMessage(fallbackEvent.fallbackErrorMessage)
            }
        }
    }

    LaunchedEffect(voiceError) {
        val err = voiceError ?: return@LaunchedEffect
        if (err.startsWith("ERROR_") ||
            err == VoiceAssistantManager.RETRY_SPEECH_PROMPT ||
            err == VoiceAssistantManager.LIVE_PAUSED_EMPTY_NOTICE
        ) {
            viewModel.postVoiceNoticeInChat(err)
        }
    }

    val messages = (messagesState as? DataUiState.Success)?.data.orEmpty()
    LaunchedEffect(messages.size, streamingReply, voiceNotices.size) {
        if (messages.isNotEmpty() || streamingReply != null) {
            val totalItems = messages.size + (if (streamingReply != null) 1 else 0) + voiceNotices.size
            val targetIndex = (totalItems - 1).coerceAtLeast(0)
            listState.animateScrollToItem(targetIndex)
        }
    }

    // Show final recognized speech in the chat input box before passing it to the AI chat system
    LaunchedEffect(stagedVoicePrompt) {
        val recognized = stagedVoicePrompt ?: return@LaunchedEffect
        viewModel.consumeStagedVoicePrompt()
        val trimmed = recognized.trim()
        if (trimmed.isNotBlank() && trimmed.any { it.isLetterOrDigit() }) {
            inputField = TextFieldValue(text = trimmed, selection = TextRange(trimmed.length))
            pendingVoiceSendText = trimmed
        }
    }

    LaunchedEffect(pendingVoiceSendText) {
        val targetPrompt = pendingVoiceSendText ?: return@LaunchedEffect
        delay(650L)
        if (pendingVoiceSendText == targetPrompt &&
            inputField.text.trim() == targetPrompt &&
            targetPrompt.any { it.isLetterOrDigit() } &&
            !isGenerating
        ) {
            pendingVoiceSendText = null
            inputField = TextFieldValue("")
            viewModel.sendUserMessage(
                rawText = targetPrompt,
                phoneAppBridge = phoneAppBridge
            )
        }
    }

    fun toggleTapToTalk() {
        viewModel.dismissStartupGreeting()
        if (isProcessingSpeech) {
            scope.launch {
                snackbarHostState.showSnackbar("آواز کو متن میں بدلا جا رہا ہے، براہ کرم انتظار کریں...")
            }
            return
        }
        if (isListening) {
            voiceManager.stopListening()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            isMicPermissionDenied = false
            isMicPermissionPermanentlyDenied = false
            pendingLiveVoiceAfterPermission = false
            viewModel.clearVoiceNotices()
            voiceManager.clearError()
            // Start listening silently without playing any greeting or reply
            voiceManager.startListening(activeLanguage)
        } else {
            pendingLiveVoiceAfterPermission = false
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun toggleLiveVoiceMode() {
        viewModel.dismissStartupGreeting()
        if (isLiveConversation) {
            pendingVoiceSendText = null
            voiceManager.stopLiveConversation()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            isMicPermissionDenied = false
            isMicPermissionPermanentlyDenied = false
            pendingLiveVoiceAfterPermission = false
            viewModel.clearVoiceNotices()
            voiceManager.clearError()
            if (settings.isMuted) {
                viewModel.updateSettings { it.copy(isMuted = false) }
            }
            voiceManager.startLiveConversation(activeLanguage)
        } else {
            pendingLiveVoiceAfterPermission = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun dispatchPrompt(textToSend: String) {
        viewModel.sendUserMessage(
            rawText = textToSend,
            phoneAppBridge = phoneAppBridge
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Futuristic Jarvis Top HUD Bar
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    MyraCoreOrb(
                        mode = coreMode,
                        rmsLevel = rmsLevel,
                        size = 44.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Jarvis",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val statusLabel = when {
                            isListening && isLiveConversation && isDirectRecordingMode -> "Live Voice • Sun raha hoon... (ریکارڈنگ)"
                            isListening && isLiveConversation -> "Live Voice • Sun raha hoon..."
                            isListening && isDirectRecordingMode -> "Sun raha hoon... (ریکارڈنگ جاری)"
                            isListening -> "Sun raha hoon..."
                            isProcessingSpeech -> "آواز متن میں بدل رہا ہے..."
                            isGenerating -> "جواب تیار کر رہا ہے..."
                            isSpeaking && isLiveConversation -> "Live Voice • بول رہا ہے..."
                            isSpeaking -> "بول رہا ہے..."
                            isLiveConversation -> "Live Voice فعال • اگلی باری تیار"
                            else -> "آن لائن • ${activeLanguage.nativeHint}"
                        }
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (coreMode) {
                                MyraCoreMode.LISTENING -> MyraCyanBright
                                MyraCoreMode.THINKING -> MyraVioletGlow
                                MyraCoreMode.SPEAKING -> MaterialTheme.colorScheme.tertiary
                                MyraCoreMode.IDLE -> MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Language selector
                    Box {
                        IconButton(
                            onClick = { showLangMenu = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("language_selector_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Language,
                                contentDescription = "زبان منتخب کریں",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = showLangMenu,
                            onDismissRequest = { showLangMenu = false }
                        ) {
                            AssistantLanguage.entries.forEach { lang ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(lang.displayLabel)
                                            if (lang == activeLanguage) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = "Selected",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        showLangMenu = false
                                        viewModel.updateSettings { it.copy(preferredLanguage = lang.storageValue) }
                                    }
                                )
                            }
                        }
                    }

                    // Mute / Unmute voice output
                    IconButton(
                        onClick = {
                            val nextMuted = !settings.isMuted
                            if (nextMuted) voiceManager.stopSpeaking()
                            viewModel.updateSettings { it.copy(isMuted = nextMuted) }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("mute_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (settings.isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                            contentDescription = if (settings.isMuted) "Jarvis کی آواز آن کریں" else "Jarvis کی آواز میوٹ کریں",
                            tint = if (settings.isMuted) MaterialTheme.colorScheme.onSurfaceVariant else MyraCyanPrimary
                        )
                    }

                    // Conversation history
                    IconButton(
                        onClick = { showHistorySheet = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = "پرانی گفتگو"
                        )
                    }

                    // Clear chat
                    if (activeConvId != null && messages.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearDialog = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("clear_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = "گفتگو صاف کریں"
                            )
                        }
                    }
                }
            }
        }

        // Two-way Live Voice Conversation Control Bar
        Surface(
            onClick = { toggleLiveVoiceMode() },
            color = if (isLiveConversation) {
                MyraVioletSecondary.copy(alpha = 0.24f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            },
            border = BorderStroke(
                width = 1.dp,
                color = if (isLiveConversation) MyraCyanBright else MyraCyanPrimary.copy(alpha = 0.35f)
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .minimumInteractiveComponentSize()
                .testTag("live_voice_mode_button")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = if (isLiveConversation) "Live Voice فعال ہے" else "Live Voice شروع کریں",
                        tint = if (isLiveConversation) MyraCyanBright else MyraCyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isLiveConversation) {
                            "Live Voice فعال • دو طرفہ بات چیت جاری"
                        } else {
                            "Live Voice • دو طرفہ لائیو آواز شروع کریں"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isLiveConversation) MyraCyanBright else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (isLiveConversation) MaterialTheme.colorScheme.error else MyraCyanPrimary
                ) {
                    Text(
                        text = if (isLiveConversation) "بند کریں" else "شروع",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isLiveConversation) Color.White else Color(0xFF050814),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // API key setup banner if missing in code constant
        AnimatedVisibility(visible = resolveGeminiApiKey().isBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MyraCyanPrimary.copy(alpha = 0.35f)),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag("missing_api_key_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Key,
                        contentDescription = null,
                        tint = MyraCyanBright,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI چیٹ کے لیے AI Studio کے Secrets پینل میں GEMINI_API_KEY شامل کریں (ایپس کھولنے کی کمانڈز فوراً کام کرتی ہیں)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Listening / Processing / Speaking Live Telemetry Strip
        AnimatedVisibility(visible = isListening || isProcessingSpeech || isSpeaking || isLiveConversation) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    VoiceWaveformStrip(
                        isActive = true,
                        rmsLevel = when {
                            isListening -> rmsLevel
                            isProcessingSpeech -> 0.4f
                            isSpeaking -> 0.55f
                            else -> 0.25f
                        },
                        label = when {
                            isListening && isLiveConversation -> "Live Voice • Sun raha hoon..."
                            isListening && isDirectRecordingMode -> "Sun raha hoon... اب بولیں (خودکار یا Stop)"
                            isListening -> "Sun raha hoon..."
                            isProcessingSpeech -> "آواز کو متن میں بدلا جا رہا ہے..."
                            isSpeaking && isLiveConversation -> "Live Voice • Jarvis بول رہا ہے..."
                            isSpeaking -> "Jarvis بول رہا ہے (بند کرنے کے لیے میوٹ دبائیں)"
                            else -> "Live Voice فعال • اگلی باری سننے کے لیے تیار"
                        }
                    )
                }
                if (isListening || isLiveConversation) {
                    TextButton(
                        onClick = {
                            if (isLiveConversation) {
                                voiceManager.stopLiveConversation()
                            } else {
                                voiceManager.cancelListening()
                            }
                        },
                        modifier = Modifier.testTag("cancel_voice_button")
                    ) {
                        Text(if (isLiveConversation) "بند کریں" else "منسوخ", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Microphone / Voice Error Banner
        AnimatedVisibility(visible = !voiceError.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("voice_error_banner"),
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = voiceError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (isMicPermissionDenied) {
                        TextButton(
                            onClick = {
                                if (isMicPermissionPermanentlyDenied) {
                                    val settingsIntent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    ).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    try {
                                        context.startActivity(settingsIntent)
                                    } catch (_: Exception) {
                                    }
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.testTag("mic_permission_action_button")
                        ) {
                            Text(if (isMicPermissionPermanentlyDenied) "سیٹنگز" else "اجازت دیں")
                        }
                    }
                    IconButton(onClick = {
                        isMicPermissionDenied = false
                        voiceManager.clearError()
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "وائس خرابی بند کریں",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Error Banner
        AnimatedVisibility(visible = !chatError.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("chat_error_banner"),
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = chatError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "خرابی بند کریں",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Main Chat Area or Hero Core Welcome
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty() && streamingReply == null) {
                HeroWelcomeArea(
                    coreMode = coreMode,
                    isListening = isListening,
                    isProcessingSpeech = isProcessingSpeech,
                    rmsLevel = rmsLevel,
                    activeLanguage = activeLanguage,
                    startupGreetingVisible = startupGreetingVisible,
                    voiceNotices = voiceNotices,
                    onQuickPrompt = { prompt -> dispatchPrompt(prompt) },
                    onTapToTalk = { toggleTapToTalk() }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatMessageBubble(
                            message = msg,
                            onCopy = {
                                ClipboardHelper.writeText(context, "Jarvis Response", msg.content)
                                scope.launch { snackbarHostState.showSnackbar("جواب کاپی ہو گیا") }
                            },
                            onSpeak = {
                                if (isSpeaking) {
                                    voiceManager.stopSpeaking()
                                } else {
                                    voiceManager.speak(
                                        rawText = msg.content,
                                        language = activeLanguage,
                                        speechRate = settings.voiceSpeechRate,
                                        pitch = settings.voicePitch,
                                        selectedVoiceName = settings.selectedVoiceName,
                                        isMuted = false
                                    )
                                }
                            },
                            onLaunchWebBuilder = { onNavigateToWebBuilder(msg.actionTarget.orEmpty()) },
                            onLaunchDesignStudio = { onNavigateToDesignStudio(msg.actionTarget.orEmpty()) }
                        )
                    }

                    if (streamingReply != null) {
                        item(key = "streaming_assistant_bubble") {
                            StreamingAssistantBubble(text = streamingReply.orEmpty())
                        }
                    }

                    itemsIndexed(voiceNotices, key = { index, notice -> "voice_notice_${index}_$notice" }) { _, notice ->
                        VoiceNoticeBubble(notice = notice)
                    }
                }
            }
        }

        // Attachment Preview Chip
        AnimatedVisibility(visible = attachment != null) {
            attachment?.let { att ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MyraCyanPrimary.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (att.bitmap != null) {
                            Image(
                                bitmap = att.bitmap.asImageBitmap(),
                                contentDescription = "منسلک تصویر",
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AttachFile,
                                contentDescription = null,
                                tint = MyraCyanPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = att.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.setAttachment(null) }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "فائل ہٹائیں"
                            )
                        }
                    }
                }
            }
        }

        // Futuristic Composer Bar with Tap-to-Talk Mic Button
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    IconButton(
                        onClick = { showAttachMenu = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("attach_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AttachFile,
                            contentDescription = "تصویر یا فائل منسلک کریں",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    DropdownMenu(
                        expanded = showAttachMenu,
                        onDismissRequest = { showAttachMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("تصویر منسلک کریں") },
                            leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                            onClick = {
                                showAttachMenu = false
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("کوڈ / ٹیکسٹ فائل منسلک کریں") },
                            leadingIcon = { Icon(Icons.Rounded.AttachFile, contentDescription = null) },
                            onClick = {
                                showAttachMenu = false
                                documentPickerLauncher.launch(arrayOf("text/*", "application/json", "application/javascript"))
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = inputField,
                    onValueChange = { nextValue ->
                        pendingVoiceSendText = null
                        if (nextValue.text != inputField.text && isLiveConversation) {
                            voiceManager.setLiveConversationActive(false)
                        }
                        inputField = nextValue
                    },
                    placeholder = {
                        Text(
                            text = "Jarvis سے اردو یا انگریزی میں پوچھیں...",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MyraCyanPrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field")
                )

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = {
                        val pasted = ClipboardHelper.readText(context)
                        if (!pasted.isNullOrEmpty()) {
                            inputField = if (inputField.text.isEmpty()) {
                                TextFieldValue(text = pasted, selection = TextRange(pasted.length))
                            } else {
                                ClipboardHelper.insertAtSelection(inputField, pasted)
                            }
                            scope.launch { snackbarHostState.showSnackbar("کلپ بورڈ سے پیسٹ ہو گیا") }
                        } else {
                            scope.launch { snackbarHostState.showSnackbar("کلپ بورڈ خالی ہے") }
                        }
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("chat_paste_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = "Paste",
                        tint = MyraCyanPrimary
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Tap-to-Talk Voice Mic Button
                FilledIconButton(
                    onClick = { toggleTapToTalk() },
                    enabled = !isProcessingSpeech,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isListening) MyraVioletGlow else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isListening) Color.White else MyraCyanPrimary
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("voice_mic_button")
                ) {
                    if (isProcessingSpeech) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MyraCyanPrimary
                        )
                    } else {
                        Icon(
                            imageVector = if (isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                            contentDescription = if (isListening) "ریکارڈنگ بند کریں" else "Tap to talk"
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Send Button
                FilledIconButton(
                    onClick = {
                        val prompt = inputField.text.trim()
                        val hasText = prompt.isNotBlank() && prompt.any { it.isLetterOrDigit() }
                        if (hasText || attachment != null) {
                            pendingVoiceSendText = null
                            inputField = TextFieldValue("")
                            dispatchPrompt(prompt)
                        }
                    },
                    enabled = !isGenerating && ((inputField.text.isNotBlank() && inputField.text.any { it.isLetterOrDigit() }) || attachment != null),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MyraCyanPrimary,
                        contentColor = Color(0xFF050814)
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("send_message_button")
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF050814)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "بھیجیں"
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("موجودہ گفتگو صاف کریں؟") },
            text = { Text("اس سیشن کے تمام پیغامات فون کی ہسٹری سے حذف ہو جائیں گے۔") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearCurrentChat()
                    }
                ) { Text("صاف کریں", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("منسوخ") }
            }
        )
    }

    if (showHistorySheet) {
        ModalBottomSheet(onDismissRequest = { showHistorySheet = false }) {
            ConversationHistorySheet(
                conversationsState = conversationsState,
                activeConvId = activeConvId,
                onNewChat = {
                    viewModel.startNewConversation()
                    showHistorySheet = false
                },
                onSelectConversation = { id ->
                    viewModel.selectConversation(id)
                    showHistorySheet = false
                },
                onDeleteConversation = { id ->
                    viewModel.deleteConversation(id)
                }
            )
        }
    }
}

@Composable
private fun HeroWelcomeArea(
    coreMode: MyraCoreMode,
    isListening: Boolean,
    isProcessingSpeech: Boolean,
    rmsLevel: Float,
    activeLanguage: AssistantLanguage,
    startupGreetingVisible: Boolean,
    voiceNotices: List<String>,
    onQuickPrompt: (String) -> Unit,
    onTapToTalk: () -> Unit
) {
    val quickPrompts = listOf(
        "Telegram کھولو",
        "ٹیلیگرام کھولو",
        "YouTube کھولو",
        "Jarvis، میرے کاروبار کے لیے ویب سائٹ بناؤ",
        "میرے برانڈ کے لیے جدید لوگو ڈیزائن کرو",
        "مصنوعی ذہانت کے بارے میں اردو میں بتاؤ"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (startupGreetingVisible) {
            item(key = "startup_greeting_item") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, MyraCyanPrimary.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .testTag("startup_greeting_card")
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "JARVIS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MyraVioletGlow
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = VoiceAssistantManager.STARTUP_GREETING,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        if (voiceNotices.isNotEmpty()) {
            itemsIndexed(voiceNotices, key = { index, notice -> "hero_voice_notice_${index}_$notice" }) { _, notice ->
                VoiceNoticeBubble(notice = notice)
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            MyraCoreOrb(
                mode = coreMode,
                rmsLevel = rmsLevel,
                size = 172.dp,
                onClick = onTapToTalk,
                modifier = Modifier.testTag("jarvis_center_orb")
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Explicit Tap-to-Talk Voice Button
            Button(
                onClick = onTapToTalk,
                enabled = !isProcessingSpeech,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListening) MyraVioletGlow else MyraCyanPrimary,
                    contentColor = if (isListening) Color.White else Color(0xFF050814)
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("tap_to_talk_button")
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isProcessingSpeech -> "آواز کو متن میں بدلا جا رہا ہے..."
                        isListening -> "Sun raha hoon... بند کرنے کے لیے دبائیں"
                        else -> "Tap to Talk • آواز سے بات کریں"
                    },
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Jarvis ذاتی اسسٹنٹ • ${activeLanguage.nativeHint}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "فوری کمانڈز (QUICK COMMANDS)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        items(quickPrompts) { prompt ->
            Surface(
                onClick = { onQuickPrompt(prompt) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                border = BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            MyraCyanPrimary.copy(alpha = 0.35f),
                            MyraVioletSecondary.copy(alpha = 0.35f)
                        )
                    )
                ),
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onLaunchWebBuilder: () -> Unit,
    onLaunchDesignStudio: () -> Unit
) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)
                }
            ),
            border = BorderStroke(
                width = 1.dp,
                color = if (isUser) MyraCyanPrimary.copy(alpha = 0.45f) else MyraVioletSecondary.copy(alpha = 0.32f)
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isUser) "آپ" else "JARVIS",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser) MyraCyanBright else MyraVioletGlow
                    )
                    if (!message.attachmentName.isNullOrBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = message.attachmentName,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Instant App Launch Status or Studio Shortcut (NO confirmation prompts)
                if (!isUser && !message.actionType.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    ActionBridgeCard(
                        actionType = message.actionType,
                        actionTarget = message.actionTarget.orEmpty(),
                        actionStatus = message.actionStatus,
                        onLaunchWebBuilder = onLaunchWebBuilder,
                        onLaunchDesignStudio = onLaunchDesignStudio
                    )
                }

                if (!isUser) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onCopy,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("copy_response_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = "کاپی کریں",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(
                            onClick = onSpeak,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("speak_response_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = "سنیں",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("سنیں", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionBridgeCard(
    actionType: String,
    actionTarget: String,
    actionStatus: String?,
    onLaunchWebBuilder: () -> Unit,
    onLaunchDesignStudio: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MyraCyanPrimary.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when (actionType) {
                "open_app" -> {
                    val succeeded = actionStatus == "launched"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (succeeded) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = if (succeeded) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (succeeded) {
                                "ایپ فوری طور پر کھول دی گئی${if (actionTarget.isNotBlank()) " • $actionTarget" else ""}"
                            } else {
                                "ایپ لانچ نہیں ہو سکی"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (succeeded) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                        )
                    }
                }
                "website_builder" -> {
                    Button(
                        onClick = onLaunchWebBuilder,
                        colors = ButtonDefaults.buttonColors(containerColor = MyraVioletSecondary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Web, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Web Studio کھولیں")
                    }
                }
                "design_studio" -> {
                    Button(
                        onClick = onLaunchDesignStudio,
                        colors = ButtonDefaults.buttonColors(containerColor = MyraVioletSecondary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Design Studio کھولیں")
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceNoticeBubble(notice: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .testTag("voice_chat_notice_card"),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, MyraVioletGlow.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = MyraCyanBright,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun StreamingAssistantBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 560.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
            ),
            border = BorderStroke(1.dp, MyraCyanPrimary.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "JARVIS • جواب لکھ رہا ہے",
                        style = MaterialTheme.typography.labelSmall,
                        color = MyraCyanBright
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MyraCyanBright
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        text = text.ifBlank { "سوچ رہا ہے..." },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationHistorySheet(
    conversationsState: DataUiState<List<Conversation>>,
    activeConvId: String?,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "گفتگو کی تاریخ",
                style = MaterialTheme.typography.headlineSmall
            )
            Button(onClick = onNewChat) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("نئی گفتگو")
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        when (conversationsState) {
            is DataUiState.Loading -> {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is DataUiState.Error -> {
                Text(
                    text = conversationsState.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            is DataUiState.Success -> {
                if (conversationsState.data.isEmpty()) {
                    Text(
                        text = "ابھی کوئی محفوظ گفتگو نہیں ہے۔ Jarvis سے بات شروع کریں!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(340.dp)
                    ) {
                        items(conversationsState.data, key = { it.id }) { conv ->
                            val isSelected = conv.id == activeConvId
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (isSelected) BorderStroke(1.dp, MyraCyanPrimary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectConversation(conv.id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = conv.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (conv.lastMessagePreview.isNotBlank()) {
                                            Text(
                                                text = conv.lastMessagePreview,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    IconButton(onClick = { onDeleteConversation(conv.id) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "گفتگو حذف کریں"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

private fun decodeScaledBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
        val maxDim = 1280
        if (original.width > maxDim || original.height > maxDim) {
            val ratio = minOf(maxDim.toFloat() / original.width, maxDim.toFloat() / original.height)
            Bitmap.createScaledBitmap(
                original,
                (original.width * ratio).toInt().coerceAtLeast(1),
                (original.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            original
        }
    } catch (_: Exception) {
        null
    }
}

private fun readTextFromUri(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            reader.readText().take(12000)
        }
    } catch (_: Exception) {
        null
    }
}

private fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun queryFileName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }
}
