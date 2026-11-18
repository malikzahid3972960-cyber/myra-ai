package com.example.device

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

sealed interface RecordedAudioResult {
    data class AudioCaptured(
        val wavBytes: ByteArray,
        val durationMs: Long,
        val peakAmplitude: Int
    ) : RecordedAudioResult

    data object TooShort : RecordedAudioResult
    data object NoAudioSignal : RecordedAudioResult
    data class Failed(
        val message: String,
        val isPermissionError: Boolean = false
    ) : RecordedAudioResult
}

internal interface AudioRecorderHandle {
    fun startRecording(onRmsUpdated: (Float) -> Unit)
    fun startRecording(
        onRmsUpdated: (Float) -> Unit,
        onAutoSilenceDetected: () -> Unit
    ) = startRecording(onRmsUpdated)
    fun stopRecording(): RecordedAudioResult
    fun cancelRecording()
}

internal class AndroidDirectAudioRecorder : AudioRecorderHandle {

    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var startTimeMs: Long = 0L
    private var peakAmplitude: Int = 0
    private var totalAbsAmplitude: Long = 0L
    private var totalSampleCount: Long = 0L
    private var failureResult: RecordedAudioResult.Failed? = null
    private val pcmOutput = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    override fun startRecording(onRmsUpdated: (Float) -> Unit) {
        startRecording(onRmsUpdated = onRmsUpdated, onAutoSilenceDetected = {})
    }

    @SuppressLint("MissingPermission")
    override fun startRecording(
        onRmsUpdated: (Float) -> Unit,
        onAutoSilenceDetected: () -> Unit
    ) {
        cancelRecording()

        pcmOutput.reset()
        peakAmplitude = 0
        totalAbsAmplitude = 0L
        totalSampleCount = 0L
        failureResult = null

        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes == AudioRecord.ERROR || minBufferBytes == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("اس ڈیوائس پر مائیکروفون آڈیو ریکارڈنگ دستیاب نہیں ہے۔")
        }

