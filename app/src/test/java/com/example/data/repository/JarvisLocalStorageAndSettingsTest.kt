package com.example.data.repository

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.GEMINI_API_KEY
import com.example.data.ai.JarvisAiService
import com.example.data.ai.resolveGeminiApiKey
import com.example.data.local.AppDatabase
import com.example.data.model.AssistantLanguage
import com.example.device.PhoneAppBridge
import com.example.ui.viewmodel.DataUiState
import com.example.ui.viewmodel.JarvisViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JarvisLocalStorageAndSettingsTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: JarvisRepository
    private lateinit var dataStore: PreferencesDataStore

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
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
        kotlinx.coroutines.runBlocking {
            dataStore.saveGeminiApiKey("")
        }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun preferencesDataStore_defaultsToUrduUnmuted_andTrimsSpacesAndNewlinesFromApiKey() = runTest {
        val initial = dataStore.settingsFlow.first()
        assertEquals(AssistantLanguage.URDU.storageValue, initial.preferredLanguage)
        assertFalse(initial.isMuted)
        assertEquals("", initial.geminiApiKey)

        dataStore.saveGeminiApiKey("  \n\r AIzaTestKey12345 \t \n  ")
        val updated = dataStore.settingsFlow.first()
        assertEquals("AIzaTestKey12345", updated.geminiApiKey)
    }

    @Test
    fun roomRepository_createsPersistsAndClearsLocalMessages() = runTest {
        val convId = repository.createConversation(
            title = "ٹیسٹ گفتگو",
            language = AssistantLanguage.URDU.storageValue,
            initialPreview = "السلام علیکم"
        )
        repository.addMessage(
            conversationId = convId,
            role = "user",
            content = "السلام علیکم جاروس",
            language = AssistantLanguage.URDU.storageValue
        )
        repository.addMessage(
            conversationId = convId,
            role = "assistant",
            content = "وعلیکم السلام! میں آپ کی کیا مدد کر سکتا ہوں؟",
            language = AssistantLanguage.URDU.storageValue
        )

        val messages = repository.observeMessages(convId).first()
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("assistant", messages[1].role)

        repository.clearConversationMessages(convId)
        val afterClear = repository.observeMessages(convId).first()
        assertTrue(afterClear.isEmpty())
    }

    @Test
    fun jarvisViewModel_telegramKholo_writesOpeningMessageInChatAndLaunchesImmediately() = runTest {
        val shadowPm = shadowOf(context.packageManager)
        val component = ComponentName("org.telegram.messenger", "org.telegram.ui.LaunchActivity")
        shadowPm.addActivityIfNotPresent(component)
        shadowPm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )
        shadowPm.getInternalMutablePackageInfo("org.telegram.messenger").applicationInfo?.apply {
            nonLocalizedLabel = "Telegram"
            name = "Telegram"
        }

        val viewModel = JarvisViewModel(
            repository = repository,
            aiService = JarvisAiService(),
            dataStore = dataStore,
            apiKeyProvider = { "" } // Proves Gemini is never called
        )
        val bridge = PhoneAppBridge(context)

        val spokenEvents = mutableListOf<String>()
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.speakEvents.collect { spokenEvents.add(it) }
        }

        viewModel.sendUserMessage("telegram کھولو", bridge)
        advanceUntilIdle()

        val startedIntent = shadowOf(context).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals("org.telegram.messenger", startedIntent.component?.packageName)

        val convId = viewModel.activeConversationId.value
        assertNotNull(convId)
        val messages = repository.observeMessages(convId!!).first()
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("telegram کھولو", messages[0].content)
        assertEquals("assistant", messages[1].role)
        assertEquals("OK, opening Telegram.", messages[1].content)
        assertEquals(null, viewModel.chatError.value)
        assertEquals("OK, opening Telegram.", viewModel.statusMessage.value)
        assertTrue(spokenEvents.contains("OK, opening Telegram."))

        collectJob.cancel()
    }

    @Test
    fun jarvisViewModel_telegramWhenNotInstalled_writesNotFoundMessageInChatWithoutGemini() = runTest {
        val viewModel = JarvisViewModel(
            repository = repository,
            aiService = JarvisAiService(),
            dataStore = dataStore,
            apiKeyProvider = { "" } // Proves Gemini is never called
        )
        val bridge = PhoneAppBridge(context)

        val spokenEvents = mutableListOf<String>()
        val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.speakEvents.collect { spokenEvents.add(it) }
        }

        viewModel.sendUserMessage("telegram کھولو", bridge)
        advanceUntilIdle()

        val expectedMsg = "I could not find telegram among installed apps. Installed apps list is in Settings."
        val startedIntent = shadowOf(context).nextStartedActivity
        assertEquals(null, startedIntent)
        val convId = viewModel.activeConversationId.value
        assertNotNull(convId)
        val messages = repository.observeMessages(convId!!).first()
        assertEquals(2, messages.size)
        assertEquals("assistant", messages[1].role)
        assertEquals(expectedMsg, messages[1].content)
        assertEquals(null, viewModel.chatError.value)
        assertEquals(expectedMsg, viewModel.statusMessage.value)
        assertTrue(spokenEvents.contains(expectedMsg))

        collectJob.cancel()
    }

    @Test
    fun jarvisViewModel_bareAppNames_youtubeFacebookInstagramCalculatorAndInstalledApp_launchWithoutGemini() = runTest {
        val shadowPm = shadowOf(context.packageManager)
        fun installApp(pkg: String, label: String, activity: String) {
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

        installApp("com.google.android.youtube", "YouTube", "com.google.android.youtube.HomeActivity")
        installApp("com.facebook.katana", "Facebook", "com.facebook.katana.LoginActivity")
        installApp("com.instagram.android", "Instagram", "com.instagram.mainactivity.LauncherActivity")
        installApp("com.google.android.calculator", "Calculator", "com.android.calculator2.Calculator")
        installApp("com.whatsapp", "WhatsApp", "com.whatsapp.Main")

        val bridge = PhoneAppBridge(context)
        val viewModel = JarvisViewModel(
            repository = repository,
            aiService = JarvisAiService(),
            dataStore = dataStore,
            apiKeyProvider = { "" } // Must never hit Gemini
        )

        val queries = listOf(
            "YouTube" to ("com.google.android.youtube" to "OK, opening YouTube."),
            "open Facebook" to ("com.facebook.katana" to "OK, opening Facebook."),
            "Instagram" to ("com.instagram.android" to "OK, opening Instagram."),
            "Calculator" to ("com.google.android.calculator" to "OK, opening Calculator."),
            "WhatsApp" to ("com.whatsapp" to "OK, opening WhatsApp.")
        )

        for ((query, expected) in queries) {
            viewModel.sendUserMessage(query, bridge)
            advanceUntilIdle()
            val launched = shadowOf(context).nextStartedActivity
            assertNotNull("Expected launch intent for $query", launched)
            assertEquals(expected.first, launched.component?.packageName)
            assertEquals(expected.second, viewModel.statusMessage.value)
            assertEquals(null, viewModel.chatError.value)
        }

        val convId = viewModel.activeConversationId.value!!
        val messages = repository.observeMessages(convId).first()
        assertEquals(10, messages.size)
        assertEquals("OK, opening YouTube.", messages[1].content)
        assertEquals("OK, opening Facebook.", messages[3].content)
        assertEquals("OK, opening Instagram.", messages[5].content)
        assertEquals("OK, opening Calculator.", messages[7].content)
        assertEquals("OK, opening WhatsApp.", messages[9].content)
    }

    @Test
    fun jarvisViewModel_todayDateQueries_answerWithDeviceDateImmediately() = runTest {
        val viewModel = JarvisViewModel(
            repository = repository,
            aiService = JarvisAiService(),
            dataStore = dataStore,
            apiKeyProvider = { "" }
        )
        val bridge = PhoneAppBridge(context)

        viewModel.sendUserMessage("today date", bridge)
        advanceUntilIdle()
        viewModel.sendUserMessage("آج کی تاریخ کیا ہے؟", bridge)
        advanceUntilIdle()

        val convId = viewModel.activeConversationId.value!!
        val messages = repository.observeMessages(convId).first()
        assertEquals(4, messages.size)
        assertTrue(messages[1].content.contains("Today's date is"))
        assertTrue(messages[3].content.contains("آج کی تاریخ"))
        assertEquals(null, viewModel.chatError.value)
    }

    @Test
    fun jarvisViewModel_chatWithMissingApiKey_showsClearInvalidKeyMessage() = runTest {
        val viewModel = JarvisViewModel(
            repository = repository,
            aiService = JarvisAiService(),
            dataStore = dataStore,
            apiKeyProvider = { "یہاں_key" }
        )
        val bridge = PhoneAppBridge(context)

        viewModel.sendUserMessage("آج موسم کیسا ہے؟", bridge)
        advanceUntilIdle()

        assertEquals(
            "Gemini API key خالی ہے، براہِ کرم AI Studio کے Secrets پینل یا کوڈ میں GEMINI_API_KEY سیٹ کریں۔",
            viewModel.chatError.value
        )
    }

    @Test
    fun resolveGeminiApiKey_trimsWhitespaceAndTreatsPlaceholderAsEmpty() {
        assertEquals("یہاں_key", GEMINI_API_KEY)
        assertEquals("", resolveGeminiApiKey("یہاں_key"))
        assertEquals("", resolveGeminiApiKey("GEMINI_API_KEY_DEFAULT_VALUE"))
        assertEquals("", resolveGeminiApiKey("  \n \t  "))
        assertEquals(
            "AIzaSyCodeConstKey987",
            resolveGeminiApiKey(" \n \"AIzaSyCodeConstKey987\" \t \r\n ")
        )
    }

    @Test
    fun jarvisViewModel_chatRetryExhausted503_showsBusyErrorWithOriginalMessage() = runTest {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var attempts = 0
        server.createContext("/") { exchange ->
            attempts++
            val errBytes = """{"error":{"code":503,"message":"Backend overloaded test","status":"UNAVAILABLE"}}"""
                .toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(503, errBytes.size.toLong())
            exchange.responseBody.use { it.write(errBytes) }
        }
        server.start()

        try {
            val viewModel = JarvisViewModel(
                repository = repository,
                aiService = JarvisAiService(
                    baseUrl = "http://127.0.0.1:${server.address.port}/v1beta",
                    retryDelayMs = 25L
                ),
                dataStore = dataStore,
                apiKeyProvider = { "AIzaSyViewModelTestKey_1234567890abc" }
            )
            val bridge = PhoneAppBridge(context)

            val observedStreamTexts = mutableListOf<String>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                viewModel.streamingReply.collect { text ->
                    if (text != null) observedStreamTexts.add(text)
                }
            }

            viewModel.sendUserMessage("ہیلو جاروس", bridge)

            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                advanceUntilIdle()
                if (viewModel.chatError.value != null && !viewModel.isGenerating.value) {
                    break
                }
                Thread.sleep(20)
            }

            collectJob.cancel()

            assertEquals(4, attempts)
            assertTrue(observedStreamTexts.contains(JarvisAiService.RETRY_STATUS_MESSAGE))
            val err = viewModel.chatError.value.orEmpty()
            assertTrue(err.contains(JarvisAiService.SERVER_BUSY_MESSAGE))
            assertTrue(err.contains("Backend overloaded test"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun jarvisViewModel_stageVoiceInput_rejectsEmptyNoiseAndEcho_andStagesRealQuestion() = runTest {
        val viewModel = JarvisViewModel(repository, JarvisAiService(), dataStore)
        val bridge = PhoneAppBridge(context)

        // One-time startup greeting state starts true and consumes at most once
        assertTrue(viewModel.startupGreetingVisible.value)
        assertTrue(viewModel.consumeStartupVoiceGreetingNeeded())
        assertFalse(viewModel.consumeStartupVoiceGreetingNeeded())

        // Empty, punctuation, noise words, and greeting echoes must be rejected with Dobara bolein
        listOf("", "   ", "   ...   ", "hmm", "uh", "um", "Assalam o alaikum, main Jarvis hoon", "I am Jarvis. I am listening. I am here to help you.").forEach { rejected ->
            assertFalse("Expected rejection for '$rejected'", viewModel.stageVoiceInput(rejected))
            assertEquals(null, viewModel.stagedVoicePrompt.value)
        }
        assertTrue(viewModel.voiceNotices.value.contains("Dobara bolein"))
        assertFalse(viewModel.startupGreetingVisible.value)

        // Sending empty/punctuation-only text directly must also be ignored
        viewModel.sendUserMessage("   ", bridge)
        viewModel.sendUserMessage("...", bridge)
        assertEquals(null, viewModel.activeConversationId.value)

        // Real question must stage and answer accurately (including date/day queries)
        assertTrue(viewModel.stageVoiceInput("  Hey Jarvis, how are you? What is today's date and day?  "))
        assertEquals("Hey Jarvis, how are you? What is today's date and day?", viewModel.stagedVoicePrompt.value)
        viewModel.consumeStagedVoicePrompt()
        assertEquals(null, viewModel.stagedVoicePrompt.value)

        viewModel.sendUserMessage("Hey Jarvis, how are you? What is today's date and day?", bridge)
        advanceUntilIdle()
        viewModel.sendUserMessage("Jarvis, aaj kaun sa din hai", bridge)
        advanceUntilIdle()

        val convId = viewModel.activeConversationId.value!!
        val messages = repository.observeMessages(convId).first()
        assertEquals(4, messages.size)
        val englishReply = messages[1].content
        assertTrue(englishReply.contains("I am doing well"))
        assertTrue(englishReply.contains("Today's date is"))
        val urduReply = messages[3].content
        assertTrue(urduReply.contains("آج "))

        // Echoing the latest assistant reply or lastSpokenText back into the mic must also be discarded!
        assertFalse(viewModel.stageVoiceInput(urduReply))
        assertFalse(viewModel.stageVoiceInput(englishReply, lastSpokenText = englishReply))
        assertEquals(null, viewModel.stagedVoicePrompt.value)
        assertTrue(viewModel.voiceNotices.value.contains("Dobara bolein"))
    }
}
