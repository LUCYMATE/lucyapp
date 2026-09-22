package com.lucy.mate.ui.agent

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lucy.mate.agent.skills.Skill
import com.lucy.mate.agent.skills.SkillCategory
import com.lucy.mate.ui.MainViewModel
import com.lucy.mate.ui.settings.SettingsPermChecks
import com.lucy.mate.ui.theme.AmberConnecting
import com.lucy.mate.ui.theme.BlushSurfaceElevated
import com.lucy.mate.ui.theme.EmeraldLive
import com.lucy.mate.ui.theme.PearlWhiteSurface
import com.lucy.mate.ui.theme.PinkyBorder
import com.lucy.mate.ui.theme.PinkyBorderFocused
import com.lucy.mate.ui.theme.PinkyWhiteBackground
import com.lucy.mate.ui.theme.RosePink
import com.lucy.mate.ui.theme.RubyShield
import com.lucy.mate.ui.theme.SoftViolet
import com.lucy.mate.ui.theme.TextMuted
import com.lucy.mate.ui.theme.TextPrimary
import com.lucy.mate.ui.theme.TextSecondary
import com.lucy.mate.ui.theme.VioletLight

/**
 * AgentHubScreen — Dynamic Agent & Skill Hub
 *
 * Full management studio for Soltini's autonomous skills:
 * - Live summary header & accessibility status badge
 * - Category filter chips (All, Communication, Media, Vision, System, Custom)
 * - Real-time keyword & tool search bar
 * - Interactive skill cards with live ON/OFF switches, tool badges, and expandable trigger lists
 * - Custom skill creation dialog with persistent storage in SkillRegistry
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentHubScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    val skills by viewModel.skillRegistry.skillsFlow.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<SkillCategory?>(null) }
    var expandedSkillId by remember { mutableStateOf<String?>(null) }
    var showAddSkillDialog by remember { mutableStateOf(false) }

    val isA11yActive = remember { SettingsPermChecks.checkAccessibility(context) }
    val activeCount = remember(skills) { skills.count { it.isEnabled } }
    val totalCount = skills.size

    // Filter skills according to category and search query (memoized)
    val filteredSkills = remember(skills, selectedCategory, searchQuery) {
        val q = searchQuery.trim().lowercase()
        skills.filter { skill ->
            val matchesCategory = selectedCategory == null || skill.category == selectedCategory
            val matchesSearch = if (q.isBlank()) true else {
                skill.name.lowercase().contains(q) ||
                skill.description.lowercase().contains(q) ||
                skill.category.name.lowercase().contains(q) ||
                skill.tools.any { it.lowercase().contains(q) } ||
                skill.triggerKeywords.any { it.lowercase().contains(q) }
            }
            matchesCategory && matchesSearch
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = PinkyWhiteBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Header & Live Status Badges ─────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Agent & Skill Hub",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "Autonomous device controls & vision skills",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Accessibility Status Badge (clickable to open Settings if inactive)
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (isA11yActive) EmeraldLive.copy(alpha = 0.12f)
                            else AmberConnecting.copy(alpha = 0.15f)
                        )
                        .clickable {
                            if (!isA11yActive) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                } catch (e: Exception) {
                                    android.util.Log.w("AgentHubScreen", "Failed to open accessibility settings", e)
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isA11yActive) EmeraldLive else AmberConnecting)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isA11yActive) "A11y Active" else "Enable A11y",
                            color = if (isA11yActive) EmeraldLive else AmberConnecting,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // ── Active Skills Summary Card ──────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, PinkyBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(RosePink.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = RosePink, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$activeCount of $totalCount Skills Active",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Gemini Flash dynamically loads active tools",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    // "+ Add Skill" shortcut button in summary
                    Surface(
                        onClick = { showAddSkillDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        color = RosePink.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, PinkyBorder)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Add, null, tint = RosePink, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Add Skill",
                                color = RosePink,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // ── Search Bar ──────────────────────────────────────────────────────
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("Search skills, tools, or triggers...", color = TextMuted, fontSize = 13.sp)
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = RosePink, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = PearlWhiteSurface,
                    unfocusedContainerColor = PearlWhiteSurface,
                    focusedBorderColor = PinkyBorderFocused,
                    unfocusedBorderColor = PinkyBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
            )

            // ── Category Filter Chips ───────────────────────────────────────────
            val categories = listOf(
                null to "All",
                SkillCategory.COMMUNICATION to "Communication",
                SkillCategory.MEDIA to "Media",
                SkillCategory.VISION to "Vision",
                SkillCategory.SYSTEM to "System",
                SkillCategory.NAVIGATION to "Navigation",
                SkillCategory.CUSTOM to "Custom"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { (cat, label) ->
                    val isSelected = selectedCategory == cat
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) RosePink else PearlWhiteSurface)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) RosePink else PinkyBorder,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else TextPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // ── Skills List Header ──────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Configured Skills (${filteredSkills.size})",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                if (searchQuery.isNotBlank() || selectedCategory != null) {
                    Text(
                        text = "Reset Filters",
                        color = RosePink,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            searchQuery = ""
                            selectedCategory = null
                        }
                    )
                }
            }

            // ── Skill Cards ─────────────────────────────────────────────────────
            if (filteredSkills.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, PinkyBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🔍", fontSize = 32.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No matching skills found",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Try clearing search or create a new custom skill",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                filteredSkills.forEach { skill ->
                    val isExpanded = expandedSkillId == skill.id
                    SkillCard(
                        skill = skill,
                        isExpanded = isExpanded,
                        onToggleExpand = {
                            expandedSkillId = if (isExpanded) null else skill.id
                        },
                        onToggleActive = { enabled ->
                            viewModel.skillRegistry.toggleSkill(skill.id, enabled)
                        },
                        onDeleteCustomSkill = {
                            viewModel.skillRegistry.deleteCustomSkill(skill.id)
                        },
                        onRunTrigger = { trigger ->
                            viewModel.sendTextMessage(trigger)
                        }
                    )
                }
            }

            // ── "+ Add New Skill" Action Card ───────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAddSkillDialog = true },
                colors = CardDefaults.cardColors(containerColor = BlushSurfaceElevated),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, PinkyBorderFocused.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Add, null, tint = RosePink, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Create Custom Automation Skill",
                        color = RosePink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(30.dp))
        }
    }

    // ── Create Custom Skill Dialog ───────────────────────────────────────────
    if (showAddSkillDialog) {
        AddCustomSkillDialog(
            onDismiss = { showAddSkillDialog = false },
            onSave = { newSkill ->
                viewModel.skillRegistry.addCustomSkill(newSkill)
                showAddSkillDialog = false
            }
        )
    }
}

/**
 * SkillCard — Renders individual skill item with toggle switch, category badge,
 * tool count badge, and expandable triggers / tools overview.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillCard(
    skill: Skill,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    onDeleteCustomSkill: () -> Unit,
    onRunTrigger: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(200)),
        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (skill.isEnabled) PinkyBorder else PinkyBorder.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Icon, Name, Category & Count Badges, Interactive Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category Icon
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (skill.isEnabled) RosePink.copy(alpha = 0.12f)
                            else TextMuted.copy(alpha = 0.12f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = skill.icon, fontSize = 20.sp)
                }

                Spacer(Modifier.width(12.dp))

                // Name & Metadata
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = skill.name,
                            color = if (skill.isEnabled) TextPrimary else TextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(Modifier.height(3.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Category pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(SoftViolet.copy(alpha = 0.10f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = skill.category.name,
                                color = SoftViolet,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Tool count badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(RosePink.copy(alpha = 0.10f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${skill.toolCount} ${if (skill.toolCount == 1) "tool" else "tools"}",
                                color = RosePink,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Delete button for custom skills
                if (!skill.isDefault) {
                    IconButton(
                        onClick = onDeleteCustomSkill,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete skill",
                            tint = RubyShield,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Interactive Live Switch
                Switch(
                    checked = skill.isEnabled,
                    onCheckedChange = onToggleActive,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PearlWhiteSurface,
                        checkedTrackColor = RosePink,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = PinkyBorder
                    )
                )
            }

            Spacer(Modifier.height(8.dp))

            // Skill Description
            Text(
                text = skill.description,
                color = if (skill.isEnabled) TextSecondary else TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(10.dp))

            // Expandable click bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggleExpand() }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isExpanded) "Hide Tools & Triggers" else "View Tools & Voice Triggers",
                    color = RosePink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = RosePink,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Expanded details: tools and example voice commands
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = PinkyBorder, thickness = 1.dp)

                    Spacer(Modifier.height(10.dp))

                    // Tools executed
                    if (skill.tools.isNotEmpty()) {
                        Text(
                            text = "TOOLS EXECUTED",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            skill.tools.forEach { tool ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(BlushSurfaceElevated)
                                        .border(1.dp, PinkyBorder, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = tool,
                                        color = TextPrimary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    // Voice triggers & quick test
                    if (skill.triggerKeywords.isNotEmpty()) {
                        Text(
                            text = "VOICE TRIGGERS (TAP TO TEST)",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            skill.triggerKeywords.forEach { trigger ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(PearlWhiteSurface)
                                        .border(1.dp, PinkyBorder, RoundedCornerShape(10.dp))
                                        .clickable { onRunTrigger(trigger) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Test trigger",
                                            tint = RosePink,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "\"$trigger\"",
                                            color = TextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Custom prompt or macro definition if present
                    if (!skill.customPromptOrMacro.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "CUSTOM AUTOMATION INSTRUCTION",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(PinkyWhiteBackground)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = skill.customPromptOrMacro,
                                color = TextPrimary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * AddCustomSkillDialog — Dialog allowing the user to create dynamic automation skills:
 * Name, Description, Category, Voice Triggers, and Action Type (app shortcut, macro, or prompt).
 */
