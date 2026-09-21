package com.lucy.mate.network

import android.util.Base64
import android.util.Log
import com.lucy.mate.BuildConfig
import com.lucy.mate.core.SoltiniDispatchers
import com.lucy.mate.memory.MemoryManager
import com.lucy.mate.settings.AppSettings
import com.lucy.mate.util.AppLogger
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
 * ToolCallListener — called when Gemini requests execution of a local tool.
 * The implementor (BackgroundVoiceService) executes the action and must
 * call GeminiLiveManager.sendToolResponse() with the result.
 */
interface ToolCallListener {
    fun onToolCall(callId: String, toolName: String, args: JSONObject)
}

/**
 * Manages the persistent WebSocket connection to Gemini Live API.
 * Uses exact model string: gemini-3.1-flash-live-preview
 * Endpoint: wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=API_KEY
 */
class GeminiLiveManager(
    private val listener: GeminiLiveListener,
    private val toolCallListener: ToolCallListener? = null,
    private val appSettings: AppSettings? = null,
    private val memoryManager: MemoryManager? = null
) {
    private var cachedMemoryBlock: String = ""

    companion object {
        private const val TAG = "GeminiLiveManager"
        private const val MODEL_STRING = "gemini-3.1-flash-live-preview"
        private const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
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

    // Dedicated coroutine scope for reconnect scheduling and WS operations on isolated LiveWsDispatcher
    private val managerScope = CoroutineScope(SoltiniDispatchers.LiveWsDispatcher + SupervisorJob())

    fun connect() {
        if (_connectionState.value == ConnectionState.Connecting ||
            _connectionState.value == ConnectionState.Live
        ) {
            AppLogger.d(TAG, "Already connected or connecting")
            return
        }

        // Reset all stale state from a previous session before opening a new connection.
        // This is critical for clean reconnects after a service restart.
        shouldAutoReconnect = true
        isSetupComplete = false
        synchronized(pendingChunks) { pendingChunks.clear() }
        droppedChunksCount = 0
        audioChunksSentCount = 0
        _connectionState.value = ConnectionState.Connecting
        _errorMessage.value = null

        val apiKey = appSettings?.effectiveApiKey() ?: BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            val err = "Gemini API key is not configured in Secrets panel or .env file"
            AppLogger.e(TAG, err)
            _connectionState.value = ConnectionState.Error
            _errorMessage.value = err
            listener.onError(err)
            return
        }

        val url = "$WS_BASE_URL?key=$apiKey"

        // Pre-fetch memories on IO BEFORE opening the WebSocket so the onOpen callback
        // can call sendSetupMessage() without any blocking call.
        managerScope.launch {
            cachedMemoryBlock = try {
                memoryManager?.buildMemoryPrompt() ?: ""
            } catch (e: Exception) {
                AppLogger.w(TAG, "Memory pre-fetch failed (non-fatal): ${e.message}")
                ""
            }
            openWebSocket(url)
        }
    }

    private fun openWebSocket(url: String) {
        val request = Request.Builder()
            .url(url)
            .build()

        AppLogger.i(TAG, "Initiating WebSocket connection with model $MODEL_STRING")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLogger.i(TAG, "WebSocket onOpen successfully connected! Waiting for setupComplete...")
                // Keep ConnectionState.Connecting until Gemini confirms setupComplete
                _errorMessage.value = null
                audioChunksSentCount = 0

                // Step 1: Send setup message immediately after onOpen
                sendSetupMessage(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // AppLogger.d(TAG, "WebSocket onMessage (text) received: $text")
                try {
                    handleServerMessage(text)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error parsing server message: ${e.message}", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                // AppLogger.d(TAG, "WebSocket onMessage (bytes) received (${bytes.size} bytes)")
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

            // Build system instruction: user persona + tool docs + memory
            val persona = appSettings?.aiPersona?.trim()?.ifBlank { null }
                ?: AppSettings.DEFAULT_PERSONA
            val voiceName = appSettings?.voiceName ?: AppSettings.DEFAULT_VOICE

            // Use the pre-fetched memory block — zero blocking, zero delay on the WS thread.
            val memoryBlock = cachedMemoryBlock

            val leanSkillPrompt = """

CORE INSTRUCTIONS:
You are Soltini, a voice-first real-time AI companion developed by Satish Acharya (www.satish.com.np).
Speak naturally, warmly, and concisely (1-2 short spoken sentences).
When the user asks to perform ANY phone action, inspect the screen, open apps, make calls, adjust volume/flashlight/wifi/bluetooth, or automate tasks, call `delegate_skill` immediately.

TOOL DELEGATION PARAMETERS (`delegate_skill`):
- skill_name: 'device_control', 'screen_vision', 'open_app', 'phone_call', 'youtube', 'whatsapp', 'reels', 'automation', 'system'
- action: specific action to perform:
  * Device & Hardware: 'toggle_flashlight' (details: 'on' or 'off'), 'set_volume' (details: 'up', 'down', 'mute', or '0-100'), 'set_brightness' (details: '0-100'), 'toggle_wifi' ('on'/'off'), 'toggle_bluetooth' ('on'/'off'), 'lock_device', 'wake_device', 'press_back', 'press_home', 'press_recents'
  * Screen Perception: 'see_screen', 'read_screen', 'take_screenshot', 'click_element', 'type_text', 'scroll_down', 'scroll_up'
  * Apps & Comms: 'open_app' (details: app name), 'make_phone_call' (details: contact or number), 'send_sms' (details: contact: message), 'read_contacts', 'read_call_log', 'set_alarm', 'get_location', 'open_google_maps'
  * Media: 'youtube_search', 'youtube_play_pause', 'youtube_next', 'whatsapp_send_message', 'next_reel'
- details: parameters, app name, contact, message, text, query, or 'on'/'off'

CRITICAL ERROR AND STATUS REPORTING:
When a tool response or system notification is received:
1. If the tool response indicates 'status': 'in_progress', give an immediate, ultra-brief spoken acknowledgment (e.g. "On it!", "Sure thing!", "Working on that!"). Keep it under 4 words so the response latency is instantaneous.
2. When you receive a [SYSTEM NOTIFICATION: Action ... succeeded ...]:
   Speak naturally and confirm what was done and how it was completed in 1 short spoken sentence (e.g., "I've turned on the flashlight.", "Volume is set to 80%.").
3. When you receive a [SYSTEM NOTIFICATION: Action ... failed ...]:
   You MUST clearly speak up and explain to the user what went wrong, why it failed, and what is needed to fix it (e.g., "I couldn't turn on the flashlight because camera permission is missing", "I couldn't find the app on your device"). Give the exact reason so the user knows what went wrong and how to fix it. Never falsely claim a task succeeded if it failed.
4. If you hear ambient chatter, TV noise, or voices not speaking directly to you, stay silent and do NOT call any tools."""

            val fullSystemInstruction = "$persona$memoryBlock$leanSkillPrompt"

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
                                    put("voiceName", voiceName)
                                })
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", fullSystemInstruction)
                            })
                        })
                    })
                    put("tools", JSONArray().apply {
                        put(JSONObject().apply {
                            put("functionDeclarations", JSONArray().apply {
                                // Single unified meta-tool for lightning-fast speech-to-speech (<400ms TTFT)
                                put(JSONObject().apply {
                                    put("name", "delegate_skill")
                                    put("description", "Delegates phone actions, screen perception, app navigation, media playback, messaging, phone calls, and device control to the background skill system.")
                                    put("parameters", JSONObject().apply {
                                        put("type", "OBJECT")
                                        put("properties", JSONObject().apply {
                                            put("skill_name", JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "The name or category of the skill (e.g. 'screen_vision', 'phone_call', 'open_app', 'device_control', 'youtube', 'whatsapp', 'reels', 'automation', 'system')")
                                            })
                                            put("action", JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "The specific action to execute (e.g. 'call', 'see_screen', 'read_screen', 'open', 'click', 'play', 'pause', 'send_message', 'lock', 'type', 'next')")
                                            })
                                            put("details", JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Detailed parameter, target element text, query, contact name, message, or natural language request")
                                            })
                                        })
                                        put("required", JSONArray().apply {
                                            put("skill_name")
                                            put("action")
                                        })
                                    })
                                })
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
        AppLogger.d(TAG, "Received server message (keys: $keys)")

        if (root.has("setupComplete")) {
            AppLogger.i(TAG, "Received setupComplete from server! Gate open.")
            isSetupComplete = true
            _connectionState.value = ConnectionState.Live
            flushPreRollChunks()
        }

        // ── Tool calls from Gemini ──────────────────────────────────────────
        // Gemini sends toolCall when it wants to execute a registered function.
        if (root.has("toolCall")) {
            val toolCall = root.getJSONObject("toolCall")
            val functionCalls = toolCall.optJSONArray("functionCalls") ?: return
            for (i in 0 until functionCalls.length()) {
                val fc = functionCalls.getJSONObject(i)
                val callId = fc.optString("id", "")
                val name = fc.optString("name", "")
                val args = fc.optJSONObject("args") ?: JSONObject()
                AppLogger.i(TAG, "Tool call received: id=$callId, name=$name, args=$args")
                toolCallListener?.onToolCall(callId, name, args)
            }
            return
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

    /**
     * Sends the result of a tool execution back to Gemini so it can continue
     * the conversation with the real-world outcome.
     */
    fun sendToolResponse(callId: String, name: String, result: JSONObject) {
        val ws = webSocket ?: return
        if (!isSetupComplete || _connectionState.value != ConnectionState.Live) return
        try {
            val message = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", callId)
                            put("name", name)
                            put("response", JSONObject().apply {
                                put("output", result)
                            })
                        })
                    })
                })
            }
            ws.send(message.toString())
            AppLogger.i(TAG, "Sent toolResponse for callId=$callId: $result")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error sending toolResponse: ${e.message}", e)
        }
    }

    /**
     * Injects a background system notification into the conversation (e.g. when automation finishes).
     */
    fun sendClientContent(text: String) {
        val ws = webSocket ?: return
        if (!isSetupComplete || _connectionState.value != ConnectionState.Live) return
        try {
            val message = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", text)
                                })
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }
            ws.send(message.toString())
            AppLogger.i(TAG, "Sent clientContent: $text")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error sending clientContent: ${e.message}", e)
        }
    }

    private val pendingChunks = mutableListOf<ByteArray>()

    private fun flushPreRollChunks() {
        val chunksToFlush: List<ByteArray>
        synchronized(pendingChunks) {
            if (pendingChunks.isEmpty()) return
            chunksToFlush = pendingChunks.toList()
            pendingChunks.clear()
        }
        for (chunk in chunksToFlush) {
            sendToWs(chunk)
        }
        AppLogger.i(TAG, "Flushed ${chunksToFlush.size} pre-roll chunks to Gemini")
    }

    /**
     * Sends user microphone audio chunk (16kHz PCM 16-bit) as realtimeInput.
     */
    fun sendAudioChunk(pcmChunk: ByteArray) {
        if (!isSetupComplete || _connectionState.value != ConnectionState.Live) {
            synchronized(pendingChunks) {
                // Circular pre-roll: keep at most 4 chunks (~400ms) to preserve the
                // initial spoken syllable without creating a massive stale audio burst.
                if (pendingChunks.size >= 4) {
                    pendingChunks.removeAt(0)
                }
                pendingChunks.add(pcmChunk)
            }
            droppedChunksCount++
            if (droppedChunksCount % 50 == 1) {
                AppLogger.w(TAG, "Chunk captured, gate waiting: isSetupComplete=$isSetupComplete (buffered ${pendingChunks.size} pre-roll chunks)")
            }
            return
        }

        // Flush any remaining pre-roll chunks first
        if (pendingChunks.isNotEmpty()) {
            flushPreRollChunks()
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
        // Use the class-level managerScope instead of creating a new leaked scope every time
        reconnectJob = managerScope.launch {
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
        synchronized(pendingChunks) { pendingChunks.clear() }

        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error closing WebSocket: ${e.message}", e)
        }
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        AppLogger.i(TAG, "Disconnected session")
    }
}
