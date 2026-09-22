package com.lucy.mate.ui.memory

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucy.mate.memory.MemoryDatabase
import com.lucy.mate.ui.MainViewModel
import com.lucy.mate.ui.theme.EmeraldLive
import com.lucy.mate.ui.theme.PearlWhiteSurface
import com.lucy.mate.ui.theme.PinkyBorder
import com.lucy.mate.ui.theme.PinkyWhiteBackground
import com.lucy.mate.ui.theme.RosePink
import com.lucy.mate.ui.theme.RubyShield
import com.lucy.mate.ui.theme.SoftViolet
import com.lucy.mate.ui.theme.TextPrimary
import com.lucy.mate.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val REGEX_IDENTITY = Regex("name|called|born|age|birthday|i am|myself", RegexOption.IGNORE_CASE)
private val REGEX_PREFERENCES = Regex("like|love|prefer|favorite|hobby|enjoy|fan", RegexOption.IGNORE_CASE)
private val REGEX_PLACES = Regex("live|from|city|work|home|office|country|location", RegexOption.IGNORE_CASE)

@Composable
fun MemoryVaultScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val memoryManager = remember { viewModel.getMemoryManager() }

    val liveMemories: List<MemoryDatabase.Memory> by (
        memoryManager?.memoriesFlow?.collectAsState(initial = emptyList())
            ?: remember { mutableStateOf(emptyList<MemoryDatabase.Memory>()) }
    )
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var showAddDialog by remember { mutableStateOf(false) }
    var newFactText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        memoryManager?.refreshMemories()
    }

    val filteredMemories = remember(liveMemories, searchQuery, selectedCategory) {
        liveMemories.filter { mem ->
            val matchesQuery = if (searchQuery.isBlank()) true else mem.content.contains(searchQuery, ignoreCase = true)
            val matchesCategory = when (selectedCategory) {
                "AI_AUTO" -> mem.importance >= 0.72f
                "IDENTITY" -> mem.content.contains(REGEX_IDENTITY)
                "PREFERENCES" -> mem.content.contains(REGEX_PREFERENCES)
                "PLACES" -> mem.content.contains(REGEX_PLACES)
                else -> true
            }
            matchesQuery && matchesCategory
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
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Memory Vault",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "${liveMemories.size} facts remembered & auto-injected into Gemini",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.width(10.dp))

                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = RosePink),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Fact", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }

            Spacer(Modifier.height(14.dp))

            // Search Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search remembered facts, likes, names...", color = TextSecondary, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = RosePink) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = PearlWhiteSurface,
                    unfocusedContainerColor = PearlWhiteSurface,
                    focusedBorderColor = RosePink,
                    unfocusedBorderColor = PinkyBorder,
                    cursorColor = RosePink
                )
            )

            Spacer(Modifier.height(10.dp))

            // Category Filter Chips
            val categories = listOf(
                "ALL" to "All (${liveMemories.size})",
                "AI_AUTO" to "🤖 AI Auto-Saved",
                "IDENTITY" to "👤 Identity",
                "PREFERENCES" to "❤️ Likes & Favs",
                "PLACES" to "📍 Places"
            )
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { (key, label) ->
                    val isSelected = selectedCategory == key
                    val bg = if (isSelected) RosePink else PearlWhiteSurface
                    val textCol = if (isSelected) Color.White else TextPrimary
                    val borderCol = if (isSelected) RosePink else PinkyBorder

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(bg)
                            .border(1.dp, borderCol, RoundedCornerShape(14.dp))
                            .clickable { selectedCategory = key }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = label,
                            color = textCol,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Memory List
            if (filteredMemories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(RosePink.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Psychology, null, tint = RosePink, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (searchQuery.isBlank() && selectedCategory == "ALL") "No memories stored yet.\nTalk to Soltini (say your name, likes, habits) or tap '+ Add Fact' above!"
                            else "No memories match current filter",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredMemories, key = { it.id }) { memory ->
                        MemoryItemCard(
                            memory = memory,
                            onDelete = {
                                scope.launch(Dispatchers.IO) {
                                    memoryManager?.deleteMemory(memory.id)
                                }
                                Toast.makeText(context, "Memory deleted", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }

        // Add Memory Modal Dialog
        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                containerColor = PearlWhiteSurface,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text("Add Permanent Fact", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "This fact will be stored in SQLite and automatically injected into Soltini's system prompt during conversations.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        OutlinedTextField(
                            value = newFactText,
                            onValueChange = { newFactText = it },
                            placeholder = { Text("e.g. My favorite football club is Chelsea.", color = TextSecondary, fontSize = 13.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = PinkyWhiteBackground,
                                unfocusedContainerColor = PinkyWhiteBackground,
                                focusedBorderColor = RosePink,
                                unfocusedBorderColor = PinkyBorder
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val text = newFactText.trim()
                            if (text.isNotBlank()) {
                                isLoading = true
                                scope.launch {
                                    memoryManager?.saveMemory(text, 0.8f)
                                    newFactText = ""
                                    showAddDialog = false
                                    isLoading = false
                                    Toast.makeText(context, "Memory saved!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RosePink),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isLoading && newFactText.isNotBlank()
                    ) {
                        Text("Save Fact", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            )
        }
    }
}

@Composable
private fun MemoryItemCard(
    memory: MemoryDatabase.Memory,
    onDelete: () -> Unit
) {
    val dateStr = remember(memory.createdAt) {
        val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        sdf.format(Date(memory.createdAt))
    }

    val isAutoSaved = memory.importance >= 0.72f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PearlWhiteSurface),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PinkyBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isAutoSaved) SoftViolet.copy(alpha = 0.15f) else RosePink.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isAutoSaved) "🤖 AUTO-SAVED" else "SAVED NOTE",
                            color = if (isAutoSaved) SoftViolet else RosePink,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(text = dateStr, color = TextSecondary, fontSize = 10.sp)
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = memory.content,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
