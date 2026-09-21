package com.lucy.mate.agent.skills

import org.json.JSONArray
import org.json.JSONObject

/**
 * SkillCategory — Functional categorization of autonomous device skills.
 */
enum class SkillCategory {
    COMMUNICATION,
    MEDIA,
    VISION,
    NAVIGATION,
    SYSTEM,
    CUSTOM
}

/**
 * Skill — Representation of an autonomous device capability or automation workflow.
 *
 * @param id Unique identifier (e.g. "skill_screen_vision", "custom_1725450000")
 * @param name Display name
 * @param description Summary of what the skill does
 * @param category SkillCategory grouping
 * @param icon Emoji or icon representation
 * @param isDefault Built-in native skill vs user-created custom skill
 * @param isEnabled Live toggle state (persisted in SharedPreferences)
 * @param tools List of tool names executable by this skill
 * @param triggerKeywords Example voice phrases or keywords triggering this skill
 * @param customPromptOrMacro Optional custom prompt instruction or macro sequence
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val category: SkillCategory,
    val icon: String,
    val isDefault: Boolean = true,
    var isEnabled: Boolean = true,
    val tools: List<String> = emptyList(),
    val triggerKeywords: List<String> = emptyList(),
    val customPromptOrMacro: String? = null
) {
    val toolCount: Int get() = tools.size

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("category", category.name)
        put("icon", icon)
        put("isDefault", isDefault)
        put("isEnabled", isEnabled)
        put("tools", JSONArray(tools))
        put("triggerKeywords", JSONArray(triggerKeywords))
        if (customPromptOrMacro != null) {
            put("customPromptOrMacro", customPromptOrMacro)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Skill {
            val toolsList = mutableListOf<String>()
            val toolsArray = json.optJSONArray("tools")
            if (toolsArray != null) {
                for (i in 0 until toolsArray.length()) {
                    toolsList.add(toolsArray.getString(i))
                }
            }

            val triggerList = mutableListOf<String>()
            val triggerArray = json.optJSONArray("triggerKeywords")
            if (triggerArray != null) {
                for (i in 0 until triggerArray.length()) {
                    triggerList.add(triggerArray.getString(i))
                }
            }

            val catName = json.optString("category", SkillCategory.CUSTOM.name)
            val category = try {
                SkillCategory.valueOf(catName)
            } catch (e: Exception) {
                SkillCategory.CUSTOM
            }

            return Skill(
                id = json.getString("id"),
                name = json.getString("name"),
                description = json.optString("description", ""),
                category = category,
                icon = json.optString("icon", "✨"),
                isDefault = json.optBoolean("isDefault", false),
                isEnabled = json.optBoolean("isEnabled", true),
                tools = toolsList,
                triggerKeywords = triggerList,
                customPromptOrMacro = if (json.has("customPromptOrMacro") && !json.isNull("customPromptOrMacro")) {
                    json.getString("customPromptOrMacro")
                } else null
            )
        }
    }
}
