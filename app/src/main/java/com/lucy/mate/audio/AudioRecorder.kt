package com.lucy.mate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.lucy.mate.core.SoltiniDispatchers
import com.lucy.mate.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.abs

/**
 * Captures microphone audio at 16kHz 16-bit mono PCM for Gemini Live API streaming.
 */
class AudioRecorder(
    private val onAudioChunkCaptured: (ByteArray) -> Unit,
    var onSpeechStateChanged: ((Boolean) -> Unit)? = null
) {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var chunkDeliveryJob: Job? = null
    private var chunkChannel: Channel<ByteArray>? = null
    private var isRecording = false
    val isRecordingActive: Boolean get() = isRecording

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    var isMuted = false
    var isDataSaverEnabled: Boolean = false
    var vadThreshold: Float = 0.010f
    var micGain: Float = 1.5f
    var isModelSpeaking: Boolean = false
    var isScreenRecordingModeEnabled: Boolean = false
    var audioSourcePreference: String = "VOICE_COMMUNICATION"

    // Auto-calibrating ambient noise floor
    private var ambientNoiseFloor: Float = 0.005f

    private val _micAmplitude = MutableStateFlow(0f)
    val micAmplitude: StateFlow<Float> = _micAmplitude.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            AppLogger.e(TAG, "Invalid buffer size for AudioRecord: $minBufferSize")
            return
        }

        val bufferSize = maxOf(minBufferSize, SAMPLE_RATE * 2 / 10) // ~200ms chunk buffer

        try {
            val primarySource = if (audioSourcePreference.equals("VOICE_COMMUNICATION", ignoreCase = true)) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            }
            val fallbackSource = if (primarySource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                MediaRecorder.AudioSource.MIC
            } else {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }

            audioRecord = AudioRecord(
                primarySource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.w(TAG, "AudioSource $primarySource failed, falling back to $fallbackSource")
                audioRecord = AudioRecord(
                    fallbackSource,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.e(TAG, "AudioRecord failed to initialize state")
                audioRecord?.release()
                audioRecord = null
                return
            }

            // Enable hardware echo cancellation and noise suppression if available
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                    aec?.enabled = true
                    AppLogger.i(TAG, "AcousticEchoCanceler enabled")
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(audioRecord!!.audioSessionId)
                    ns?.enabled = true
                    AppLogger.i(TAG, "NoiseSuppressor enabled")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not apply audio FX: ${e.message}")
            }

            audioRecord?.startRecording()
            isRecording = true
            AppLogger.i(TAG, "AudioRecord started recording at 16000Hz PCM 16-bit")

            // Decouple chunk delivery: channel buffers PCM chunks without blocking AudioRecord read loop
            val channel = Channel<ByteArray>(
                capacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
            chunkChannel = channel

            chunkDeliveryJob = CoroutineScope(SupervisorJob() + SoltiniDispatchers.LiveWsDispatcher).launch {
                try {
                    for (chunk in channel) {
                        try {
                            onAudioChunkCaptured(chunk)
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Error in onAudioChunkCaptured: ${e.message}")
                        }
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Normal coroutine cancellation
                }
            }

            recordingJob = CoroutineScope(SupervisorJob() + SoltiniDispatchers.MicCaptureDispatcher).launch {
                // 3200 bytes = 1600 samples = 100ms at 16kHz PCM 16-bit mono.
                // Reduces WebSocket framing overhead by ~70% compared to 1024-byte chunks.
                val buffer = ByteArray(3200)
                var chunkCount = 0
                var errorCount = 0
                var zeroAmpStreak = 0

                // In Data Saver mode, send trailing audio for at least 8 chunks (~800ms) after speech
                // drops below threshold so Gemini server VAD can commit end-of-turn before pausing.
                var dataSaverHangoverRemaining = 0

                while (isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        errorCount = 0
                        val chunk = ByteArray(bytesRead)
                        var maxSample = 0
                        var i = 0
                        val gain = micGain
                        if (gain == 1.0f) {
                            System.arraycopy(buffer, 0, chunk, 0, bytesRead)
                            while (i < bytesRead - 1) {
                                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                                val absSample = abs(sample.toShort().toInt())
                                if (absSample > maxSample) maxSample = absSample
                                i += 2
                            }
                        } else {
                            while (i < bytesRead - 1) {
                                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                                val shortSample = sample.toShort().toInt()
                                val scaled = (shortSample * gain).toInt().coerceIn(-32768, 32767)
                                chunk[i] = scaled.toByte()
                                chunk[i + 1] = (scaled shr 8).toByte()
                                val absSample = abs(scaled)
                                if (absSample > maxSample) maxSample = absSample
                                i += 2
                            }
                        }
                        val amp = if (isMuted) 0f else (maxSample / 32768f).coerceIn(0f, 1f)
                        if (abs(amp - _micAmplitude.value) > 0.015f || (amp == 0f && _micAmplitude.value != 0f)) {
                            _micAmplitude.value = amp
                        }

                        // Dead-mic / silenced stream detection:
                        // Only trigger recovery if the mic has been completely flatline (amp=0.0) for an extended period
                        // (300 reads = ~30s), preventing spurious teardowns during natural conversational silence.
                        if (amp == 0f) {
                            zeroAmpStreak++
                            if (zeroAmpStreak >= 300 && !isMuted) {
                                AppLogger.w(TAG, "Microphone silence freeze detected ($zeroAmpStreak frames of amp=0.0). Attempting automatic recovery...")
                                zeroAmpStreak = 0
                                restartMicrophone(bufferSize)
                                continue
                            }
                        } else {
                            zeroAmpStreak = 0
                        }

                        if (!isMuted) {
                            // If the AI model is currently speaking:
                            if (isModelSpeaking) {
                                dataSaverHangoverRemaining = 0
                                // Allow barge-in / user interruption if speech is loud and clear
                                val isBargeIn = amp > (vadThreshold * 1.8f)
                                if (isBargeIn) {
                                    onSpeechStateChanged?.invoke(true)
                                    deliverChunk(chunk)
                                    chunkCount++
                                } else {
                                    onSpeechStateChanged?.invoke(false)
                                    // Suppress sending continuous speaker echo to Gemini
                                }
                                yield()
                                continue
                            }

                            val isSpeech = amp > vadThreshold
                            onSpeechStateChanged?.invoke(isSpeech)

                            if (isDataSaverEnabled) {
                                if (isSpeech) {
                                    deliverChunk(chunk)
                                    dataSaverHangoverRemaining = 8 // ~800ms hangover window (8 * 100ms) for clean turn-commit
                                    chunkCount++
                                } else if (dataSaverHangoverRemaining > 0) {
                                    dataSaverHangoverRemaining--
                                    deliverChunk(chunk)
                                    chunkCount++
                                }
                            } else {
                                // Full-speed continuous streaming (identical to Soltini Web):
                                // Real-time bidirectional voice models (Gemini Live) rely on continuous audio streaming
                                // and server-side neural VAD to detect natural turn boundaries with ultra-low latency (<500ms).
                                deliverChunk(chunk)
                                chunkCount++
                            }

                            if (chunkCount % 100 == 1) {
                                AppLogger.d(TAG, "AudioRecord streaming chunk #$chunkCount ($bytesRead bytes, amp=$amp)")
                            }
                        } else {
                            dataSaverHangoverRemaining = 0
                        }
                    } else if (bytesRead < 0) {
                        AppLogger.e(TAG, "AudioRecord read error code: $bytesRead")
                        errorCount++
                        delay(50)

                        if (errorCount > 5) {
                            AppLogger.w(TAG, "Consecutive audio errors. Attempting to restart microphone...")
                            errorCount = 0
                            restartMicrophone(bufferSize)
                        }
                    }
                    yield()
                }
            }
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "RECORD_AUDIO permission missing: ${e.message}", e)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error starting AudioRecord: ${e.message}", e)
        }
    }

    private fun deliverChunk(chunk: ByteArray) {
        chunkChannel?.trySend(chunk)
    }

    @SuppressLint("MissingPermission")
    private fun restartMicrophone(bufferSize: Int) {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        try {
            val primarySource = if (audioSourcePreference.equals("VOICE_COMMUNICATION", ignoreCase = true)) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            }
            val fallbackSource = if (primarySource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                MediaRecorder.AudioSource.MIC
            } else {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }

            audioRecord = AudioRecord(
                primarySource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = AudioRecord(
                    fallbackSource,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }
            try {
                if (AcousticEchoCanceler.isAvailable() && audioRecord != null) {
                    aec?.release()
                    aec = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                    aec?.enabled = true
                }
                if (NoiseSuppressor.isAvailable() && audioRecord != null) {
                    ns?.release()
                    ns = NoiseSuppressor.create(audioRecord!!.audioSessionId)
                    ns?.enabled = true
                }
            } catch (_: Exception) {}

            audioRecord?.startRecording()
            AppLogger.i(TAG, "Microphone restarted successfully.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to restart microphone: ${e.message}")
        }
    }

    private fun applySoftGain(pcmData: ByteArray, multiplier: Float): ByteArray {
        if (multiplier == 1.0f) return pcmData
        val boosted = ByteArray(pcmData.size)
        var i = 0
        while (i < pcmData.size - 1) {
            val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)
            val shortSample = sample.toShort().toInt()
            val scaled = (shortSample * multiplier).toInt().coerceIn(-32768, 32767)
            boosted[i] = scaled.toByte()
            boosted[i + 1] = (scaled shr 8).toByte()
            i += 2
        }
        return boosted
    }

    private fun calculateAmplitude(pcmData: ByteArray): Float {
        var maxSample = 0
        var i = 0
        while (i < pcmData.size - 1) {
            val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)
            val absSample = abs(sample.toShort().toInt())
            if (absSample > maxSample) {
                maxSample = absSample
            }
            i += 2
        }
        val norm = if (isMuted) 0f else (maxSample / 32768f).coerceIn(0f, 1f)
        _micAmplitude.value = norm
        return norm
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        chunkDeliveryJob?.cancel()
        chunkDeliveryJob = null
        chunkChannel?.close()
        chunkChannel = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
            aec?.release()
            ns?.release()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping AudioRecord: ${e.message}", e)
        }
        audioRecord = null
        aec = null
        ns = null
        _micAmplitude.value = 0f
        AppLogger.i(TAG, "AudioRecord stopped")
    }
}

