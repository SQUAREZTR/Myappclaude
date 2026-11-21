package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CommandHistoryEntity
import com.example.data.local.NimSettings
import com.example.data.local.TerminalEntryEntity
import com.example.engine.shell.TerminalMode
import com.example.service.PendingPromptRequest
import com.example.ui.theme.ClaudeAmber
import com.example.ui.theme.ClaudeAmberContainer
import com.example.ui.theme.DiffAddedBg
import com.example.ui.theme.DiffRemovedBg
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
import com.example.ui.theme.TerminalError
import com.example.ui.theme.TerminalErrorContainer
import com.example.ui.theme.TerminalSuccess
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary

private data class SlashCmdSuggestion(
    val command: String,
    val description: String,
    val autoExecute: Boolean = true
)

@Composable
fun TerminalScreen(
    entries: List<TerminalEntryEntity>,
    history: List<CommandHistoryEntity>,
    settings: NimSettings,
    mode: TerminalMode,
    cwdDisplay: String,
    availableSkills: List<String>,
    isBusy: Boolean,
    isServiceActive: Boolean,
    pendingPrompt: PendingPromptRequest?,
    onSubmitInput: (String) -> Unit,
    onInterrupt: () -> Unit,
    onRespondApproval: (Boolean) -> Unit,
    onSwitchMode: (TerminalMode) -> Unit,
    onToggleService: (Boolean) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    var historyCursor by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()

    val slashSuggestions = remember(inputText, availableSkills) {
        if (!inputText.startsWith("/")) emptyList()
        else {
            val baseCmds = listOf(
                SlashCmdSuggestion("/skills", "List available .claude/skills/*.md skills", true),
                SlashCmdSuggestion("/skill ", "Activate a skill (/skill <name>)", false),
                SlashCmdSuggestion("/init", "Generate CLAUDE.md project memory", true),
                SlashCmdSuggestion("/memory", "View CLAUDE.md project memory", true),
                SlashCmdSuggestion("/status", "Show model, API key, & session stats", true),
                SlashCmdSuggestion("/doctor", "Diagnose API, Python 3, & workspace", true),
                SlashCmdSuggestion("/model ", "Switch NVIDIA NIM / LiteLLM model", false),
                SlashCmdSuggestion("/modelno ", "Select model by number (/modelno 8)", false),
                SlashCmdSuggestion("/models", "Fetch /v1/models & list in pages", true),
                SlashCmdSuggestion("/workspace ", "List, create, or switch workspace", false),
                SlashCmdSuggestion("/permissions", "Toggle auto-approve vs Allow/Deny", true),
                SlashCmdSuggestion("/compact", "Compact conversation history", true),
                SlashCmdSuggestion("/files", "List files in ~/workspace", true),
                SlashCmdSuggestion("/python", "Open Python 3.12 REPL (>>>)", true),
                SlashCmdSuggestion("/key ", "Save NVIDIA / LiteLLM API key", false),
                SlashCmdSuggestion("/base ", "Switch NVIDIA NIM / LiteLLM URL", false),
                SlashCmdSuggestion("/litellm ", "Connect LiteLLM proxy (/litellm <url> [key])", false),
                SlashCmdSuggestion("/clear", "Clear terminal & reset context", true),
                SlashCmdSuggestion("/help", "Show all CLI & slash commands", true),
                SlashCmdSuggestion("/exit", "Drop to $ Workspace Shell", true)
            )
            val skillCmds = availableSkills.map { s ->
                SlashCmdSuggestion("/skill $s", "Load skill '$s' into Claude Code", true)
            }
            val q = inputText.lowercase()
            val pool = if (q.startsWith("/skill")) skillCmds + baseCmds else baseCmds + skillCmds
            pool.filter { it.command.lowercase().startsWith(q) || it.command.lowercase().contains(q.removePrefix("/")) }
                .take(7)
        }
    }

    LaunchedEffect(entries.size, entries.lastOrNull()?.text?.length) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    val fontSize = settings.terminalFontSizeSp.sp
    val lineHeight = (settings.terminalFontSizeSp + 6).sp

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        // Top HUD Status Strip
        TerminalStatusHeader(
            mode = mode,
            cwdDisplay = cwdDisplay,
            modelId = settings.selectedModelId,
            hasApiKey = settings.apiKey.isNotBlank(),
            isBusy = isBusy,
            isServiceActive = isServiceActive,
            onSwitchMode = onSwitchMode,
            onToggleService = onToggleService,
            onOpenModels = onOpenModels,
            onOpenSettings = onOpenSettings
        )

        // Terminal Scrollback Viewport
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("terminal_output_list")
        ) {
            items(entries, key = { it.id }) { entry ->
                TerminalEntryView(
                    entry = entry,
                    fontSize = fontSize,
                    lineHeight = lineHeight
                )
            }
        }

        // Interactive Approval / Stdin Prompt Banner
        AnimatedVisibility(visible = pendingPrompt != null) {
            pendingPrompt?.let { req ->
                Surface(
                    color = ClaudeAmberContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .border(1.dp, ClaudeAmber.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = req.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = ClaudeAmber
                            )
                            Text(
                                text = req.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = TerminalTextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (req.isApproval) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { onRespondApproval(false) },
                                modifier = Modifier.testTag("approve_no_button")
                            ) {
                                Text("Deny")
                            }
                            Spacer(Modifier.width(6.dp))
                            Button(
                                onClick = { onRespondApproval(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = PhosphorGreen, contentColor = ObsidianVoid),
                                modifier = Modifier.testTag("approve_yes_button")
                            ) {
                                Text("Allow")
                            }
                        }
                    }
                }
            }
        }

        // Slash-Command & Skill Autocomplete Palette (Claude Code style)
        AnimatedVisibility(visible = slashSuggestions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianElevated)
                    .border(1.dp, ClaudeAmber.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .padding(vertical = 4.dp)
                    .testTag("slash_command_menu")
            ) {
                slashSuggestions.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (item.autoExecute) {
                                    inputText = ""
                                    onSubmitInput(item.command)
                                } else {
                                    inputText = item.command
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = item.command.trim(),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = ClaudeAmber
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = TerminalTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Accessory Shortcut Strip above input
        TerminalAccessoryBar(
            isBusy = isBusy,
            onInterrupt = onInterrupt,
            onHistoryUp = {
                if (history.isNotEmpty()) {
                    val next = (historyCursor + 1).coerceAtMost(history.lastIndex)
                    historyCursor = next
                    inputText = history[next].command
                }
            },
            onHistoryDown = {
                if (historyCursor > 0) {
                    historyCursor--
                    inputText = history[historyCursor].command
                } else {
                    historyCursor = -1
                    inputText = ""
                }
            },
            onInsertSnippet = { snippet, autoSend ->
                if (autoSend) {
                    inputText = ""
                    onSubmitInput(snippet)
                } else {
                    inputText = if (inputText.isBlank()) snippet else "$inputText$snippet"
                }
            }
        )

        // Command Input Bar
        TerminalInputBar(
            mode = mode,
            inputText = inputText,
            isBusy = isBusy,
            waitingStdin = pendingPrompt?.isApproval == false,
            onValueChange = { inputText = it },
            onSubmit = {
                val cmd = inputText
                inputText = ""
                historyCursor = -1
                onSubmitInput(cmd)
            },
            onInterrupt = onInterrupt
        )

        // Authentic Claude Code CLI Footer Strip
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (settings.autoApproveTools) "▶▶ bypass permissions on" else "⏸ ask before edits",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                color = if (settings.autoApproveTools) ClaudeAmber else TerminalCyan,
                modifier = Modifier.clickable { onSubmitInput("/permissions") }
            )
            Text(
                text = "! bash • / skills & cmds",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                color = TerminalTextMuted
            )
        }
    }
}

