package com.example.device

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AssistantLanguage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.os.Looper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceAssistantManagerTest {

    private lateinit var context: Application

    private class FakeSpeechRecognizerHandle : SpeechRecognizerHandle {
        var listener: RecognitionListener? = null
        val startedIntents = mutableListOf<Intent>()
        var stoppedCount = 0
        var destroyedCount = 0

        override fun setRecognitionListener(listener: RecognitionListener) {
            this.listener = listener
        }

        override fun startListening(recognizerIntent: Intent) {
            startedIntents.add(recognizerIntent)
        }

        override fun stopListening() {
            stoppedCount++
        }

        override fun destroy() {
            destroyedCount++
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun buildSpeechRecognizerIntent_includesCallingPackageSilenceTimeoutsAndFinalOnly() {
        val manager = VoiceAssistantManager(
            context = context,
            recognizerFactory = { FakeSpeechRecognizerHandle() },
            availabilityChecker = { true },
            initTtsOnStart = false,
            onFinalSpeechResult = {}
        )

        val intent = manager.buildSpeechRecognizerIntent(AssistantLanguage.URDU)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE))
        assertEquals("ur-PK", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        assertEquals(5, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
        assertFalse(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
        assertEquals(2000L, intent.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L))
        assertEquals(3000L, intent.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0L))
        assertEquals(2500L, intent.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 0L))
    }

    @Test
    fun errorNoMatch_ignoresPartialsFallsBackToEnUsAndReportsExactErrorNoMatchWhenNoDirectFallback() {
        val handles = mutableListOf<FakeSpeechRecognizerHandle>()
        val delivered = mutableListOf<String>()
        val manager = VoiceAssistantManager(
            context = context,
            recognizerFactory = { FakeSpeechRecognizerHandle().also { handles.add(it) } },
            availabilityChecker = { true },
            initTtsOnStart = false,
            canUseDirectAudioFallback = { false },
            onFinalSpeechResult = { delivered.add(it) }
        )

        manager.startListening(AssistantLanguage.URDU)
        assertEquals(1, handles.size)
        val listener = handles[0].listener!!
        listener.onReadyForSpeech(Bundle())

        val partialBundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("open Facebook"))
        }
        listener.onPartialResults(partialBundle)
        listener.onError(SpeechRecognizer.ERROR_NO_MATCH)

        // Falls back once from ur-PK to en-US before speech starts, ignoring partial fragments
        assertEquals(2, handles.size)
        assertEquals("en-US", handles[1].startedIntents.first().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(delivered.isEmpty())

        // If en-US also returns ERROR_NO_MATCH and no direct AI fallback is available, exact error name is reported
        handles[1].listener!!.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertTrue(delivered.isEmpty())
        assertTrue(manager.voiceError.value?.startsWith("ERROR_NO_MATCH:") == true)
        assertFalse(manager.isListening.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun errorNoMatch_afterSpeechWithDirectAudioFallback_requestsDirectMicAndRemembersPreference() = runTest {
        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val handles = mutableListOf<FakeSpeechRecognizerHandle>()
        val fakeWav = AndroidDirectAudioRecorder.encodePcm16MonoToWav(ByteArray(3200))
        val fakeRecorder = FakeAudioRecorderHandle(
            RecordedAudioResult.AudioCaptured(wavBytes = fakeWav, durationMs = 1000L, peakAmplitude = 1500)
        )
        val delivered = mutableListOf<String>()
        val manager = VoiceAssistantManager(
            context = context,
            recognizerFactory = { FakeSpeechRecognizerHandle().also { handles.add(it) } },
            availabilityChecker = { true },
            initTtsOnStart = false,
            audioRecorderFactory = { fakeRecorder },
            audioTranscriber = { _, _ -> "Jarvis aaj kaun sa din hai" },
            canUseDirectAudioFallback = { true },
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher,
            onFinalSpeechResult = { delivered.add(it) }
        )

        val fallbacks = mutableListOf<SystemVoiceFallbackEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.systemVoiceFallbackRequests.collect { fallbacks.add(it) }
        }

        manager.startListening(AssistantLanguage.URDU)
        assertEquals(1, handles.size)
        handles[0].listener!!.onReadyForSpeech(Bundle())
        handles[0].listener!!.onBeginningOfSpeech()
        handles[0].listener!!.onError(SpeechRecognizer.ERROR_NO_MATCH)
        advanceUntilIdle()

        // Must immediately request Direct AI Mic fallback instead of looping in SpeechRecognizer ERROR_NO_MATCH
        assertEquals(1, handles.size)
        assertEquals(1, fallbacks.size)
        assertTrue(fallbacks.first().preferDirectRecorder)

        // Subsequent mic tap goes straight to DirectAudioRecorder cleanly
        manager.startListening(AssistantLanguage.URDU)
        assertEquals(1, fakeRecorder.startedCount)
        assertTrue(manager.isDirectRecordingMode.value)
        manager.stopListening()
        advanceUntilIdle()
        assertEquals(listOf("Jarvis aaj kaun sa din hai"), delivered)
        job.cancel()
    }

    @Test
    fun errorNoMatchBeforeBeginningOfSpeech_retriesWithFallbackLocaleAndDeliversFinalResult() {
        val handles = mutableListOf<FakeSpeechRecognizerHandle>()
        val delivered = mutableListOf<String>()
        val manager = VoiceAssistantManager(
            context = context,
            recognizerFactory = {
                FakeSpeechRecognizerHandle().also { handles.add(it) }
            },
            availabilityChecker = { true },
            initTtsOnStart = false,
            onFinalSpeechResult = { delivered.add(it) }
        )

        manager.startListening(AssistantLanguage.URDU)
        assertEquals(1, handles.size)
        assertEquals("ur-PK", handles[0].startedIntents.first().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))

        // First recognizer fails before speech starts (e.g. ur-PK offline pack unavailable)
        handles[0].listener!!.onError(SpeechRecognizer.ERROR_NO_MATCH)

        // Should have automatically created a second session with fallback locale (en-US)
        assertEquals(2, handles.size)
        val retryLocale = handles[1].startedIntents.first().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        assertEquals("en-US", retryLocale)

        val finalBundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("What is today's date"))
        }
        handles[1].listener!!.onResults(finalBundle)

        assertEquals(listOf("What is today's date"), delivered)
        assertNull(manager.voiceError.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun unavailableInlineRecognizer_requestsSystemVoiceFallback_andAcceptsExternalResult() = runTest {
        val delivered = mutableListOf<String>()
        val manager = VoiceAssistantManager(
            context = context,
            recognizerFactory = { FakeSpeechRecognizerHandle() },
            availabilityChecker = { false },
            initTtsOnStart = false,
            onFinalSpeechResult = { delivered.add(it) }
        )

        val fallbacks = mutableListOf<SystemVoiceFallbackEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.systemVoiceFallbackRequests.collect { fallbacks.add(it) }
        }

        manager.startListening(AssistantLanguage.URDU)
        advanceUntilIdle()
        assertEquals(1, fallbacks.size)
        assertEquals(AssistantLanguage.URDU, fallbacks.first().language)

        manager.deliverExternalSpeechResult("  Telegram  ")
        assertEquals(listOf("Telegram"), delivered)
        assertNull(manager.voiceError.value)

        job.cancel()
    }

    private class FakeAudioRecorderHandle(
        private val resultToReturn: RecordedAudioResult
    ) : AudioRecorderHandle {
        var startedCount = 0
        var stoppedCount = 0
        var cancelledCount = 0

        override fun startRecording(onRmsUpdated: (Float) -> Unit) {
            startedCount++
            onRmsUpdated(0.45f)
        }

        override fun stopRecording(): RecordedAudioResult {
            stoppedCount++
            return resultToReturn
        }

        override fun cancelRecording() {
            cancelledCount++
        }
    }

    @Test
    fun errorClient_andOtherRecognizerErrors_reportExactErrorNamesInVoiceError() {
        val handles = mutableListOf<FakeSpeechRecognizerHandle>()
        val manager = VoiceAssistantManager(
            context = context,
            recognizerFactory = { FakeSpeechRecognizerHandle().also { handles.add(it) } },
            availabilityChecker = { true },
            initTtsOnStart = false,
            canUseDirectAudioFallback = { false },
            onFinalSpeechResult = {}
        )

        manager.startListening(AssistantLanguage.URDU)
        handles.last().listener!!.onError(SpeechRecognizer.ERROR_CLIENT)
        assertTrue(manager.voiceError.value?.startsWith("ERROR_CLIENT:") == true)
        assertFalse(manager.isListening.value)

        manager.startListening(AssistantLanguage.URDU)
        handles.last().listener!!.onError(SpeechRecognizer.ERROR_AUDIO)
        assertTrue(manager.voiceError.value?.startsWith("ERROR_AUDIO:") == true)

        manager.startListening(AssistantLanguage.URDU)
        handles.last().listener!!.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertTrue(manager.voiceError.value?.startsWith("ERROR_SPEECH_TIMEOUT:") == true)
    }

    @Test
    fun startListeningWhileTtsSpeaking_waitsUntilUtteranceDoneBeforeStartingRecognizer() {
        val handles = mutableListOf<FakeSpeechRecognizerHandle>()
        val manager = VoiceAssistantManager(
            context = context,
            recognizerFactory = { FakeSpeechRecognizerHandle().also { handles.add(it) } },
            availabilityChecker = { true },
            initTtsOnStart = false,
            onFinalSpeechResult = {}
        )

        manager.setSpeakingForTest(VoiceAssistantManager.STARTUP_GREETING)
        manager.startListening(AssistantLanguage.URDU)

        // Recognizer MUST NOT start while TTS is still speaking
        assertEquals(0, handles.size)
        assertFalse(manager.isListening.value)

        // Once TTS UtteranceProgressListener.onDone() fires, SpeechRecognizer starts
        manager.notifyTtsUtteranceDoneForTest()
        assertEquals(1, handles.size)
        assertEquals("ur-PK", handles[0].startedIntents.first().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(manager.isListening.value)
    }

    @Test
    fun errorRecognizerBusy_retriesOnceAndDeliversFinalResult() {
        val handles = mutableListOf<FakeSpeechRecognizerHandle>()
        val delivered = mutableListOf<String>()
        val manager = VoiceAssistantManager(
            context = context,
            recognizerFactory = { FakeSpeechRecognizerHandle().also { handles.add(it) } },
            availabilityChecker = { true },
            initTtsOnStart = false,
            onFinalSpeechResult = { delivered.add(it) }
        )

        manager.startListening(AssistantLanguage.ENGLISH)
        assertEquals(1, handles.size)

        handles[0].listener!!.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        assertEquals(2, handles.size)

        val finalBundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("open YouTube"))
        }
        handles[1].listener!!.onResults(finalBundle)

        assertEquals(listOf("open YouTube"), delivered)
        assertNull(manager.voiceError.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun directAudioRecorder_capturesWav_transcribesAndDeliversToChat() = runTest {
        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val fakeWav = AndroidDirectAudioRecorder.encodePcm16MonoToWav(ByteArray(3200))
        val fakeRecorder = FakeAudioRecorderHandle(
            RecordedAudioResult.AudioCaptured(wavBytes = fakeWav, durationMs = 1000L, peakAmplitude = 1500)
        )
        val delivered = mutableListOf<String>()
        val manager = VoiceAssistantManager(
            context = context,
            recognizerFactory = { FakeSpeechRecognizerHandle() },
            availabilityChecker = { false },
            initTtsOnStart = false,
            audioRecorderFactory = { fakeRecorder },
            audioTranscriber = { wavBytes, _ ->
                assertEquals(fakeWav.size, wavBytes.size)
                "آج کی تاریخ کیا ہے"
            },
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher,
            onFinalSpeechResult = { delivered.add(it) }
        )

        manager.startDirectRecording(AssistantLanguage.URDU)
        assertTrue(manager.isListening.value)
        assertTrue(manager.isDirectRecordingMode.value)
        assertEquals(1, fakeRecorder.startedCount)

        manager.stopListening()
        advanceUntilIdle()

        assertEquals(1, fakeRecorder.stoppedCount)
        assertFalse(manager.isListening.value)
        assertFalse(manager.isProcessingSpeech.value)
        assertEquals(listOf("آج کی تاریخ کیا ہے"), delivered)
        assertNull(manager.voiceError.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun directAudioRecorder_noAudioSignal_setsClearError() = runTest {
        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val fakeRecorder = FakeAudioRecorderHandle(RecordedAudioResult.NoAudioSignal)
        val manager = VoiceAssistantManager(
            context = context,
            recognizerFactory = { FakeSpeechRecognizerHandle() },
            availabilityChecker = { false },
            initTtsOnStart = false,
            audioRecorderFactory = { fakeRecorder },
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher,
            onFinalSpeechResult = {}
        )

        manager.startDirectRecording(AssistantLanguage.URDU)
        manager.stopListening()
        advanceUntilIdle()

        assertFalse(manager.isListening.value)
        assertFalse(manager.isProcessingSpeech.value)
        assertTrue(manager.voiceError.value?.contains("مائیکروفون") == true)
    }

    @Test
    fun encodePcm16MonoToWav_writesValidRiffWaveHeader() {
        val pcm = ByteArray(100) { 1 }
        val wav = AndroidDirectAudioRecorder.encodePcm16MonoToWav(pcm, sampleRateHz = 16000)
        assertEquals(144, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun silenceMarkersNoiseWordsAndEchoes_areRejectedWithDobaraBoleinWithoutDeliveringToChat() {
        val delivered = mutableListOf<String>()
        val manager = VoiceAssistantManager(
            context = context,
            recognizerFactory = { FakeSpeechRecognizerHandle() },
            availabilityChecker = { true },
            initTtsOnStart = false,
            onFinalSpeechResult = { delivered.add(it) }
        )

        manager.setSpeakingForTest("Today's date is Friday, November 20, 2026.")
        manager.notifyTtsUtteranceDoneForTest()

        listOf(
            "",
            "   ",
            "...",
            "hmm",
            "hm",
            "uh",
            "um",
            "ہمم",
            "[silence]",
            "(no speech)",
            "[inaudible]",
            "Assalam o alaikum, main Jarvis hoon",
            "I am Jarvis. I am listening. I am here to help you.",
            "Today's date is Friday, November 20, 2026."
        ).forEach { noiseOrEcho ->
            manager.deliverExternalSpeechResult(noiseOrEcho)
            assertTrue("Expected discard for '$noiseOrEcho'", delivered.isEmpty())
            assertEquals(VoiceAssistantManager.RETRY_SPEECH_PROMPT, manager.voiceError.value)
        }

        manager.deliverExternalSpeechResult("Hey Jarvis, how are you? What is today's date and day?")
        assertEquals(listOf("Hey Jarvis, how are you? What is today's date and day?"), delivered)
        assertNull(manager.voiceError.value)
    }

    @Test
    fun liveConversation_autoResumesAfterTtsDone_andPausesAfterRepeatedEmptyTurnsOrCancel() {
        val handles = mutableListOf<FakeSpeechRecognizerHandle>()
        val delivered = mutableListOf<String>()
        val manager = VoiceAssistantManager(
            context = context,
            recognizerFactory = { FakeSpeechRecognizerHandle().also { handles.add(it) } },
            availabilityChecker = { true },
            initTtsOnStart = false,
            canUseDirectAudioFallback = { false },
            onFinalSpeechResult = { delivered.add(it) }
        )

        manager.startLiveConversation(AssistantLanguage.URDU)
        assertTrue(manager.isLiveConversationActive.value)
        assertEquals(1, handles.size)
        assertTrue(manager.isListening.value)

        // User asks a question in Urdu; TTS starts speaking reply
        handles[0].listener!!.onReadyForSpeech(Bundle())
        handles[0].listener!!.onResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("آج موسم کیسا ہے"))
        })
        assertEquals(listOf("آج موسم کیسا ہے"), delivered)

        manager.setSpeakingForTest("آج لاہور میں موسم خوشگوار ہے۔")
        manager.notifyTtsUtteranceDoneForTest()
        // Wait past post-TTS drain delay (350ms) -> live mode automatically opens mic for next turn
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(400L, TimeUnit.MILLISECONDS)
        assertEquals(2, handles.size)
        assertTrue(manager.isListening.value)
        assertTrue(manager.isLiveConversationActive.value)

        // Two consecutive empty/silence turns automatically pause Live Voice so it never loops endlessly
        handles[1].listener!!.onResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(""))
        })
        assertTrue(manager.isLiveConversationActive.value)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(700L, TimeUnit.MILLISECONDS)
        assertEquals(3, handles.size)

        handles[2].listener!!.onResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("hmm"))
        })
        assertFalse(manager.isLiveConversationActive.value)
        assertEquals(VoiceAssistantManager.LIVE_PAUSED_EMPTY_NOTICE, manager.voiceError.value)

        // Restarting live mode & cancelling turns it off immediately
        manager.startLiveConversation(AssistantLanguage.URDU)
        assertTrue(manager.isLiveConversationActive.value)
        manager.stopLiveConversation()
        assertFalse(manager.isLiveConversationActive.value)
        assertFalse(manager.isListening.value)
    }
}
