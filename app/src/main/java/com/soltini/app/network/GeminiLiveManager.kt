package com.soltini.app.network

import android.util.Base64
import android.util.Log
import com.soltini.app.BuildConfig
import com.soltini.app.util.AppLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ConnectionState {
    Disconnected,
    Connecting,
    Live,
    Reconnecting,
    Error
}

interface GeminiLiveListener {
    fun onAudioDataReceived(pcmData: ByteArray)
    fun onTextReceived(text: String)
    fun onInterrupted()
    fun onTurnComplete()
    fun onError(message: String)
}

/**
 * Manages the persistent WebSocket connection to Gemini Live API.
 * Uses exact model string: gemini-3.1-flash-live-preview
 * Endpoint: wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=API_KEY
 */
class GeminiLiveManager(
    private val listener: GeminiLiveListener
) {

    companion object {
        private const val TAG = "GeminiLiveManager"
        private const val MODEL_STRING = "gemini-3.1-flash-live-preview"
        private const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    @Volatile private var isSetupComplete = false

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var shouldAutoReconnect = true
    private var reconnectJob: Job? = null
    private var audioChunksSentCount = 0
    private var droppedChunksCount = 0

    fun connect() {
        if (_connectionState.value == ConnectionState.Connecting ||
            _connectionState.value == ConnectionState.Live
        ) {
            AppLogger.d(TAG, "Already connected or connecting")
            return
        }

        shouldAutoReconnect = true
        _connectionState.value = ConnectionState.Connecting
        _errorMessage.value = null

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            val err = "Gemini API key is not configured in Secrets panel or .env file"
            AppLogger.e(TAG, err)
            _connectionState.value = ConnectionState.Error
            _errorMessage.value = err
            listener.onError(err)
            return
        }

        val url = "$WS_BASE_URL?key=$apiKey"

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .build()

        AppLogger.i(TAG, "Initiating WebSocket connection with model $MODEL_STRING")
        webSocket = client?.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLogger.i(TAG, "WebSocket onOpen successfully connected!")
                _connectionState.value = ConnectionState.Live
                _errorMessage.value = null
                audioChunksSentCount = 0

                // Step 1: Send setup message immediately after onOpen
                sendSetupMessage(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                AppLogger.d(TAG, "WebSocket onMessage (text) received: $text")
                try {
                    handleServerMessage(text)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error parsing server message: ${e.message}", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                AppLogger.d(TAG, "WebSocket onMessage (bytes) received (${bytes.size} bytes): $text")
                try {
                    handleServerMessage(text)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error parsing binary server message: ${e.message}", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.w(TAG, "WebSocket onClosing: code=$code, reason=$reason")
                _connectionState.value = ConnectionState.Reconnecting
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.w(TAG, "WebSocket onClosed: code=$code, reason=$reason")
                _connectionState.value = ConnectionState.Disconnected
                if (shouldAutoReconnect) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val responseBodyStr = try {
                    response?.body?.string() ?: "None"
                } catch (e: Exception) {
                    "Error reading body: ${e.message}"
                }

                val failureLog = "WebSocket onFailure: message=${t.message}, HTTP code=${response?.code}, responseBody=$responseBodyStr"
                AppLogger.e(TAG, failureLog, t)

                _errorMessage.value = failureLog
                _connectionState.value = ConnectionState.Error
                listener.onError(t.message ?: "Connection failed")

                if (shouldAutoReconnect) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket) {
        try {
            isSetupComplete = false
            val setupObj = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", "models/$MODEL_STRING")
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply {
                            put("AUDIO")
                        })
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", "Aoede")
                                })
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "You are a polite, slightly flirty, and highly proper AI assistant. Speak with elegance, charm, and a touch of playful flirtation, but always remain respectful and helpful. Speak with a natural South Asian (Nepali) accent. Keep responses concise for live voice conversation.")
                            })
                        })
                    })
                })
            }

            val setupString = setupObj.toString()
            AppLogger.i(TAG, "Sending setup message: $setupString")
            ws.send(setupString)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create setup JSON: ${e.message}", e)
        }
    }

    private fun handleServerMessage(jsonText: String) {
        val root = JSONObject(jsonText)
        val keys = root.keys().asSequence().toList()
        AppLogger.d(TAG, "Received server message (keys: $keys): $jsonText")

        if (root.has("setupComplete")) {
            AppLogger.i(TAG, "Received setupComplete from server! Gate open.")
            isSetupComplete = true
        }

        if (root.has("serverContent")) {
            val serverContent = root.getJSONObject("serverContent")

            // Check if user barged in / interrupted
            if (serverContent.optBoolean("interrupted", false)) {
                AppLogger.i(TAG, "Server flagged interrupted=true")
                listener.onInterrupted()
            }

            // Check if turn complete
            if (serverContent.optBoolean("turnComplete", false)) {
                AppLogger.i(TAG, "Server flagged turnComplete=true")
                listener.onTurnComplete()
            }

            // Extract model turn content
            if (serverContent.has("modelTurn")) {
                val modelTurn = serverContent.getJSONObject("modelTurn")
                val parts = modelTurn.optJSONArray("parts") ?: JSONArray()

                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)

                    // Audio part
                    if (part.has("inlineData")) {
                        val inlineData = part.getJSONObject("inlineData")
                        val base64Data = inlineData.optString("data")
                        if (base64Data.isNotEmpty()) {
                            val pcmBytes = Base64.decode(base64Data, Base64.NO_WRAP)
                            AppLogger.d(TAG, "Received audio payload: ${pcmBytes.size} PCM bytes")
                            listener.onAudioDataReceived(pcmBytes)
                        }
                    }

                    // Text transcript part
                    if (part.has("text")) {
                        val textPart = part.optString("text")
                        if (textPart.isNotEmpty()) {
                            AppLogger.i(TAG, "Received text transcript: $textPart")
                            listener.onTextReceived(textPart)
                        }
                    }
                }
            }
        }
    }

    private val pendingChunks = mutableListOf<ByteArray>()

    /**
     * Sends user microphone audio chunk (16kHz PCM 16-bit) as realtimeInput.
     */
    fun sendAudioChunk(pcmChunk: ByteArray) {
        if (!isSetupComplete) {
            if (pendingChunks.size < 200) { // Limit buffer to ~200 chunks to prevent memory issues if setup never completes
                pendingChunks.add(pcmChunk)
            }
            droppedChunksCount++
            if (droppedChunksCount % 50 == 1) {
                AppLogger.w(TAG, "Chunk captured, gate open: isSetupComplete=$isSetupComplete (buffered $droppedChunksCount chunks waiting for setupComplete)")
            }
            return
        }
        if (_connectionState.value != ConnectionState.Live) {
            return
        }

        // Flush pending chunks first
        if (pendingChunks.isNotEmpty()) {
            val chunksToFlush = pendingChunks.toList()
            pendingChunks.clear()
            for (chunk in chunksToFlush) {
                sendToWs(chunk)
            }
        }

        sendToWs(pcmChunk)
    }

    private fun sendToWs(pcmChunk: ByteArray) {
        val ws = webSocket ?: return
        try {
            val base64Audio = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
            val message = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("data", base64Audio)
                        put("mimeType", "audio/pcm;rate=16000")
                    })
                })
            }
            ws.send(message.toString())
            audioChunksSentCount++
            if (audioChunksSentCount % 50 == 1) {
                AppLogger.d(TAG, "Chunk sent: #$audioChunksSentCount (${pcmChunk.size} bytes)")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error sending audio chunk: ${e.message}", e)
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            _connectionState.value = ConnectionState.Reconnecting
            AppLogger.i(TAG, "Auto-reconnecting in 3 seconds...")
            delay(3000)
            if (shouldAutoReconnect) {
                connect()
            }
        }
    }

    fun disconnect() {
        isSetupComplete = false
        shouldAutoReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null

        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error closing WebSocket: ${e.message}", e)
        }
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        _connectionState.value = ConnectionState.Disconnected
        AppLogger.i(TAG, "Disconnected session")
    }
}