@Composable
private fun TerminalStatusHeader(
    mode: TerminalMode,
    cwdDisplay: String,
    modelId: String,
    hasApiKey: Boolean,
    isBusy: Boolean,
    isServiceActive: Boolean,
    onSwitchMode: (TerminalMode) -> Unit,
    onToggleService: (Boolean) -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "bg_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    Surface(
        color = ObsidianSurface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Background Foreground Service Pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isServiceActive) NvidiaLimeContainer else ObsidianCard)
                            .border(
                                1.dp,
                                if (isServiceActive) NvidiaLime.copy(alpha = 0.6f) else ObsidianOutline,
                                RoundedCornerShape(50)
                            )
                            .clickable { onToggleService(!isServiceActive) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("background_service_pill")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .alpha(if (isServiceActive) pulseAlpha else 1f)
                                .clip(CircleShape)
                                .background(if (isServiceActive) PhosphorGreen else TerminalTextMuted)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isBusy) "RUNNING" else if (isServiceActive) "BG LIVE" else "BG OFF",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isServiceActive) PhosphorGreen else TerminalTextMuted,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = cwdDisplay,
                        style = MaterialTheme.typography.labelMedium,
                        color = TerminalCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Active Model Quick Chip
                Surface(
                    color = ObsidianCard,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { if (hasApiKey) onOpenModels() else onOpenSettings() }
                        .border(
                            1.dp,
                            if (hasApiKey) ClaudeAmber.copy(alpha = 0.5f) else TerminalError.copy(alpha = 0.6f),
                            RoundedCornerShape(50)
                        )
                        .testTag("active_model_chip")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Active model",
                            tint = if (hasApiKey) ClaudeAmber else TerminalError,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (!hasApiKey) "Set API Key" else modelId.substringAfterLast("/"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasApiKey) TerminalTextPrimary else TerminalError,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Mode Switcher Pills ($ sh | >>> py | >_ claude)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ModePill(
                    label = "$ Shell",
                    selected = mode == TerminalMode.SHELL,
                    accentColor = TerminalCyan,
                    onClick = { onSwitchMode(TerminalMode.SHELL) },
                    modifier = Modifier.weight(1f).testTag("mode_shell_pill")
                )
                ModePill(
                    label = ">>> Python",
                    selected = mode == TerminalMode.PYTHON_REPL,
                    accentColor = PhosphorGreen,
                    onClick = { onSwitchMode(TerminalMode.PYTHON_REPL) },
                    modifier = Modifier.weight(1f).testTag("mode_python_pill")
                )
                ModePill(
                    label = ">_ Claude Code",
                    selected = mode == TerminalMode.CLAUDE_CODE,
                    accentColor = ClaudeAmber,
                    onClick = { onSwitchMode(TerminalMode.CLAUDE_CODE) },
                    modifier = Modifier.weight(1f).testTag("mode_claude_pill")
                )
            }
        }
    }
}

