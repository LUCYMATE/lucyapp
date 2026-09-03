package com.soltini.app.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.InputStreamReader

/**
 * MacroRunner
 *
 * Executes declarative multi-step macro workflows from builtin_macros.json.
 * Supports parameterized replacements, optional steps, and dynamic screen condition polling.
 */
object MacroRunner {

    private const val TAG = "MacroRunner"

    data class MacroStep(
        val tool: String,
        val args: Map<String, Any>,
        val optional: Boolean = false,
        val why: String = ""
    )

    data class Macro(
        val name: String,
        val intent: String,
        val params: List<String>,
        val steps: List<MacroStep>
    )

    private val macros = mutableMapOf<String, Macro>()

    fun loadMacros(context: Context) {
        try {
            val assetStream = context.assets.open("macros/builtin_macros.json")
            val jsonStr = InputStreamReader(assetStream).use { it.readText() }
            val root = JSONObject(jsonStr)
            val macroArray = root.optJSONArray("macros") ?: return

            macros.clear()
            for (i in 0 until macroArray.length()) {
                val mObj = macroArray.getJSONObject(i)
                val name = mObj.getString("name").lowercase().trim()
                val intent = mObj.optString("intent", "")
                val paramsList = mutableListOf<String>()
                val paramsJson = mObj.optJSONArray("params")
                if (paramsJson != null) {
                    for (p in 0 until paramsJson.length()) {
                        paramsList.add(paramsJson.getString(p))
                    }
                }

                val stepsList = mutableListOf<MacroStep>()
                val stepsJson = mObj.optJSONArray("steps")
                if (stepsJson != null) {
                    for (s in 0 until stepsJson.length()) {
                        val sObj = stepsJson.getJSONObject(s)
                        val tool = sObj.getString("tool")
                        val argsObj = sObj.optJSONObject("args") ?: JSONObject()
                        val argsMap = mutableMapOf<String, Any>()
                        val keys = argsObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            argsMap[k] = argsObj.get(k)
                        }
                        val optional = sObj.optBoolean("_optional", argsObj.optBoolean("_optional", false))
                        val why = sObj.optString("_why", "")
                        stepsList.add(MacroStep(tool, argsMap, optional, why))
                    }
                }

                macros[name] = Macro(name, intent, paramsList, stepsList)
            }
            Log.i(TAG, "Successfully loaded ${macros.size} macros from builtin_macros.json")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load builtin_macros.json: ${e.message}", e)
        }
    }

    fun getAvailableMacros(): List<String> = macros.keys.toList()

    fun getMacro(name: String): Macro? = macros[name.lowercase().trim()]

    suspend fun executeMacro(
        context: Context,
        macroName: String,
        paramValues: Map<String, String>,
        a11y: SoltiniAccessibilityService?,
        toolExecutor: AgentToolExecutor
    ): String {
        if (macros.isEmpty()) {
            loadMacros(context)
        }

        val macro = getMacro(macroName)
            ?: return "Error: Macro '$macroName' not found. Available: ${getAvailableMacros().joinToString()}"

        Log.i(TAG, "Starting execution of macro: '${macro.name}' with params: $paramValues")

        for ((index, step) in macro.steps.withIndex()) {
            Log.d(TAG, "Step [${index + 1}/${macro.steps.size}]: ${step.tool} (optional=${step.optional})")
            val success = executeStep(context, step, paramValues, a11y, toolExecutor)
            if (!success && !step.optional) {
                val errorMsg = "Macro '${macro.name}' failed at step ${index + 1} (${step.tool})."
                Log.w(TAG, errorMsg)
                return errorMsg
            }
        }

        Log.i(TAG, "Macro '${macro.name}' completed successfully.")
        return "Macro '${macro.name}' executed successfully."
    }

    private suspend fun executeStep(
        context: Context,
        step: MacroStep,
        paramValues: Map<String, String>,
        a11y: SoltiniAccessibilityService?,
        toolExecutor: AgentToolExecutor
    ): Boolean {
        return try {
            when (step.tool) {
                "open_app_by_name" -> {
                    val appName = substitute(step.args["name"]?.toString() ?: "", paramValues)
                    val res = toolExecutor.openApp(appName)
                    res.optString("status") == "opened"
                }

                "wait" -> {
                    val seconds = (step.args["seconds"] as? Number)?.toLong() ?: 2L
                    delay(seconds * 1000L)
                    true
                }

                "tap_text" -> {
                    if (a11y == null) return false
                    val text = substitute(step.args["text"]?.toString() ?: "", paramValues)
                    a11y.clickByText(text)
                }

                "type_text" -> {
                    if (a11y == null) return false
                    val rawText = step.args["text"]?.toString() ?: ""
                    val text = substitute(rawText, paramValues)
                    a11y.typeText(text)
                }

                "wait_for_screen_text" -> {
                    if (a11y == null) return false
                    val text = substitute(step.args["text"]?.toString() ?: "", paramValues)
                    val mode = step.args["mode"]?.toString() ?: "appear"
                    val timeout = (step.args["timeout_seconds"] as? Number)?.toInt() ?: 60
                    a11y.waitForScreenText(text, mode, timeout)
                }

                "tap_coordinates" -> {
                    if (a11y == null) return false
                    val x = (step.args["x"] as? Number)?.toFloat() ?: 0f
                    val y = (step.args["y"] as? Number)?.toFloat() ?: 0f
                    a11y.tapAt(x, y)
                }

                "go_back" -> {
                    a11y?.pressBack() ?: false
                }

                "go_home" -> {
                    a11y?.pressHome() ?: false
                }

                else -> {
                    Log.w(TAG, "Unknown macro tool: ${step.tool}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during macro step ${step.tool}: ${e.message}", e)
            false
        }
    }

    private fun substitute(template: String, params: Map<String, String>): String {
        var result = template
        for ((key, value) in params) {
            result = result.replace("{$key}", value)
            result = result.replace("{${key.replace(" ", "_")}}", value)
        }
        return result
    }
}