@Composable
private fun AddCustomSkillDialog(
    onDismiss: () -> Unit,
    onSave: (Skill) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(SkillCategory.CUSTOM) }
    var triggersText by remember { mutableStateOf("") }
    var actionType by remember { mutableStateOf("Custom Prompt") } // "Custom Prompt", "App Shortcut", "Macro"
    var actionValue by remember { mutableStateOf("") }
    var iconEmoji by remember { mutableStateOf("✨") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, PinkyBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Create Custom Skill",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp
                        )
                        Text(
                            text = "Add a new automation workflow",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                HorizontalDivider(color = PinkyBorder, thickness = 1.dp)

                // Skill Name & Icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = iconEmoji,
                        onValueChange = { if (it.length <= 4) iconEmoji = it },
                        modifier = Modifier.width(64.dp),
                        label = { Text("Icon", fontSize = 11.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = customFieldColors()
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Skill Name", fontSize = 11.sp) },
                        placeholder = { Text("e.g. Discord Auto-Reply", fontSize = 11.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = customFieldColors()
                    )
                }

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Description", fontSize = 11.sp) },
                    placeholder = { Text("What does this skill do?", fontSize = 11.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = customFieldColors()
                )

                // Category Selection
                Text("CATEGORY", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SkillCategory.values().forEach { category ->
                        val isSelected = selectedCategory == category
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) RosePink else BlushSurfaceElevated)
                                .border(1.dp, if (isSelected) RosePink else PinkyBorder, RoundedCornerShape(10.dp))
                                .clickable { selectedCategory = category }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = category.name,
                                color = if (isSelected) Color.White else TextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Action Type Selector (Tabs)
                Text("ACTION TYPE", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                val actionTypes = listOf("Custom Prompt", "App Shortcut", "Macro")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    actionTypes.forEach { type ->
                        val isSelected = actionType == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) RosePink.copy(alpha = 0.15f) else PearlWhiteSurface)
                                .border(1.dp, if (isSelected) RosePink else PinkyBorder, RoundedCornerShape(10.dp))
                                .clickable { actionType = type }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = type,
                                color = if (isSelected) RosePink else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Action Value Input depending on Action Type
                when (actionType) {
                    "App Shortcut" -> {
                        OutlinedTextField(
                            value = actionValue,
                            onValueChange = { actionValue = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("App Name to Launch", fontSize = 11.sp) },
                            placeholder = { Text("e.g. Spotify, Chrome, Camera", fontSize = 11.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = customFieldColors()
                        )
                    }
                    "Macro" -> {
                        OutlinedTextField(
                            value = actionValue,
                            onValueChange = { actionValue = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Macro Name", fontSize = 11.sp) },
                            placeholder = { Text("e.g. night_routine, work_focus", fontSize = 11.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = customFieldColors()
                        )
                    }
                    else -> {
                        OutlinedTextField(
                            value = actionValue,
                            onValueChange = { actionValue = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("AI Prompt / Instructions", fontSize = 11.sp) },
                            placeholder = { Text("Explain what Soltini should do when this skill triggers...", fontSize = 11.sp) },
                            minLines = 3,
                            maxLines = 5,
                            shape = RoundedCornerShape(12.dp),
                            colors = customFieldColors()
                        )
                    }
                }

                // Voice Triggers
                OutlinedTextField(
                    value = triggersText,
                    onValueChange = { triggersText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Voice Triggers (comma-separated)", fontSize = 11.sp) },
                    placeholder = { Text("e.g. open discord, check chats", fontSize = 11.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = customFieldColors()
                )

                // Error message if validation fails
                if (errorText != null) {
                    Text(
                        text = errorText ?: "",
                        color = RubyShield,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Actions: Cancel & Create
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BlushSurfaceElevated)
                            .border(1.dp, PinkyBorder, RoundedCornerShape(12.dp))
                            .clickable { onDismiss() }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Cancel", color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(RosePink)
                            .clickable {
                                if (name.isBlank()) {
                                    errorText = "Skill name cannot be empty"
                                    return@clickable
                                }
                                val triggers = triggersText
                                    .split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }

                                val tools = when (actionType) {
                                    "App Shortcut" -> listOf("open_app")
                                    "Macro" -> listOf("run_macro", "get_available_macros")
                                    else -> listOf("see_screen")
                                }

                                val newSkill = Skill(
                                    id = "custom_${System.currentTimeMillis()}",
                                    name = name.trim(),
                                    description = description.ifBlank { "Custom user automation" }.trim(),
                                    category = selectedCategory,
                                    icon = iconEmoji.ifBlank { "✨" }.trim(),
                                    isDefault = false,
                                    isEnabled = true,
                                    tools = tools,
                                    triggerKeywords = if (triggers.isNotEmpty()) triggers else listOf(name.trim().lowercase()),
                                    customPromptOrMacro = actionValue.ifBlank { null }?.trim()
                                )

                                onSave(newSkill)
                            }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Create Skill", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun customFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = PearlWhiteSurface,
    unfocusedContainerColor = PearlWhiteSurface,
    focusedBorderColor = PinkyBorderFocused,
    unfocusedBorderColor = PinkyBorder,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = RosePink,
    unfocusedLabelColor = TextSecondary
)