@Composable
private fun ModePill(
    label: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) accentColor.copy(alpha = 0.16f) else ObsidianElevated)
            .border(
                1.dp,
                if (selected) accentColor else ObsidianOutline,
                RoundedCornerShape(9.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accentColor else TerminalTextMuted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun TerminalEntryView(
    entry: TerminalEntryEntity,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit
) {
    val monoStyle = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = fontSize,
        lineHeight = lineHeight
    )

    when (entry.kind) {
        "BANNER" -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, ClaudeAmber.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Iconic Claude Code pixel mascot
                    Text(
                        text = " ▐▛███▜▌\n▝▜█████▛▘\n  ▘▘ ▝▝",
                        style = TextStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = ClaudeAmber
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = entry.title.ifBlank { "Claude Code CLI" },
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = TerminalTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = entry.text,
                            style = monoStyle.copy(fontSize = (fontSize.value - 1f).sp),
                            color = TerminalTextMuted
                        )
                    }
                }
            }
        }
        "COMMAND" -> {
            Text(
                text = entry.text,
                style = monoStyle.copy(fontWeight = FontWeight.Bold),
                color = PhosphorGreen,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        "STDERR" -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TerminalErrorContainer.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = entry.text.trimEnd('\n'),
                    style = monoStyle,
                    color = TerminalError
                )
            }
        }
        "SYSTEM" -> {
            Text(
                text = entry.text.trimEnd('\n'),
                style = monoStyle,
                color = TerminalCyan
            )
        }
        "AGENT_THINKING" -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, ObsidianOutline, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "Thinking...",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextMuted
                    )
                    Text(
                        text = entry.text.ifBlank { "..." },
                        style = monoStyle.copy(fontSize = (fontSize.value - 1f).sp),
                        color = TerminalTextMuted
                    )
                }
            }
        }
        "AGENT_TEXT" -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianElevated)
                    .border(1.dp, ClaudeAmber.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = ClaudeAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = entry.title.substringAfterLast("/").ifBlank { "Claude Code" },
                        style = MaterialTheme.typography.labelSmall,
                        color = ClaudeAmber
                    )
                }
                Text(
                    text = entry.text.ifBlank { "▍" },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = fontSize,
                        lineHeight = lineHeight
                    ),
                    color = TerminalTextPrimary
                )
            }
        }
        "TOOL_CALL" -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianCard)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = ClaudeAmber,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${entry.title}: ${entry.text}",
                    style = monoStyle.copy(fontWeight = FontWeight.Medium),
                    color = ClaudeAmber
                )
            }
        }
        "TOOL_RESULT" -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianSurface)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = entry.text.trimEnd('\n'),
                    style = monoStyle.copy(fontSize = (fontSize.value - 1f).sp),
                    color = TerminalTextMuted
                )
            }
        }
        "DIFF" -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianSurface)
                    .border(1.dp, ObsidianOutline, RoundedCornerShape(10.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = TerminalCyan,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                entry.text.lines().forEach { line ->
                    val bg = when {
                        line.startsWith("+") && !line.startsWith("+++") -> DiffAddedBg
                        line.startsWith("-") && !line.startsWith("---") -> DiffRemovedBg
                        else -> Color.Transparent
                    }
                    val fg = when {
                        line.startsWith("+") && !line.startsWith("+++") -> TerminalSuccess
                        line.startsWith("-") && !line.startsWith("---") -> TerminalError
                        else -> TerminalTextMuted
                    }
                    Text(
                        text = line,
                        style = monoStyle.copy(fontSize = (fontSize.value - 1f).sp),
                        color = fg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .padding(horizontal = 4.dp)
                    )
                }
            }
        }
        else -> {
            Text(
                text = entry.text.trimEnd('\n'),
                style = monoStyle,
                color = TerminalTextPrimary
            )
        }
    }
}

