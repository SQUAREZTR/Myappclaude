package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.NimModelEntity
import com.example.ui.theme.ClaudeAmber
import com.example.ui.theme.ClaudeAmberContainer
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NvidiaLime
import com.example.ui.theme.NvidiaLimeContainer
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalCyanContainer
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary

@Composable
fun ModelsScreen(
    models: List<NimModelEntity>,
    selectedModelId: String,
    isSyncing: Boolean,
    syncMessage: String?,
    onSelectModel: (String) -> Unit,
    onToggleFavorite: (NimModelEntity) -> Unit,
    onSyncLiveModels: () -> Unit,
    onAddCustomModel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    var showAddDialog by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(1) }
    var pageSize by remember { mutableIntStateOf(10) }
    val listState = rememberLazyListState()

    val filters = listOf("All", "Live V1", "Favorites", "Coding", "Reasoning", "Flagship", "Fast", "Vision", "New")

    val filteredModels = remember(models, searchQuery, selectedFilter) {
        val q = searchQuery.trim().lowercase()
        models.filter { m ->
            val matchesFilter = when (selectedFilter) {
                "All" -> true
                "Favorites" -> m.isFavorite
                "New" -> m.isNew
                "Live V1" -> m.isLiveApi
                else -> m.category.equals(selectedFilter, ignoreCase = true)
            }
            val matchesQuery = q.isEmpty() ||
                m.id.lowercase().contains(q) ||
                m.displayName.lowercase().contains(q) ||
                m.provider.lowercase().contains(q) ||
                m.description.lowercase().contains(q)
            matchesFilter && matchesQuery
        }
    }

    LaunchedEffect(searchQuery, selectedFilter, pageSize) {
        currentPage = 1
    }

    val totalPages = remember(filteredModels.size, pageSize) {
        if (filteredModels.isEmpty()) 1 else (filteredModels.size + pageSize - 1) / pageSize
    }
    val safePage = currentPage.coerceIn(1, totalPages)
    val pagedModels = remember(filteredModels, safePage, pageSize) {
        filteredModels.drop((safePage - 1) * pageSize).take(pageSize)
    }

    LaunchedEffect(safePage) {
        if (pagedModels.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    val startIdx = if (filteredModels.isEmpty()) 0 else (safePage - 1) * pageSize + 1
    val endIdx = if (filteredModels.isEmpty()) 0 else startIdx + pagedModels.size - 1
    val liveCount = remember(models) { models.count { it.isLiveApi } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        Surface(color = ObsidianSurface, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "NVIDIA NIM /v1 Models",
                            style = MaterialTheme.typography.titleLarge,
                            color = TerminalTextPrimary
                        )
                        Text(
                            text = "${models.size} total ($liveCount live V1) • ${selectedModelId.substringAfterLast("/")}",
                            style = MaterialTheme.typography.labelMedium,
                            color = PhosphorGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(ObsidianCard)
                                .testTag("add_custom_model_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Custom Model", tint = ClaudeAmber)
                        }
                        Button(
                            onClick = onSyncLiveModels,
                            enabled = !isSyncing,
                            colors = ButtonDefaults.buttonColors(containerColor = NvidiaLime, contentColor = ObsidianVoid),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("sync_models_button")
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = ObsidianVoid
                                )
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("Fetch /v1", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (!syncMessage.isNullOrBlank()) {
                    Text(
                        text = syncMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = TerminalCyan
                    )
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search Llama 4, DeepSeek V4, Qwen3 Coder, Nemotron, Kimi...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TerminalTextMuted) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", tint = TerminalTextMuted)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PhosphorGreen,
                        unfocusedBorderColor = ObsidianOutline,
                        focusedContainerColor = ObsidianVoid,
                        unfocusedContainerColor = ObsidianVoid
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model_search_input")
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    filters.forEach { f ->
                        val isSelected = selectedFilter == f
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedFilter = f },
                            label = { Text(f) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NvidiaLimeContainer,
                                selectedLabelColor = PhosphorGreen,
                                containerColor = ObsidianElevated,
                                labelColor = TerminalTextMuted
                            )
                        )
                    }
                }

                // Page summary & per-page selector
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (filteredModels.isEmpty()) "0 models" else "Showing $startIdx–$endIdx of ${filteredModels.size} • Page $safePage/$totalPages",
                        style = MaterialTheme.typography.labelMedium,
                        color = TerminalTextMuted,
                        modifier = Modifier.testTag("models_page_summary")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(10, 20, 50).forEach { size ->
                            val active = pageSize == size
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) NvidiaLimeContainer else ObsidianElevated)
                                    .border(
                                        1.dp,
                                        if (active) PhosphorGreen else ObsidianOutline,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { pageSize = size }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .testTag("page_size_$size")
                            ) {
                                Text(
                                    text = "$size/pg",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) PhosphorGreen else TerminalTextMuted,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        if (pagedModels.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp)
            ) {
                Text(
                    text = if (selectedFilter == "Live V1") {
                        "No live /v1/models cached yet. Tap 'Fetch /v1' above to load models from your API endpoint."
                    } else {
                        "No models match your search or filter."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TerminalTextMuted
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("models_list")
            ) {
                items(pagedModels, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        isSelected = model.id == selectedModelId,
                        onSelect = { onSelectModel(model.id) },
                        onToggleFavorite = { onToggleFavorite(model) }
                    )
                }
            }
        }

        // Paginated Page Controls Bar
        if (filteredModels.isNotEmpty()) {
            ModelsPaginationBar(
                currentPage = safePage,
                totalPages = totalPages,
                onPageSelected = { currentPage = it }
            )
        }
    }

    if (showAddDialog) {
        var customId by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = ObsidianCard,
            title = { Text("Add Custom Model ID", color = TerminalTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter any NVIDIA NIM or LiteLLM model identifier:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTextMuted
                    )
                    OutlinedTextField(
                        value = customId,
                        onValueChange = { customId = it },
                        placeholder = { Text("e.g. qwen/qwen3-coder-480b-a35b-instruct") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddCustomModel(customId)
                        showAddDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PhosphorGreen, contentColor = ObsidianVoid)
                ) {
                    Text("Add & Use")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ModelCard(
    model: NimModelEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) NvidiaLimeContainer.copy(alpha = 0.35f) else ObsidianElevated)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) PhosphorGreen else ObsidianOutline,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onSelect)
            .padding(14.dp)
            .testTag("model_card_${model.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = model.provider,
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalCyan,
                        fontWeight = FontWeight.Bold
                    )
                    if (model.isNew) {
                        BadgePill("NEW", ClaudeAmber, ClaudeAmberContainer)
                    }
                    if (model.isLiveApi) {
                        BadgePill("LIVE", PhosphorGreen, NvidiaLimeContainer)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TerminalTextPrimary
                )
                Text(
                    text = model.id,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = TerminalTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Active model",
                        tint = PhosphorGreen,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (model.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (model.isFavorite) ClaudeAmber else TerminalTextMuted
                    )
                }
            }
        }

        Text(
            text = model.description,
            style = MaterialTheme.typography.bodyMedium,
            color = TerminalTextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            BadgePill(model.category, TerminalTextPrimary, ObsidianCard)
            BadgePill("${model.contextLength} ctx", TerminalCyan, TerminalCyanContainer)
            if (model.supportsTools) {
                BadgePill("Tools", ClaudeAmber, ClaudeAmberContainer)
            }
            if (model.supportsVision) {
                BadgePill("Vision", PhosphorGreen, NvidiaLimeContainer)
            }
        }
    }
}

