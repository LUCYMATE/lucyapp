package com.soltini.app.agent.skills

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * SkillRegistry — Central dynamic registry for all autonomous agent skills in Soltini.
 *
 * Holds default built-in skills, tracks live enabled/disabled states in SharedPreferences,
 * and manages user-created custom skills.
 */
class SkillRegistry private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _skillsFlow = MutableStateFlow<List<Skill>>(emptyList())
    val skillsFlow: StateFlow<List<Skill>> = _skillsFlow.asStateFlow()

    init {
        _skillsFlow.value = getAllSkills()
    }

    /**
     * Returns all skills (built-in + custom) with current persisted enabled states.
     */
    fun getAllSkills(): List<Skill> {
        val builtIns = DEFAULT_BUILT_IN_SKILLS.map { skill ->
            val enabled = prefs.getBoolean(KEY_ENABLED_PREFIX + skill.id, true)
            skill.copy(isEnabled = enabled)
        }
        val customs = loadCustomSkills()
        return builtIns + customs
    }

    /**
     * Returns only currently active/enabled skills.
     */
    fun getActiveSkills(): List<Skill> = getAllSkills().filter { it.isEnabled }

    /**
     * Toggles a skill's active status and persists the state.
     */
    fun toggleSkill(id: String, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED_PREFIX + id, enabled).apply()
        val customs = loadCustomSkills().toMutableList()
        val index = customs.indexOfFirst { it.id == id }
        if (index != -1) {
            customs[index] = customs[index].copy(isEnabled = enabled)
            saveCustomSkills(customs)
        }
        _skillsFlow.value = getAllSkills()
        Log.i(TAG, "Toggled skill $id -> enabled=$enabled")
    }

    /**
     * Adds a user-defined custom skill.
     */
    fun addCustomSkill(skill: Skill) {
        val customs = loadCustomSkills().toMutableList()
        customs.removeAll { it.id == skill.id }
        customs.add(skill)
        saveCustomSkills(customs)
        prefs.edit().putBoolean(KEY_ENABLED_PREFIX + skill.id, skill.isEnabled).apply()
        _skillsFlow.value = getAllSkills()
        Log.i(TAG, "Added custom skill: ${skill.name} (${skill.id})")
    }

    /**
     * Deletes a custom skill by its ID.
     */
    fun deleteCustomSkill(id: String) {
        val customs = loadCustomSkills().toMutableList()
        customs.removeAll { it.id == id }
        saveCustomSkills(customs)
        prefs.edit().remove(KEY_ENABLED_PREFIX + id).apply()
        _skillsFlow.value = getAllSkills()
        Log.i(TAG, "Deleted custom skill: $id")
    }

    /**
     * Searches skills matching a query across name, description, category, tools, and trigger keywords.
     */
    fun searchSkills(query: String): List<Skill> {
        val clean = query.trim().lowercase()
        if (clean.isBlank()) return getAllSkills()
        return getAllSkills().filter { skill ->
            skill.name.lowercase().contains(clean) ||
            skill.description.lowercase().contains(clean) ||
            skill.category.name.lowercase().contains(clean) ||
            skill.tools.any { it.lowercase().contains(clean) } ||
            skill.triggerKeywords.any { it.lowercase().contains(clean) }
        }
    }

    private fun loadCustomSkills(): List<Skill> {
        val jsonStr = prefs.getString(KEY_CUSTOM_SKILLS, null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Skill>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val skill = Skill.fromJson(obj)
                val enabled = prefs.getBoolean(KEY_ENABLED_PREFIX + skill.id, skill.isEnabled)
                list.add(skill.copy(isEnabled = enabled))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse custom skills JSON", e)
            emptyList()
        }
    }

    private fun saveCustomSkills(customs: List<Skill>) {
        try {
            val array = JSONArray()
            for (skill in customs) {
                array.put(skill.toJson())
            }
            prefs.edit().putString(KEY_CUSTOM_SKILLS, array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom skills JSON", e)
        }
    }

    companion object {
        private const val TAG = "SkillRegistry"
        private const val PREFS_NAME = "soltini_skill_registry"
        private const val KEY_ENABLED_PREFIX = "skill_enabled_"
        private const val KEY_CUSTOM_SKILLS = "custom_skills_json"

        @Volatile
        private var INSTANCE: SkillRegistry? = null

        fun getInstance(context: Context): SkillRegistry {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillRegistry(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * The 8 Core Built-in Skills of Soltini.
         */
        val DEFAULT_BUILT_IN_SKILLS: List<Skill> = listOf(
            // 1. ScreenVisionSkill
            Skill(
                id = "skill_screen_vision",
                name = "Screen Vision",
                description = "Perceive screen layout, read text hierarchy, and inspect UI elements",
                category = SkillCategory.VISION,
                icon = "👁️",
                isDefault = true,
                isEnabled = true,
                tools = listOf("see_screen", "read_screen", "take_screenshot"),
                triggerKeywords = listOf("see screen", "read screen", "screenshot", "what is on my screen", "view screen")
            ),

            // 2. WhatsAppSkill
            Skill(
                id = "skill_whatsapp",
                name = "WhatsApp Assistant",
                description = "Send WhatsApp messages and initiate hands-free voice/video calls",
                category = SkillCategory.COMMUNICATION,
                icon = "💬",
                isDefault = true,
                isEnabled = true,
                tools = listOf("whatsapp_send_message", "whatsapp_call"),
                triggerKeywords = listOf("whatsapp", "send message", "whatsapp call", "message on whatsapp")
            ),

            // 3. YouTubeSkill
            Skill(
                id = "skill_youtube",
                name = "YouTube Controller",
                description = "Search videos, play/pause, seek playback, and auto-skip advertisements",
                category = SkillCategory.MEDIA,
                icon = "▶️",
                isDefault = true,
                isEnabled = true,
                tools = listOf("youtube_search", "youtube_play_pause", "youtube_seek", "youtube_skip_ad"),
                triggerKeywords = listOf("youtube", "play video", "skip ad", "seek", "pause youtube")
            ),

            // 4. ReelsSkill
            Skill(
                id = "skill_reels",
                name = "Shorts & Reels Autopilot",
                description = "Hands-free scrolling for Instagram Reels, YouTube Shorts, and TikTok",
                category = SkillCategory.MEDIA,
                icon = "📱",
                isDefault = true,
                isEnabled = true,
                tools = listOf("next_reel", "previous_reel"),
                triggerKeywords = listOf("next reel", "previous reel", "scroll reel", "next short", "swipe video")
            ),

            // 5. PhoneCallSkill
            Skill(
                id = "skill_phone_call",
                name = "Phone & Contacts",
                description = "Place cellular phone calls via SIM, answer calls, and search contacts",
                category = SkillCategory.COMMUNICATION,
                icon = "📞",
                isDefault = true,
                isEnabled = true,
                tools = listOf("make_phone_call", "answer_call", "read_contacts"),
                triggerKeywords = listOf("call", "dial", "answer phone", "contacts", "phone call")
            ),

            // 6. DeviceControlSkill
            Skill(
                id = "skill_device_control",
                name = "Device Control",
                description = "App launching, screen lock/wake, home/back/recents, volume and flashlight",
                category = SkillCategory.SYSTEM,
                icon = "⚙️",
                isDefault = true,
                isEnabled = true,
                tools = listOf("open_app", "lock_device", "wake_device", "press_home", "press_back", "press_recents", "set_volume", "toggle_flashlight"),
                triggerKeywords = listOf("open app", "lock screen", "wake screen", "go home", "go back", "recents", "volume", "flashlight")
            ),

            // 7. TermuxSkill
            Skill(
                id = "skill_termux",
                name = "Termux Shell Bridge",
                description = "Execute terminal commands and bash scripts in background Termux environment",
                category = SkillCategory.SYSTEM,
                icon = "⚡",
                isDefault = true,
                isEnabled = true,
                tools = listOf("run_termux_command"),
                triggerKeywords = listOf("termux", "terminal", "bash", "shell command", "run script")
            ),

            // 8. MacroAutomationSkill
            Skill(
                id = "skill_macro_automation",
                name = "Macro Automation",
                description = "Execute pre-recorded macros, sequences, and retrieve automated workflows",
                category = SkillCategory.SYSTEM,
                icon = "🪄",
                isDefault = true,
                isEnabled = true,
                tools = listOf("run_macro", "get_available_macros"),
                triggerKeywords = listOf("macro", "run macro", "workflow", "routine", "automate")
            )
        )
    }
}