@Composable
private fun TerminalAccessoryBar(
    isBusy: Boolean,
    onInterrupt: () -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    onInsertSnippet: (String, Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        AccessoryKey(
            label = "Ctrl+C",
            highlight = isBusy,
            highlightColor = TerminalError,
            onClick = onInterrupt,
            modifier = Modifier.testTag("key_ctrl_c")
        )
        AccessoryKey("/", highlight = false, highlightColor = ClaudeAmber, onClick = { onInsertSnippet("/", false) })
        AccessoryKey("!", highlight = false, highlightColor = TerminalCyan, onClick = { onInsertSnippet("!", false) })
        IconButton(
            onClick = onHistoryUp,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(ObsidianCard)
                .testTag("key_history_up")
        ) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous command", tint = TerminalTextPrimary, modifier = Modifier.size(18.dp))
        }
        IconButton(
            onClick = onHistoryDown,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(ObsidianCard)
                .testTag("key_history_down")
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next command", tint = TerminalTextPrimary, modifier = Modifier.size(18.dp))
        }
        AccessoryKey("/skills", highlight = false, highlightColor = ClaudeAmber, onClick = { onInsertSnippet("/skills", true) })
        AccessoryKey("/init", highlight = false, highlightColor = PhosphorGreen, onClick = { onInsertSnippet("/init", true) })
        AccessoryKey("/doctor", highlight = false, highlightColor = TerminalCyan, onClick = { onInsertSnippet("/doctor", true) })
        AccessoryKey("/model", highlight = false, highlightColor = ClaudeAmber, onClick = { onInsertSnippet("/model", true) })
        AccessoryKey("!python3 ", highlight = false, highlightColor = PhosphorGreen, onClick = { onInsertSnippet("!python3 ", false) })
        AccessoryKey("/clear", highlight = false, highlightColor = TerminalTextMuted, onClick = { onInsertSnippet("/clear", true) })
        AccessoryKey("Tab", highlight = false, highlightColor = TerminalTextMuted, onClick = { onInsertSnippet("    ", false) })
    }
}

