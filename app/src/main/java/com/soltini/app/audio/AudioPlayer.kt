package com.soltini.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.soltini.app.core.SoltiniDispatchers
import com.soltini.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Handles playing raw 24kHz 16-bit mono PCM audio chunks received from Gemini Live API.
 */
class AudioPlayer(private val context: Context? = null) {

    private val playerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var audioFocusRequest: AudioFocusRequest? = null

    private fun requestFocus() {
        if (context == null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    val playbackAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(playbackAttributes)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener { /* no-op */ }
                        .build()
                }
                am.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Audio focus request failed: ${e.message}")
        }
    }

    private fun abandonFocus() {
        if (context == null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Audio focus abandon failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 24000
    }

    private var audioTrack: AudioTrack? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var playbackJob: Job? = null
    private var isPlaying = false
    @Volatile private var totalFramesWritten = 0L
    @Volatile private var queueEmptySince = 0L

    private val _speakerAmplitude = MutableStateFlow(0f)
    val speakerAmplitude: StateFlow<Float> = _speakerAmplitude.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    fun start() {
        if (isPlaying) return

        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val bufferSize = maxOf(minBufferSize * 2, SAMPLE_RATE / 4) // ~250ms low-latency buffer

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            // Ensure sound comes from the loud bottom media speaker, NOT the earpiece
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null) {
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val speakerDevice = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speakerDevice != null) {
                        audioTrack?.preferredDevice = speakerDevice
                        AppLogger.i(TAG, "AudioTrack preferred device set to TYPE_BUILTIN_SPEAKER")
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to set AudioTrack preferred device: ${e.message}")
                }
            }

            audioTrack?.play()
            isPlaying = true
            AppLogger.i(TAG, "AudioTrack initialized and playing at 24000Hz")

            startPlaybackLoop()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error initializing AudioTrack: ${e.message}", e)
        }
    }

    private fun startPlaybackLoop() {
        playbackJob = CoroutineScope(SupervisorJob() + SoltiniDispatchers.AudioPlaybackDispatcher).launch {
            var playChunkCount = 0
            totalFramesWritten = 0L
            queueEmptySince = 0L

            while (isPlaying) {
                // Poll with minimal timeout: wakes up immediately when a new audio chunk is offered
                val chunk = audioQueue.poll(if (_isSpeaking.value) 5 else 15, TimeUnit.MILLISECONDS)
                if (chunk != null && chunk.isNotEmpty()) {
                    if (!_isSpeaking.value) {
                        _isSpeaking.value = true
                        // Request audio focus asynchronously on playerScope so it never blocks writing the first chunk
                        playerScope.launch {
                            requestFocus()
                        }
                    }
                    queueEmptySince = 0L
                    calculateAmplitude(chunk)

                    val written = audioTrack?.write(chunk, 0, chunk.size) ?: 0
                    if (written < 0) {
                        AppLogger.e(TAG, "AudioTrack write error code: $written")
                    } else {
                        // Track total PCM frames written (2 bytes per frame for 16-bit mono)
                        totalFramesWritten += written / 2
                        playChunkCount++
                        if (playChunkCount % 50 == 1) {
                            AppLogger.d(TAG, "Writing bytes to AudioTrack: chunk #$playChunkCount (${chunk.size} bytes)")
                        }
                    }
                    yield()
                } else {
                    // Queue is empty — but audio may still be playing in hardware buffer.
                    // Calculate how many frames the hardware has actually played using playbackHeadPosition.
                    if (_isSpeaking.value) {
                        if (queueEmptySince == 0L) {
                            queueEmptySince = System.currentTimeMillis()
                        }
                        val track = audioTrack
                        val hardwareDone = if (track != null) {
                            val headPosition = track.playbackHeadPosition.toLong()
                            // playbackHeadPosition wraps at Int.MAX_VALUE — normalise it
                            val normHead = headPosition and 0xFFFFFFFFL
                            normHead >= (totalFramesWritten and 0xFFFFFFFFL)
                        } else {
                            true
                        }
                        val timeEmpty = System.currentTimeMillis() - queueEmptySince
                        // Either hardware DAC completed the playback (80ms debounce) OR
                        // safety timeout (350ms) elapsed so mic is never blocked by stalled HAL positions
                        if ((hardwareDone && timeEmpty >= 80) || timeEmpty >= 350) {
                            // Audio has fully drained from the hardware DAC
                            _isSpeaking.value = false
                            _speakerAmplitude.value = 0f
                            abandonFocus()
                            totalFramesWritten = 0L
                            queueEmptySince = 0L
                            AppLogger.d(TAG, "AudioTrack hardware drain complete — mic unmuting (timeEmpty=${timeEmpty}ms)")
                        }
                    }
                    delay(5)
                }
            }
        }
    }

    fun playChunk(chunk: ByteArray) {
        if (!isPlaying) start()
        audioQueue.add(chunk)
    }

    /**
     * Called when Gemini signals turnComplete so playback can drain swiftly.
     */
    fun markTurnComplete() {
        if (audioQueue.isEmpty() && queueEmptySince == 0L) {
            queueEmptySince = System.currentTimeMillis()
        }
    }

    /**
     * Called when model response is interrupted by user barge-in.
     * Immediately halts playback and clears pending audio buffer.
     */
    fun interrupt() {
        AppLogger.i(TAG, "Interrupted! Clearing audio queue and flushing track")
        audioQueue.clear()
        try {
            audioTrack?.let { track ->
                track.pause()
                track.flush()
                track.play()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error flushing AudioTrack on interrupt: ${e.message}", e)
        }
        _isSpeaking.value = false
        _speakerAmplitude.value = 0f
        totalFramesWritten = 0L
        queueEmptySince = 0L
        abandonFocus()
    }

    private fun calculateAmplitude(pcmData: ByteArray) {
        var maxSample = 0
        var i = 0
        while (i < pcmData.size - 1) {
            val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)
            val shortSample = sample.toShort()
            val absSample = abs(shortSample.toInt())
            if (absSample > maxSample) {
                maxSample = absSample
            }
            i += 2
        }
        val norm = (maxSample / 32768f).coerceIn(0f, 1f)
        _speakerAmplitude.value = norm
    }

    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        playerScope.coroutineContext.cancelChildren()
        audioQueue.clear()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error releasing AudioTrack: ${e.message}", e)
        }
        audioTrack = null
        _isSpeaking.value = false
        _speakerAmplitude.value = 0f
        abandonFocus()
        AppLogger.i(TAG, "AudioTrack stopped")
    }
}
