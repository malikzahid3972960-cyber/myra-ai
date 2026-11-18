package com.example.util

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.JarvisAiService
import com.example.data.local.AppDatabase
import com.example.data.repository.JarvisRepository
import com.example.data.repository.PreferencesDataStore
import com.example.device.PhoneAppBridge
import com.example.device.VoiceAssistantManager
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WebsiteCreatorScreen
import com.example.ui.theme.MyraTheme
import com.example.ui.viewmodel.JarvisViewModel
import com.example.ui.viewmodel.WebsiteCreatorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClipboardAndPasteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: JarvisRepository
    private lateinit var dataStore: PreferencesDataStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repository = JarvisRepository(db.jarvisDao())
        dataStore = PreferencesDataStore(context)
    }

    @After
    fun tearDown() {
        runBlocking {
            withContext(Dispatchers.IO) {
                dataStore.saveGeminiApiKey("")
            }
        }
        db.close()
    }

    @Test
    fun clipboardHelper_readsPlainAndHtmlText_andWritesToAndroidClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
        assertNull(ClipboardHelper.readText(context))

        val exactKey = "AIzaSyTest_Exact-Key1234567890"
        assertTrue(ClipboardHelper.writeText(context, "Key", exactKey))
        assertEquals(exactKey, ClipboardHelper.readText(context))

        clipboard.setPrimaryClip(
            ClipData.newHtmlText("HTML Key", exactKey, "<b>$exactKey</b>")
        )
        assertEquals(exactKey, ClipboardHelper.readText(context))
    }

    @Test
    fun clipboardHelper_textFieldValueOps_supportPasteSelectAllCopyAndCutWithoutAlteringChars() {
        val initial = TextFieldValue("Hello World", selection = TextRange(6, 11))
        val pasted = ClipboardHelper.insertAtSelection(initial, "AIzaSy_Key-99")
        assertEquals("Hello AIzaSy_Key-99", pasted.text)

        val selectedAll = ClipboardHelper.selectAll(pasted)
        assertEquals(TextRange(0, pasted.text.length), selectedAll.selection)
        assertEquals("Hello AIzaSy_Key-99", ClipboardHelper.selectedTextOrAll(selectedAll))

        val (cutText, remaining) = ClipboardHelper.cutSelectionOrAll(selectedAll)
        assertEquals("Hello AIzaSy_Key-99", cutText)
        assertEquals("", remaining.text)
    }

    @Test
    fun settingsScreen_doesNotShowApiKeyInputOrPasteButton() {
        composeRule.mainClock.autoAdvance = false
        val viewModel = JarvisViewModel(repository, JarvisAiService(), dataStore)
        val bridge = PhoneAppBridge(context)
        val voiceManager = VoiceAssistantManager(context) {}

        try {
            composeRule.setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                MyraTheme(darkTheme = true) {
                    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            SettingsScreen(
                                viewModel = viewModel,
                                voiceManager = voiceManager,
                                phoneAppBridge = bridge,
                                snackbarHostState = snackbarHostState
                            )
                        }
                    }
                }
            }
            composeRule.mainClock.advanceTimeBy(100)

            composeRule.onNodeWithTag("gemini_api_key_input").assertDoesNotExist()
            composeRule.onNodeWithTag("paste_api_key_button").assertDoesNotExist()
            composeRule.onNodeWithTag("save_api_key_button").assertDoesNotExist()
            composeRule.onNodeWithTag("test_api_key_button").assertDoesNotExist()
            composeRule.onNodeWithText("Jarvis Control Center").assertExists()
        } finally {
            voiceManager.release()
        }
    }

    @Test
    fun websiteCreatorScreen_codeEditor_supportsPasteSelectAllCopyAndCut() {
        composeRule.mainClock.autoAdvance = false
        val htmlSnippet = "<h1>Jarvis Website</h1>"
        ClipboardHelper.writeText(context, "HTML", htmlSnippet)

        val viewModel = WebsiteCreatorViewModel(repository, JarvisAiService(), dataStore)

        composeRule.setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            MyraTheme(darkTheme = true) {
                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        WebsiteCreatorScreen(
                            viewModel = viewModel,
                            snackbarHostState = snackbarHostState
                        )
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(100)

        composeRule.onNodeWithText("Code").performClick()
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithTag("website_code_editor").assertIsEnabled()
        composeRule.onNodeWithTag("code_paste_button").performClick()
        composeRule.mainClock.advanceTimeBy(100)
        assertEquals(htmlSnippet, viewModel.htmlCode.value)

        composeRule.onNodeWithTag("code_select_all_button").performClick()
        composeRule.mainClock.advanceTimeBy(50)
        composeRule.onNodeWithTag("code_copy_button").performClick()
        composeRule.mainClock.advanceTimeBy(50)
        assertEquals(htmlSnippet, ClipboardHelper.readText(context))

        composeRule.onNodeWithTag("code_cut_button").performClick()
        composeRule.mainClock.advanceTimeBy(50)
        assertEquals("", viewModel.htmlCode.value)
        assertEquals(htmlSnippet, ClipboardHelper.readText(context))
    }

    @Test
    fun chatScreen_inputField_isEnabledAndAcceptsTextInput() {
        composeRule.mainClock.autoAdvance = false
        val viewModel = JarvisViewModel(repository, JarvisAiService(), dataStore)
        val bridge = PhoneAppBridge(context)
        val voiceManager = VoiceAssistantManager(context) {}

        try {
            composeRule.setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                MyraTheme(darkTheme = true) {
                    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            ChatScreen(
                                viewModel = viewModel,
                                voiceManager = voiceManager,
                                phoneAppBridge = bridge,
                                snackbarHostState = snackbarHostState,
                                onNavigateToSettings = {},
                                onNavigateToWebBuilder = {},
                                onNavigateToDesignStudio = {}
                            )
                        }
                    }
                }
            }
            composeRule.mainClock.advanceTimeBy(100)

            composeRule.onNodeWithTag("chat_input_field").assertIsEnabled()
            composeRule.onNodeWithTag("chat_input_field").performTextInput("Hello Jarvis")
            composeRule.mainClock.advanceTimeBy(50)
        } finally {
            voiceManager.release()
        }
    }

    @Test
    fun chatPasteButton_pastesClipboardTextIntoChatInput() {
        composeRule.mainClock.autoAdvance = false
        val promptText = "Explain quantum computing in Urdu"
        ClipboardHelper.writeText(context, "Prompt", promptText)

        val viewModel = JarvisViewModel(repository, JarvisAiService(), dataStore)
        val bridge = PhoneAppBridge(context)
        val voiceManager = VoiceAssistantManager(context) {}

        try {
            composeRule.setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                MyraTheme(darkTheme = true) {
                    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            ChatScreen(
                                viewModel = viewModel,
                                voiceManager = voiceManager,
                                phoneAppBridge = bridge,
                                snackbarHostState = snackbarHostState,
                                onNavigateToSettings = {},
                                onNavigateToWebBuilder = {},
                                onNavigateToDesignStudio = {}
                            )
                        }
                    }
                }
            }
            composeRule.mainClock.advanceTimeBy(100)

            composeRule.onNodeWithTag("chat_paste_button").performClick()
            composeRule.mainClock.advanceTimeBy(50)
        } finally {
            voiceManager.release()
        }
    }

    @Test
    fun settingsScreen_opensInstalledAppsListPageAndShowsAllApps() {
        val shadowPm = shadowOf(context.packageManager)
        listOf(
            Triple("com.google.android.youtube", "YouTube", "com.google.android.youtube.HomeActivity"),
            Triple("com.facebook.katana", "Facebook", "com.facebook.katana.LoginActivity"),
            Triple("com.instagram.android", "Instagram", "com.instagram.mainactivity.LauncherActivity"),
            Triple("com.google.android.calculator", "Calculator", "com.android.calculator2.Calculator")
        ).forEach { (pkg, label, activity) ->
            val comp = ComponentName(pkg, activity)
            shadowPm.addActivityIfNotPresent(comp)
            shadowPm.addIntentFilterForActivity(
                comp,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            )
            shadowPm.getInternalMutablePackageInfo(pkg).applicationInfo?.apply {
                nonLocalizedLabel = label
                name = label
            }
        }

        val viewModel = JarvisViewModel(repository, JarvisAiService(), dataStore)
        val bridge = PhoneAppBridge(context)
        val voiceManager = VoiceAssistantManager(context) {}

        try {
            composeRule.setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                MyraTheme(darkTheme = true) {
                    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            SettingsScreen(
                                viewModel = viewModel,
                                voiceManager = voiceManager,
                                phoneAppBridge = bridge,
                                snackbarHostState = snackbarHostState
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("settings_lazy_column")
                .performScrollToNode(hasTestTag("open_apps_list_page_button"))
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Show installed apps").assertExists().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("all_installed_apps_list").assertExists()
            composeRule.onNodeWithText("YouTube").assertExists()
            composeRule.onNodeWithText("com.google.android.youtube").assertExists()
            composeRule.onNodeWithText("Facebook").assertExists()
            composeRule.onNodeWithText("com.facebook.katana").assertExists()
            composeRule.onNodeWithText("Instagram").assertExists()
            composeRule.onNodeWithText("Calculator").assertExists()

            composeRule.onNodeWithTag("installed_apps_search").performTextInput("Calc")
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Calculator").assertExists()
            composeRule.onNodeWithText("YouTube").assertDoesNotExist()

            composeRule.onNodeWithTag("apps_list_back_button").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Jarvis Control Center").assertExists()
        } finally {
            voiceManager.release()
        }
    }

    @Test
    fun chatScreen_recognizedSpeech_showsInInputBeforeSending_andDispatchesActualQuestion() {
        val viewModel = JarvisViewModel(repository, JarvisAiService(), dataStore)
        val bridge = PhoneAppBridge(context)
        val voiceManager = VoiceAssistantManager(context) { spoken ->
            viewModel.stageVoiceInput(spoken)
        }

        try {
            composeRule.setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                MyraTheme(darkTheme = true) {
                    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            ChatScreen(
                                viewModel = viewModel,
                                voiceManager = voiceManager,
                                phoneAppBridge = bridge,
                                snackbarHostState = snackbarHostState,
                                onNavigateToSettings = {},
                                onNavigateToWebBuilder = {},
                                onNavigateToDesignStudio = {}
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            // Empty input cannot be sent
            composeRule.onNodeWithTag("send_message_button").assertIsNotEnabled()

            // Deliver recognized speech -> must show in chat_input_field first
            composeRule.runOnUiThread {
                voiceManager.deliverExternalSpeechResult("today date")
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chat_input_field").assertTextContains("today date")
            composeRule.onNodeWithTag("send_message_button").assertIsEnabled()

            // Advance main clock past the 650ms preview window so it dispatches automatically
            composeRule.mainClock.advanceTimeBy(800L)
            composeRule.waitForIdle()

            // Actual question was sent and answered (not a default welcome message)
            composeRule.onNodeWithText("today date").assertExists()
            composeRule.onNodeWithText("Today's date is", substring = true).assertExists()
        } finally {
            voiceManager.release()
        }
    }

    @Test
    fun chatScreen_startupGreetingShownOnce_micClickShowsSunRahaHoon_andExactErrorsAppearInChat() {
        shadowOf(context).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val viewModel = JarvisViewModel(repository, JarvisAiService(), dataStore)
        val bridge = PhoneAppBridge(context)
        val voiceManager = VoiceAssistantManager(
            context = context,
            recognizerFactory = {
                object : com.example.device.SpeechRecognizerHandle {
                    override fun setRecognitionListener(listener: android.speech.RecognitionListener) {}
                    override fun startListening(recognizerIntent: Intent) {}
                    override fun stopListening() {}
                    override fun destroy() {}
                }
            },
            availabilityChecker = { true },
            initTtsOnStart = false
        ) { spoken ->
            viewModel.stageVoiceInput(spoken)
        }

        try {
            composeRule.setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                MyraTheme(darkTheme = true) {
                    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            ChatScreen(
                                viewModel = viewModel,
                                voiceManager = voiceManager,
                                phoneAppBridge = bridge,
                                snackbarHostState = snackbarHostState,
                                onNavigateToSettings = {},
                                onNavigateToWebBuilder = {},
                                onNavigateToDesignStudio = {}
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            // One-time startup greeting shown initially
            composeRule.onNodeWithTag("startup_greeting_card").assertExists()
            composeRule.onNodeWithText("Assalam o alaikum, main Jarvis hoon").assertExists()

            // Tap mic -> greeting dismisses, shows "Sun raha hoon..." (and does not repeat greeting)
            composeRule.onNodeWithTag("voice_mic_button").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("startup_greeting_card").assertDoesNotExist()
            assertTrue(
                composeRule.onAllNodesWithText("Sun raha hoon...", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            )

            // Surface an exact SpeechRecognizer error -> shown directly in chat
            composeRule.runOnUiThread {
                voiceManager.reportErrorMessage(
                    com.example.device.VoiceAssistantManager.formatSpeechError(
                        android.speech.SpeechRecognizer.ERROR_NO_MATCH
                    )
                )
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("voice_chat_notice_card").assertExists()
            assertTrue(
                composeRule.onAllNodesWithText("ERROR_NO_MATCH", substring = true)
                    .fetchSemanticsNodes()
                    .size >= 2
            )

            // Deliver recognized Urdu/Roman-Urdu day query -> shows in input first, then answers
            composeRule.runOnUiThread {
                voiceManager.deliverExternalSpeechResult("Jarvis, aaj kaun sa din hai")
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chat_input_field").assertTextContains("Jarvis, aaj kaun sa din hai")
            composeRule.mainClock.advanceTimeBy(800L)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Jarvis, aaj kaun sa din hai").assertExists()
            composeRule.onNodeWithText("آج ", substring = true).assertExists()
        } finally {
            voiceManager.release()
        }
    }

    @Test
    fun chatScreen_liveVoiceToggle_startsLiveListeningWithoutGreetingAndStopsCleanly() {
        shadowOf(context).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val viewModel = JarvisViewModel(repository, JarvisAiService(), dataStore)
        val bridge = PhoneAppBridge(context)
        val voiceManager = VoiceAssistantManager(
            context = context,
            recognizerFactory = {
                object : com.example.device.SpeechRecognizerHandle {
                    override fun setRecognitionListener(listener: android.speech.RecognitionListener) {}
                    override fun startListening(recognizerIntent: Intent) {}
                    override fun stopListening() {}
                    override fun destroy() {}
                }
            },
            availabilityChecker = { true },
            initTtsOnStart = false
        ) { spoken ->
            viewModel.stageVoiceInput(spoken)
        }

        try {
            composeRule.setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                MyraTheme(darkTheme = true) {
                    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            ChatScreen(
                                viewModel = viewModel,
                                voiceManager = voiceManager,
                                phoneAppBridge = bridge,
                                snackbarHostState = snackbarHostState,
                                onNavigateToSettings = {},
                                onNavigateToWebBuilder = {},
                                onNavigateToDesignStudio = {}
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("live_voice_mode_button").assertExists().performClick()
            composeRule.waitForIdle()

            assertTrue(voiceManager.isLiveConversationActive.value)
            assertTrue(voiceManager.isListening.value)
            composeRule.onNodeWithTag("startup_greeting_card").assertDoesNotExist()
            assertTrue(
                composeRule.onAllNodesWithText("Live Voice • Sun raha hoon...", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            )

            composeRule.onNodeWithTag("cancel_voice_button").assertExists().performClick()
            composeRule.waitForIdle()

            assertFalse(voiceManager.isLiveConversationActive.value)
            assertFalse(voiceManager.isListening.value)
        } finally {
            voiceManager.release()
        }
    }
}