        val bufferBytes = max(minBufferBytes * 2, SAMPLE_RATE_HZ / 2)
        val record = createAudioRecord(bufferBytes)
            ?: throw IllegalStateException("مائیکروفون شروع نہیں ہو سکا (AudioRecord initialize ناکام)۔")

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            try {
                record.startRecording()
            } catch (sec: SecurityException) {
                record.release()
                throw sec
            } catch (e: Exception) {
                record.release()
                throw IllegalStateException("مائیکروفون ریکارڈنگ شروع نہیں ہو سکی: ${e.localizedMessage}", e)
            }
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw IllegalStateException("مائیکروفون کسی اور ایپ میں مصروف ہے یا ریکارڈنگ شروع نہیں ہو سکی۔")
        }

        audioRecord = record
        startTimeMs = System.currentTimeMillis()
        isRecording.set(true)

        captureThread = Thread({
            val samples = ShortArray(bufferBytes / 2)
            val byteScratch = ByteArray(samples.size * 2)
            var hasDetectedSpeech = false
            var lastSpeechTimestampMs = startTimeMs
            var autoStopTriggered = false

            try {
                while (isRecording.get()) {
                    val now = System.currentTimeMillis()
                    if (now - startTimeMs >= MAX_RECORDING_MS) {
                        isRecording.set(false)
                        if (hasDetectedSpeech) {
                            autoStopTriggered = true
                        }
                        break
                    }
                    val readCount = record.read(samples, 0, samples.size)
                    if (readCount > 0) {
                        var sumSquares = 0.0
                        var localPeak = 0
                        var localAbsSum = 0L
                        for (i in 0 until readCount) {
                            val s = samples[i].toInt()
                            val absVal = abs(s)
                            if (absVal > localPeak) localPeak = absVal
                            localAbsSum += absVal
                            sumSquares += s.toDouble() * s.toDouble()
                            byteScratch[i * 2] = (s and 0xFF).toByte()
                            byteScratch[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
                        }
                        if (localPeak > peakAmplitude) {
                            peakAmplitude = localPeak
                        }
                        totalAbsAmplitude += localAbsSum
                        totalSampleCount += readCount
                        synchronized(pcmOutput) {
                            pcmOutput.write(byteScratch, 0, readCount * 2)
                        }
                        val rms = sqrt(sumSquares / readCount).toFloat()
                        val normalizedRms = (rms / 6000f).coerceIn(0f, 1f)
                        onRmsUpdated(normalizedRms)

                        val readTimeMs = System.currentTimeMillis()
                        if (localPeak >= SPEECH_DETECTED_PEAK || rms >= SPEECH_DETECTED_RMS) {
                            hasDetectedSpeech = true
                            lastSpeechTimestampMs = readTimeMs
                        } else if (hasDetectedSpeech &&
                            (readTimeMs - startTimeMs) >= MIN_AUTO_STOP_ELAPSED_MS &&
                            (readTimeMs - lastSpeechTimestampMs) >= AUTO_SILENCE_STOP_MS
                        ) {
                            isRecording.set(false)
                            autoStopTriggered = true
                            break
                        }
                    } else if (readCount < 0) {
                        Log.w(TAG, "AudioRecord.read returned error code: $readCount")
                        if (readCount == AudioRecord.ERROR_INVALID_OPERATION || readCount == AudioRecord.ERROR_DEAD_OBJECT) {
                            failureResult = RecordedAudioResult.Failed("مائیکروفون آڈیو اسٹریم منقطع ہو گیا۔")
                            isRecording.set(false)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio capture loop failed", e)
                failureResult = RecordedAudioResult.Failed("آڈیو ریکارڈنگ میں خرابی: ${e.localizedMessage}")
            }

            if (autoStopTriggered && failureResult == null) {
                onAutoSilenceDetected()
            }
        }, "jarvis-audio-capture").apply { start() }
    }

    override fun stopRecording(): RecordedAudioResult {
        isRecording.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            captureThread?.join(1200L)
        } catch (_: InterruptedException) {
        }
        captureThread = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        val recordedFailure = failureResult
        if (recordedFailure != null) {
            return recordedFailure
        }

        val durationMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
        val pcmBytes = synchronized(pcmOutput) { pcmOutput.toByteArray() }
        pcmOutput.reset()

        val meanAbsAmplitude = if (totalSampleCount > 0L) totalAbsAmplitude / totalSampleCount else 0L
        if (pcmBytes.size < MIN_PCM_BYTES || durationMs < MIN_DURATION_MS) {
            return RecordedAudioResult.TooShort
        }
        if (peakAmplitude < MIN_AUDIBLE_PEAK || meanAbsAmplitude < MIN_MEAN_ABS_AMPLITUDE) {
            return RecordedAudioResult.NoAudioSignal
        }

        val wavBytes = encodePcm16MonoToWav(pcmBytes, SAMPLE_RATE_HZ)
        return RecordedAudioResult.AudioCaptured(
            wavBytes = wavBytes,
            durationMs = durationMs,
            peakAmplitude = peakAmplitude
        )
    }

    override fun cancelRecording() {
        isRecording.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            captureThread?.join(400L)
        } catch (_: InterruptedException) {
        }
        captureThread = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        synchronized(pcmOutput) { pcmOutput.reset() }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferBytes: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val candidate = try {
                AudioRecord(
                    source,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            } catch (sec: SecurityException) {
                throw sec
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord creation failed for source=$source", e)
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            try {
                candidate?.release()
            } catch (_: Exception) {
            }
        }
        return null
    }

    companion object {
        private const val TAG = "DirectAudioRecorder"
        const val SAMPLE_RATE_HZ = 16000
        private const val MAX_RECORDING_MS = 30_000L
        private const val MIN_DURATION_MS = 250L
        private const val MIN_PCM_BYTES = SAMPLE_RATE_HZ * 2 / 5 // >= 0.2s
        private const val MIN_AUDIBLE_PEAK = 60
        private const val MIN_MEAN_ABS_AMPLITUDE = 6L
        private const val SPEECH_DETECTED_PEAK = 220
        private const val SPEECH_DETECTED_RMS = 65f
        private const val MIN_AUTO_STOP_ELAPSED_MS = 1400L
        private const val AUTO_SILENCE_STOP_MS = 2400L

        fun encodePcm16MonoToWav(pcmBytes: ByteArray, sampleRateHz: Int = SAMPLE_RATE_HZ): ByteArray {
            val totalDataLen = pcmBytes.size + 36
            val byteRate = sampleRateHz * 2 // 16-bit mono = 2 bytes/sample
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(totalDataLen)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16) // PCM chunk size
                putShort(1.toShort()) // Audio format = PCM
                putShort(1.toShort()) // Mono
                putInt(sampleRateHz)
                putInt(byteRate)
                putShort(2.toShort()) // Block align
                putShort(16.toShort()) // Bits per sample
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmBytes.size)
            }.array()
            return header + pcmBytes
        }
    }
}