@Composable
private fun AccessoryKey(
    label: String,
    highlight: Boolean,
    highlightColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (highlight) highlightColor.copy(alpha = 0.22f) else ObsidianCard)
            .border(
                1.dp,
                if (highlight) highlightColor else ObsidianOutline,
                RoundedCornerShape(7.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlight) highlightColor else TerminalTextPrimary
        )
    }
}

@Composable
private fun TerminalInputBar(
    mode: TerminalMode,
    inputText: String,
    isBusy: Boolean,
    waitingStdin: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onInterrupt: () -> Unit
) {
    val promptSymbol = when {
        waitingStdin -> "stdin>"
        mode == TerminalMode.PYTHON_REPL -> ">>>"
        mode == TerminalMode.CLAUDE_CODE -> "claude>"
        else -> "$"
    }
    val promptColor = when {
        waitingStdin -> TerminalCyan
        mode == TerminalMode.PYTHON_REPL -> PhosphorGreen
        mode == TerminalMode.CLAUDE_CODE -> ClaudeAmber
        else -> TerminalCyan
    }

    val focusRequester = remember { FocusRequester() }

    Surface(
        color = ObsidianElevated,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = promptSymbol,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                color = promptColor,
                modifier = Modifier.padding(end = 8.dp)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianVoid)
                    .border(1.dp, ObsidianOutline, RoundedCornerShape(10.dp))
                    .clickable { focusRequester.requestFocus() }
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = when {
                            waitingStdin -> "Enter Python input()..."
                            mode == TerminalMode.PYTHON_REPL -> "Enter Python expression or code..."
                            mode == TerminalMode.CLAUDE_CODE -> "Ask Claude Code to write/edit/run code..."
                            else -> "Type command (python3 hello.py, claude, ls)..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = { updated ->
                        if (updated.contains('\n')) {
                            val clean = updated.replace("\n", "")
                            onValueChange(clean)
                            if (clean.isNotBlank()) {
                                onSubmit()
                            }
                        } else {
                            onValueChange(updated)
                        }
                    },
                    textStyle = TextStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 14.sp,
                        color = TerminalTextPrimary
                    ),
                    cursorBrush = SolidColor(promptColor),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                                !event.isShiftPressed
                            ) {
                                onSubmit()
                                true
                            } else {
                                false
                            }
                        }
                        .testTag("terminal_command_input")
                )
            }

            Spacer(Modifier.width(8.dp))

            if (isBusy && !waitingStdin) {
                IconButton(
                    onClick = onInterrupt,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TerminalErrorContainer)
                        .testTag("terminal_stop_button")
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop execution", tint = TerminalError)
                }
            } else {
                IconButton(
                    onClick = onSubmit,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(promptColor)
                        .testTag("terminal_send_button")
                ) {
                    Icon(
                        imageVector = if (mode == TerminalMode.SHELL) Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Execute command",
                        tint = ObsidianVoid
                    )
                }
            }
        }
    }
}
