package com.lucy.mate.memory

import android.util.Log
import com.lucy.mate.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * MemoryManager — high-level facade for Soltini's vector memory system.
 *
 * Responsibilities:
 *   1. saveMemory(text)    — embed via Gemini API, store in SQLite
 *   2. retrieveRelevant()  — cosine-similarity search over stored vectors
 *   3. buildMemoryPrompt() — format top-K memories for injection into system instruction
 *   4. extractAndSave()    — parse conversation text, pull out precious facts, save them
 */
class MemoryManager(
    private val db: MemoryDatabase,
    private val appSettings: AppSettings
) {

    companion object {
        private const val TAG = "MemoryManager"
        private const val EMBED_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent"
        private const val EXTRACT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"
        private const val FALLBACK_EXTRACT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent"
        private const val TOP_K = 10   // memories injected per session
        private const val MIN_SIMILARITY = 0.55f  // ignore unrelated memories
    }

    private val _memoriesFlow = MutableStateFlow<List<MemoryDatabase.Memory>>(emptyList())
    val memoriesFlow: StateFlow<List<MemoryDatabase.Memory>> = _memoriesFlow.asStateFlow()

    init {
        refreshMemories()
    }

    fun refreshMemories() {
        _memoriesFlow.value = db.getAllMemories()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Embed [text] via Gemini API and store in memory database.
     * Runs on Dispatchers.IO — safe to call from coroutine scope.
     */
    suspend fun saveMemory(text: String, importance: Float = 0.6f) = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return@withContext
        val embedding = embedText(trimmed)
        db.insertMemory(trimmed, embedding, importance)
        refreshMemories()
    }

    /**
     * Retrieves the top-K memories most semantically similar to [query].
     * Returns an empty list if embeddings fail (no crash, just no memory).
     */
    suspend fun retrieveRelevant(query: String, topK: Int = TOP_K): List<MemoryDatabase.Memory> =
        withContext(Dispatchers.IO) {
            val queryVec = embedText(query) ?: run {
                Log.w(TAG, "Could not embed query — returning all recent memories")
                return@withContext db.getAllMemories().take(topK)
            }
            val all = db.getAllMemories()
            if (all.isEmpty()) return@withContext emptyList()

            // Rank by cosine similarity, filter by threshold
            val ranked = all
                .mapNotNull { mem ->
                    val vec = mem.embedding ?: return@mapNotNull null
                    val sim = cosineSimilarity(queryVec, vec)
                    if (sim >= MIN_SIMILARITY) Pair(mem, sim) else null
                }
                .sortedByDescending { it.second }
                .take(topK)

            // Update access counts for recalled memories
            ranked.forEach { (mem, _) -> db.recordAccess(mem.id) }

            ranked.map { it.first }
        }

    /**
     * Formats retrieved memories as a system-instruction block to inject into
     * Gemini's setup message.
     * Loads local memories directly without blocking network latency on startup.
     *
     * Returns empty string if there are no memories.
     */
    suspend fun buildMemoryPrompt(contextQuery: String = "user preferences and personal info"): String = withContext(Dispatchers.IO) {
        val memories = db.getAllMemories().take(TOP_K)
        if (memories.isEmpty()) return@withContext ""
        val lines = memories.joinToString("\n") { "- ${it.content}" }
        "\n\nWHAT YOU REMEMBER ABOUT THIS USER:\n$lines\n"
    }

    /**
     * Given a completed conversation turn (Soltini's reply text and user input), calls
     * hybrid heuristics and Gemini Flash to extract precious user-specific facts, then saves each one.
     *
     * This is called after [onTurnComplete] in BackgroundVoiceService.
     */
    suspend fun extractAndSave(conversationText: String) = withContext(Dispatchers.IO) {
        if (conversationText.isBlank()) return@withContext

        // 1. Instant regex-based heuristic extraction (zero network latency, guaranteed accuracy for direct facts)
        try {
            val directPatterns = listOf(
                Regex("(?i)\\bmy name is\\s+([A-Za-z]+(?:\\s+[A-Za-z]+)?)") to "User's name is %s",
                Regex("(?i)\\bcall me\\s+([A-Za-z]+(?:\\s+[A-Za-z]+)?)") to "User prefers to be called %s",
                Regex("(?i)\\bi (?:really\\s+)?(?:like|love|enjoy|prefer)\\s+([^.,\n\r!?]+)") to "User likes %s",
                Regex("(?i)\\bmy favorite\\s+([A-Za-z]+)\\s+is\\s+([^.,\n\r!?]+)") to "User's favorite %s is %s",
                Regex("(?i)\\bi (?:live|reside|stay)\\s+in\\s+([A-Za-z\\s]+)") to "User lives in %s",
                Regex("(?i)\\bi am from\\s+([A-Za-z\\s]+)") to "User is from %s",
                Regex("(?i)\\bmy (?:birthday|bday) is\\s+([^.,\n\r!?]+)") to "User's birthday is %s"
            )

            for ((regex, template) in directPatterns) {
                regex.find(conversationText)?.let { match ->
                    val fact = if (match.groupValues.size == 2) {
                        template.format(match.groupValues[1].trim())
                    } else if (match.groupValues.size >= 3) {
                        template.format(match.groupValues[1].trim(), match.groupValues[2].trim())
                    } else null

                    if (fact != null && fact.isNotBlank()) {
                        val existing = db.getAllMemories().map { it.content.lowercase().trim() }
                        if (existing.none { it.contains(fact.lowercase()) || fact.lowercase().contains(it) }) {
                            Log.i(TAG, "Heuristic memory extracted: $fact")
                            saveMemory(fact, importance = 0.85f)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Heuristic extraction notice: ${e.message}")
        }

        // 2. LLM-based deep fact extraction using Gemini Flash
        val apiKey = appSettings.effectiveApiKey()
        if (apiKey.isBlank()) return@withContext

        try {
            val prompt = """You are a memory extractor. From the following conversation excerpt, extract ONLY concrete facts specifically about the USER (not general knowledge).

Rules:
- Extract: user's name, preferences, habits, goals, relationships, important personal events
- DO NOT extract: questions the user asked, commands they gave, general statements
- Return a JSON array of short fact strings (max 15 words each)
- If nothing worth remembering, return an empty array []

Conversation:
$conversationText

Return only valid JSON array, no explanation."""

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
            }.toString()

            var responseBody: String? = null
            for (endpoint in listOf(EXTRACT_URL, FALLBACK_EXTRACT_URL)) {
                try {
                    val req = Request.Builder()
                        .url("$endpoint?key=$apiKey")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = client.newCall(req).execute()
                    if (response.isSuccessful) {
                        responseBody = response.body?.string()
                        if (!responseBody.isNullOrBlank()) break
                    } else {
                        Log.w(TAG, "Memory extraction attempt with $endpoint failed: HTTP ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Endpoint $endpoint failed: ${e.message}")
                }
            }

            if (responseBody.isNullOrBlank()) return@withContext

            val candidates = JSONObject(responseBody).optJSONArray("candidates")
            val rawText = candidates
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text") ?: return@withContext

            // Parse JSON array of facts
            val facts = try {
                val arr = JSONArray(rawText.trim().removePrefix("```json").removeSuffix("```").trim())
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse extracted facts: ${e.message}")
                return@withContext
            }

            Log.i(TAG, "Extracted ${facts.size} facts from conversation via Gemini")
            val existing = db.getAllMemories().map { it.content.lowercase().trim() }
            facts.forEach { fact ->
                val trimmed = fact.trim()
                if (trimmed.isNotBlank() && trimmed.length < 200) {
                    if (existing.none { it.contains(trimmed.lowercase()) || trimmed.lowercase().contains(it) }) {
                        saveMemory(trimmed, importance = 0.75f)
                    }
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "Memory extraction error (non-fatal): ${e.message}")
        }
    }

    fun getCount(): Int = db.getCount()
    fun getAllMemories(): List<MemoryDatabase.Memory> = db.getAllMemories()
    fun clearAll() {
        db.clearAll()
        refreshMemories()
    }
    fun deleteMemory(id: Long): Boolean {
        val result = db.deleteMemory(id)
        refreshMemories()
        return result
    }

    // ── Embedding ─────────────────────────────────────────────────────────────

    private fun embedText(text: String): FloatArray? {
        val apiKey = appSettings.effectiveApiKey()
        if (apiKey.isBlank()) return null

        return try {
            val body = JSONObject().apply {
                put("model", "models/text-embedding-004")
                put("content", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", text) })
                    })
                })
            }.toString()

            val req = Request.Builder()
                .url("$EMBED_URL?key=$apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "Embedding failed: HTTP ${resp.code}")
                return null
            }

            val arr = JSONObject(resp.body!!.string())
                .getJSONObject("embedding")
                .getJSONArray("values")
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }

        } catch (e: Exception) {
            Log.w(TAG, "Embedding error (non-fatal): ${e.message}")
            null
        }
    }

    // ── Math ──────────────────────────────────────────────────────────────────

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0.0; var normA = 0.0; var normB = 0.0
        for (i in a.indices) {
            dot  += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }
}