@Composable
private fun ModelsPaginationBar(
    currentPage: Int,
    totalPages: Int,
    onPageSelected: (Int) -> Unit
) {
    val pageNumbers = remember(currentPage, totalPages) {
        if (totalPages <= 5) {
            (1..totalPages).toList()
        } else {
            val set = linkedSetOf(1)
            for (p in (currentPage - 1)..(currentPage + 1)) {
                if (p in 1..totalPages) set.add(p)
            }
            set.add(totalPages)
            set.toList()
        }
    }

    Surface(
        color = ObsidianSurface,
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("models_pagination_bar")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            IconButton(
                onClick = { if (currentPage > 1) onPageSelected(currentPage - 1) },
                enabled = currentPage > 1,
                modifier = Modifier.testTag("models_prev_page")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Previous page",
                    tint = if (currentPage > 1) PhosphorGreen else TerminalTextMuted.copy(alpha = 0.4f)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pageNumbers.forEachIndexed { idx, p ->
                    if (idx > 0 && p - pageNumbers[idx - 1] > 1) {
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.labelMedium,
                            color = TerminalTextMuted
                        )
                    }
                    val active = p == currentPage
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(width = 34.dp, height = 34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) PhosphorGreen else ObsidianElevated)
                            .border(
                                1.dp,
                                if (active) PhosphorGreen else ObsidianOutline,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onPageSelected(p) }
                            .testTag("models_page_$p")
                    ) {
                        Text(
                            text = "$p",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (active) ObsidianVoid else TerminalTextPrimary
                        )
                    }
                }
            }

            IconButton(
                onClick = { if (currentPage < totalPages) onPageSelected(currentPage + 1) },
                enabled = currentPage < totalPages,
                modifier = Modifier.testTag("models_next_page")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Next page",
                    tint = if (currentPage < totalPages) PhosphorGreen else TerminalTextMuted.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
private fun BadgePill(text: String, fg: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.SemiBold
        )
    }
}
