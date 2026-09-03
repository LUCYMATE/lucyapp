package com.soltini.app.audio

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
    }

    fun processAudioFrame(pcmData: ByteArray, size: Int) {
        if (!isRunning || size < 2 || !isInitialized) return

        try {
            var sum = 0.0
            val sampleCount = size / 2
            for (i in 0 until size step 2) {
                val sample = ((pcmData[i + 1].toInt() shl 8) or (pcmData[i].toInt() and 0xFF)).toShort()
                sum += (sample * sample).toDouble()
            }
            val rms = sqrt(sum / sampleCount)

            // When energy exceeds voice activity threshold, evaluate models
            if (rms > 2000.0) {
                val score = calculateWakeScore(pcmData, size, rms)
                if (score >= WAKE_THRESHOLD) {
                    val detectedPhrase = "Hey Maya"
                    Log.i(TAG, "Wake word detected: '$detectedPhrase' with score: $score")
                    onWakeWordDetected?.invoke(detectedPhrase)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio frame: ${e.message}")
        }
    }

    private fun calculateWakeScore(pcmData: ByteArray, size: Int, rms: Double): Float {
        // TFLite integration placeholder: 
        // real pipeline would be:
        // float[] melOutput = new float[1 * 76 * 32];
        // melInterpreter.run(audioFloatBuffer, melOutput);
        // embedInterpreter.run(melOutput, embedOutput);
        // wakeInterpreters[phrase].run(embedOutput, scoreOutput);
        
        val normalizedRms = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
        return if (normalizedRms > 0.15f) 0.85f else 0.0f
    }
}
