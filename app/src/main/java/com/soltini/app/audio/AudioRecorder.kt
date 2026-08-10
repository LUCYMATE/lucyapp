package com.soltini.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.soltini.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Captures microphone audio at 16kHz 16-bit mono PCM for Gemini Live API streaming.
 */
class AudioRecorder(
    private val onAudioChunkCaptured: (ByteArray) -> Unit
) {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    var isMuted = false

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
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.w(TAG, "VOICE_COMMUNICATION failed, falling back to MIC source")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
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

            audioRecord?.startRecording()
            isRecording = true
            AppLogger.i(TAG, "AudioRecord started recording at 16000Hz PCM 16-bit")

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(2048)
                var chunkCount = 0
                while (isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)
                        calculateAmplitude(chunk)

                        if (!isMuted) {
                            onAudioChunkCaptured(chunk)
                            chunkCount++
                            if (chunkCount % 100 == 1) {
                                AppLogger.d(TAG, "AudioRecord captured chunk #$chunkCount ($bytesRead bytes)")
                            }
                        }
                    } else if (bytesRead < 0) {
                        AppLogger.e(TAG, "AudioRecord read error code: $bytesRead")
                        Thread.sleep(20)
                    }
                }
            }
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "RECORD_AUDIO permission missing: ${e.message}", e)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error starting AudioRecord: ${e.message}", e)
        }
    }

    private fun calculateAmplitude(pcmData: ByteArray) {
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
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping AudioRecord: ${e.message}", e)
        }
        audioRecord = null
        _micAmplitude.value = 0f
        AppLogger.i(TAG, "AudioRecord stopped")
    }
}

