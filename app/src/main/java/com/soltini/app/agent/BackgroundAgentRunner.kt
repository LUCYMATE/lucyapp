package com.soltini.app.agent

import android.content.Context
import android.util.Log
import com.soltini.app.agent.skills.SkillRegistry
import com.soltini.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BackgroundAgentRunner — Tier 2 Silent Background Automation Agent
 *
 * Receives delegated automation tasks from the Tier 1 Live Voice Agent and executes
 * them autonomously using Android accessibility, app control, screen automation,
 * YouTube, WhatsApp, and Reels automations.
 *
 * Configured with static Gemini Flash endpoints (gemini-3-flash-preview and gemini-2.5-flash),
 * dynamic skill-based tool filtering from SkillRegistry, and a search_skills meta-tool.
 */
class BackgroundAgentRunner(
    private val context: Context,
    private val agentToolExecutor: AgentToolExecutor,
    private val appSettings: AppSettings,
    private val skillRegistry: SkillRegistry = SkillRegistry.getInstance(context)
) {

    companion object {
        private const val TAG = "BackgroundAgentRunner"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val PRIMARY_MODEL = "gemini-3.5-flash"
        private const val FALLBACK_MODEL = "gemini-3-flash-preview"
        private const val MAX_STEPS = 6
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Executes the requested automation command autonomously in the background.
     * Returns a concise human-readable summary of what was accomplished.
     */
    suspend fun execute(requestText: String): String = withContext(Dispatchers.IO) {
        val apiKey = appSettings.effectiveApiKey()
        if (apiKey.isBlank()) {
            Log.e(TAG, "Cannot execute background task — API key is missing")
            return@withContext "Error: API key is not configured in Settings."
        }

        Log.i(TAG, "Starting background agent for task: \"$requestText\"")

        val activeSkills = skillRegistry.getActiveSkills()
        val activeToolNames = activeSkills.flatMap { it.tools }.toSet()
        val activeSkillsSummary = activeSkills.joinToString(", ") { "${it.name} (${it.category.name})" }

        val systemPrompt = """You are Soltini's Background Android Automation Agent.
You are the silent execution engine running on an Android device.
Your job is to execute the user's requested phone automation task cleanly and swiftly using your tools.

ACTIVE SKILLS: $activeSkillsSummary

### USER REQUEST:
"$requestText"

## EXECUTION RULES:
1. **Direct Execution**: Output tool calls immediately based on active skills. If asked to make a call, open an app, or control media, call the corresponding tool in your first turn.
2. **Skill Discovery**: You have the meta-tool `search_skills`. Use it if you need to discover available skills, their triggers, or verify capabilities.
3. **Phone Calls**:
   - For regular phone calls ("call Dad", "call John", "dial [number]", "make a phone call"): ALWAYS call `make_phone_call` (Cellular SIM phone call).
   - ONLY call `whatsapp_call` if the user explicitly requested "WhatsApp" or "call on WhatsApp".
4. **Screen Perception & Vision**:
   - When vision skill is active: Inspect the screen using `see_screen`, `read_screen`, or `take_screenshot`.
5. **Multi-Step Tasks**:
   - For WhatsApp: call `whatsapp_send_message` or `whatsapp_call`.
   - For YouTube: call `youtube_search`, `youtube_play_pause`, `youtube_seek`, `youtube_skip_ad`.
   - For Reels: call `next_reel` or `previous_reel`.
   - For Device Control: call `open_app`, `lock_device`, `wake_device`, `press_home`, `press_back`, `press_recents`, `set_volume`, `toggle_flashlight`.
   - For Terminal: call `run_termux_command`.
   - For Macros: call `run_macro` or `get_available_macros`.
6. **User Control**: Never perform unexpected screen actions or navigation outside of the explicit request: "$requestText".
7. **Completion**: When the entire task is finished, return a brief, friendly 1-2 sentence summary of what was done. Do NOT call any more tools once finished."""

        val contents = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", "Execute task: $requestText") })
                })
            })
        }

        var currentModel = PRIMARY_MODEL
        val toolsDeclaration = buildToolsDeclaration(activeToolNames)
        var finalResult = ""

        for (step in 1..MAX_STEPS) {
            val a11y = SoltiniAccessibilityService.getInstance()
            if (a11y != null && a11y.isUserInteractingManually()) {
                Log.i(TAG, "Step $step: User is interacting manually with screen. Pausing background agent.")
                return@withContext "Paused task because you are interacting with your screen."
            }

            Log.d(TAG, "Execution step $step/$MAX_STEPS using model $currentModel")

            val requestBody = JSONObject().apply {
                put("contents", contents)
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemPrompt) })
                    })
                })
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put("functionDeclarations", toolsDeclaration)
                    })
                })
            }

            var responseJson: JSONObject? = null
            val modelsToTry = if (currentModel == PRIMARY_MODEL) {
                listOf(PRIMARY_MODEL, FALLBACK_MODEL)
            } else {
                listOf(currentModel, FALLBACK_MODEL).distinct()
            }

            for (m in modelsToTry) {
                try {
                    responseJson = callGeminiGenerateContent(m, apiKey, requestBody)
                    currentModel = m
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Model $m failed at step $step: ${e.message}")
                }
            }

            if (responseJson == null) {
                Log.e(TAG, "Null response from Gemini API at step $step")
                break
            }

            val candidates = responseJson.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val candidateContent = candidate?.optJSONObject("content")
            val parts = candidateContent?.optJSONArray("parts")

            if (parts == null || parts.length() == 0) {
                Log.w(TAG, "No parts returned by model at step $step")
                break
            }

            // Check if there are function calls
            val functionCalls = mutableListOf<JSONObject>()
            var textContent = ""

            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.has("functionCall")) {
                    functionCalls.add(part.getJSONObject("functionCall"))
                }
                if (part.has("text")) {
                    textContent += part.getString("text") + " "
                }
            }

            if (functionCalls.isNotEmpty()) {
                // Append model turn with tool calls to conversation history
                contents.put(candidateContent)

                // Execute each tool and build tool response turn
                val responseParts = JSONArray()
                for (fc in functionCalls) {
                    val name = fc.optString("name", "")
                    val args = fc.optJSONObject("args") ?: JSONObject()
                    Log.i(TAG, "Step $step: Tool call -> $name with args $args")

                    if (a11y != null && a11y.isUserInteractingManually()) {
                        Log.i(TAG, "User started manual touch during tool execution. Aborting remaining tools.")
                        responseParts.put(JSONObject().apply {
                            put("functionResponse", JSONObject().apply {
                                put("name", name)
                                put("response", JSONObject().apply {
                                    put("output", JSONObject().apply {
                                        put("status", "user_active_backoff")
                                        put("message", "User is currently interacting manually with the screen.")
                                    })
                                })
                            })
                        })
                        continue
                    }

                    val execResult = try {
                        if (name == "search_skills") {
                            val query = args.optString("query", "")
                            val matched = skillRegistry.searchSkills(query)
                            val jsonArray = JSONArray()
                            matched.forEach { skill ->
                                jsonArray.put(JSONObject().apply {
                                    put("id", skill.id)
                                    put("name", skill.name)
                                    put("description", skill.description)
                                    put("category", skill.category.name)
                                    put("isEnabled", skill.isEnabled)
                                    put("tools", JSONArray(skill.tools))
                                    put("triggerKeywords", JSONArray(skill.triggerKeywords))
                                })
                            }
                            JSONObject().apply {
                                put("status", "success")
                                put("skills", jsonArray)
                                put("count", matched.size)
                            }
                        } else {
                            agentToolExecutor.execute(name, args)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool execution exception for $name: ${e.message}", e)
                        JSONObject().apply { put("error", e.message ?: "Execution failed") }
                    }

                    responseParts.put(JSONObject().apply {
                        put("functionResponse", JSONObject().apply {
                            put("name", name)
                            put("response", JSONObject().apply {
                                put("output", execResult)
                            })
                        })
                    })
                }

                // Append tool results to conversation history
                contents.put(JSONObject().apply {
                    put("role", "tool")
                    put("parts", responseParts)
                })

            } else {
                // Model has completed and provided final text output
                finalResult = textContent.trim()
                Log.i(TAG, "Background agent completed in $step step(s): $finalResult")
                break
            }
        }

        if (finalResult.isBlank()) {
            finalResult = "Done! Successfully completed the task: $requestText"
        }

        return@withContext finalResult
    }

    private fun callGeminiGenerateContent(model: String, apiKey: String, body: JSONObject): JSONObject {
        val url = "$BASE_URL/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw RuntimeException("Gemini HTTP ${response.code}: $responseBody")
            }
            return JSONObject(responseBody)
        }
    }

    /**
     * Builds Gemini tool declarations dynamically based ONLY on active skills in SkillRegistry,
     * plus the search_skills meta-tool.
     */
    private fun buildToolsDeclaration(activeTools: Set<String>): JSONArray = JSONArray().apply {
        // Meta-Tool: search_skills is ALWAYS declared so the agent can discover dynamic skills
        put(funcDecl("search_skills", "Discover available phone automation skills, capabilities, and tools installed on the device",
            objProps("query" to strProp("Optional query to filter skills by name, keyword, or action (leave empty to list all skills)")),
            emptyList()
        ))

        fun includeIfActive(toolName: String, block: () -> JSONObject) {
            if (activeTools.contains(toolName)) {
                put(block())
            }
        }

        // ── Device Control Skills ──────────────────────────────────────────
        includeIfActive("open_app") {
            funcDecl("open_app", "Launch any installed app by name",
                objProps("app_name" to strProp("The name of the app to launch (e.g. WhatsApp, YouTube, Chrome, Settings)")),
                listOf("app_name")
            )
        }
        includeIfActive("lock_device") {
            funcDecl("lock_device", "Lock the device screen immediately", emptyObjProps())
        }
        includeIfActive("wake_device") {
            funcDecl("wake_device", "Turn the screen on", emptyObjProps())
        }
        includeIfActive("press_back") {
            funcDecl("press_back", "Simulate the Android Back button", emptyObjProps())
        }
        includeIfActive("press_home") {
            funcDecl("press_home", "Simulate the Android Home button", emptyObjProps())
        }
        includeIfActive("press_recents") {
            funcDecl("press_recents", "Open Android Recent Apps overview", emptyObjProps())
        }
        includeIfActive("set_volume") {
            funcDecl("set_volume", "Set the device media volume percentage (0 to 100)",
                objProps("level" to intProp("Volume level from 0 to 100")),
                listOf("level")
            )
        }
        includeIfActive("toggle_flashlight") {
            funcDecl("toggle_flashlight", "Turn device flashlight on or off",
                objProps("on" to boolProp("True to turn flashlight ON, False to turn OFF")),
                listOf("on")
            )
        }

        // ── Screen Vision Skills ───────────────────────────────────────────
        includeIfActive("see_screen") {
            funcDecl("see_screen", "Perceive the screen using both accessibility UI wireframe and visual screenshot",
                objProps("screenshot" to boolProp("Include visual screenshot. Defaults to true.")),
                emptyList()
            )
        }
        includeIfActive("read_screen") {
            funcDecl("read_screen", "Reads all visible UI text, buttons, and layout hierarchy currently displayed on the phone screen", emptyObjProps())
        }
        includeIfActive("take_screenshot") {
            funcDecl("take_screenshot", "Captures a visual screenshot of the current screen", emptyObjProps())
        }

        // ── YouTube Media Skills ───────────────────────────────────────────
        includeIfActive("youtube_search") {
            funcDecl("youtube_search", "Search for videos on YouTube",
                objProps("query" to strProp("Search terms")),
                listOf("query")
            )
        }
        includeIfActive("youtube_play_pause") {
            funcDecl("youtube_play_pause", "Toggle Play/Pause on current YouTube video", emptyObjProps())
        }
        includeIfActive("youtube_seek") {
            funcDecl("youtube_seek", "Seek forward or backward by seconds",
                objProps("seconds" to intProp("Seconds to seek (+ for forward, - for backward)")),
                listOf("seconds")
            )
        }
        includeIfActive("youtube_skip_ad") {
            funcDecl("youtube_skip_ad", "Skip YouTube ad if available", emptyObjProps())
        }

        // ── Reels & Shorts Skills ──────────────────────────────────────────
        includeIfActive("next_reel") {
            funcDecl("next_reel", "Swipe to next Short or Reel", emptyObjProps())
        }
        includeIfActive("previous_reel") {
            funcDecl("previous_reel", "Swipe to previous Short or Reel", emptyObjProps())
        }

        // ── Phone & Communications Skills ──────────────────────────────────
        includeIfActive("make_phone_call") {
            funcDecl("make_phone_call", "Place a standard cellular/SIM phone call to a contact name or phone number",
                objProps("phone_number" to strProp("Contact name or phone number to call via SIM card")),
                listOf("phone_number")
            )
        }
        includeIfActive("answer_call") {
            funcDecl("answer_call", "Answer an incoming phone call", emptyObjProps())
        }
        includeIfActive("read_contacts") {
            funcDecl("read_contacts", "Search phone contacts by name to find phone numbers",
                objProps("query" to strProp("Contact name to search")),
                listOf("query")
            )
        }

        // ── WhatsApp Skills ────────────────────────────────────────────────
        includeIfActive("whatsapp_send_message") {
            funcDecl("whatsapp_send_message", "Open WhatsApp, search contact, type and send message",
                objProps(
                    "contact_name" to strProp("Name of the contact"),
                    "message" to strProp("Message to send")
                ),
                listOf("contact_name", "message")
            )
        }
        includeIfActive("whatsapp_call") {
            funcDecl("whatsapp_call", "Place a voice or video call on WhatsApp",
                objProps(
                    "contact_name" to strProp("Name of the contact"),
                    "is_video" to boolProp("True for video call, False for voice call")
                ),
                listOf("contact_name")
            )
        }

        // ── Termux & Shell Skills ──────────────────────────────────────────
        includeIfActive("run_termux_command") {
            funcDecl("run_termux_command", "Execute bash commands or scripts via Termux background bridge",
                objProps(
                    "command" to strProp("Executable name or script path"),
                    "args" to arrProp("Command arguments array")
                ),
                listOf("command")
            )
        }

        // ── Macro Automation Skills ────────────────────────────────────────
        includeIfActive("run_macro") {
            funcDecl("run_macro", "Execute a predefined automation macro by name",
                objProps("macro_name" to strProp("Name of the macro to run")),
                listOf("macro_name")
            )
        }
        includeIfActive("get_available_macros") {
            funcDecl("get_available_macros", "Get a list of all saved automation macros on the device", emptyObjProps())
        }
    }

    private fun funcDecl(name: String, description: String, parameters: JSONObject, required: List<String> = emptyList()): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", parameters.apply {
                if (required.isNotEmpty()) {
                    put("required", JSONArray(required))
                }
            })
        }

    private fun emptyObjProps(): JSONObject = JSONObject().apply {
        put("type", "OBJECT")
        put("properties", JSONObject())
    }

    private fun objProps(vararg props: Pair<String, JSONObject>): JSONObject = JSONObject().apply {
        put("type", "OBJECT")
        put("properties", JSONObject().apply {
            for ((key, value) in props) {
                put(key, value)
            }
        })
    }

    private fun strProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "STRING")
        put("description", desc)
    }

    private fun numProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "NUMBER")
        put("description", desc)
    }

    private fun intProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "INTEGER")
        put("description", desc)
    }

    private fun boolProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "BOOLEAN")
        put("description", desc)
    }

    private fun arrProp(desc: String): JSONObject = JSONObject().apply {
        put("type", "ARRAY")
        put("description", desc)
        put("items", JSONObject().apply { put("type", "STRING") })
    }
}
