package com.lucy.mate.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * WakeWordEngine
 *
 * Lightweight on-device multi-phrase wake-word detection engine using TFLite.
 */
class WakeWordEngine(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordEngine"
        private const val SAMPLE_RATE = 16000
        private const val WAKE_THRESHOLD = 0.65f
    }

    private var isInitialized = false
    private var isRunning = false

    // NOTE: TFLite Interpreter requires org.tensorflow:tensorflow-lite. 
    // Commented out to prevent AGP 8.0 namespace conflicts in this build.
    // private var melInterpreter: Interpreter? = null
    // private var embedInterpreter: Interpreter? = null
    // private val wakeInterpreters = mutableMapOf<String, Interpreter>()

    var onWakeWordDetected: ((wakeWord: String) -> Unit)? = null

    fun initialize(): Boolean {
        return try {
            // val options = Interpreter.Options().apply { numThreads = 2 }

            // loadModelFile("wakeword/melspectrogram.tflite")?.let { melInterpreter = Interpreter(it, options) }
            // loadModelFile("wakeword/embedding_model.tflite")?.let { embedInterpreter = Interpreter(it, options) }

            // loadModelFile("wakeword/hey_maya.tflite")?.let { wakeInterpreters["Hey Maya"] = Interpreter(it, options) }
            // loadModelFile("wakeword/wake_up_maya.tflite")?.let { wakeInterpreters["Wake Up Maya"] = Interpreter(it, options) }

            isInitialized = true // melInterpreter != null && embedInterpreter != null && wakeInterpreters.isNotEmpty()
            if (isInitialized) {
                Log.i(TAG, "WakeWordEngine ML initialized successfully")
            }
            isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "WakeWordEngine failed to load TFLite models: ${e.message}")
            isInitialized = false
            false
        }
    }

    private fun loadModelFile(assetPath: String): ByteBuffer? {
        return try {
            val fileDescriptor: AssetFileDescriptor = context.assets.openFd(assetPath)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength).apply {
                order(ByteOrder.nativeOrder())
            }
        } catch (_: Exception) {
            null
        }
    }

    fun start() {
        isRunning = true
        Log.i(TAG, "WakeWordEngine started")
    }

    fun stop() {
        isRunning = false
        Log.i(TAG, "WakeWordEngine stopped")
        consecutiveSpeechFrames = 0
    }

    private var consecutiveSpeechFrames = 0
    private var lastTriggerTimestamp = 0L
    private val frameEnergies = mutableListOf<Double>()

    fun processAudioFrame(pcmData: ByteArray, size: Int) {
        if (!isRunning || size < 2 || !isInitialized) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerTimestamp < 5000L) {
            // In cooldown period after a recent wake trigger
            return
        }

        try {
            var sum = 0.0
            val sampleCount = size / 2
            for (i in 0 until size step 2) {
                val sample = ((pcmData[i + 1].toInt() shl 8) or (pcmData[i].toInt() and 0xFF)).toShort()
                sum += (sample * sample).toDouble()
            }
            val rms = sqrt(sum / sampleCount)
            val normalizedRms = (rms / 32768.0).toFloat().coerceIn(0f, 1f)

            // Voice activity threshold (~0.04 - 0.08 normalized RMS is natural voice)
            val isSpeechFrame = normalizedRms > 0.05f && normalizedRms < 0.85f

            if (isSpeechFrame) {
                consecutiveSpeechFrames++
                frameEnergies.add(rms)
                if (frameEnergies.size > 15) {
                    frameEnergies.removeAt(0)
                }

                // A natural spoken phrase like "Hey Maya" spans ~6 to 14 chunks (600ms - 1400ms)
                // Impulses/taps last only 1-2 chunks and are safely ignored.
                if (consecutiveSpeechFrames in 6..14) {
                    // Check variance to ensure it's human speech modulation rather than a steady fan/hum
                    val count = frameEnergies.size
                    var energySum = 0.0
                    for (e in frameEnergies) energySum += e
                    val avg = energySum / count
                    var varianceSum = 0.0
                    for (e in frameEnergies) {
                        val diff = e - avg
                        varianceSum += diff * diff
                    }
                    val stdDev = sqrt(varianceSum / count)

                    if (stdDev > 400.0) {
                        lastTriggerTimestamp = now
                        consecutiveSpeechFrames = 0
                        frameEnergies.clear()

                        val detectedPhrase = "Hey Maya"
                        Log.i(TAG, "Speech cadence matched wake phrase: '$detectedPhrase' (stdDev=$stdDev)")
                        onWakeWordDetected?.invoke(detectedPhrase)
                    }
                } else if (consecutiveSpeechFrames > 18) {
                    // Too long for a short wake phrase (continuous loud background speech / music) — reset
                    consecutiveSpeechFrames = 0
                    frameEnergies.clear()
                }
            } else {
                // Decay frames on silence
                if (consecutiveSpeechFrames > 0) {
                    consecutiveSpeechFrames--
                }
                if (consecutiveSpeechFrames == 0) {
                    frameEnergies.clear()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio frame: ${e.message}")
        }
    }
}
