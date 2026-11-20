package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.shell.WorkspaceFileItem
import com.example.engine.shell.WorkspaceInfo
import com.example.ui.EditorState
import com.example.ui.theme.ClaudeAmber
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NvidiaLimeContainer
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalError
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary

@Composable
fun WorkspaceScreen(
    files: List<WorkspaceFileItem>,
    workspaces: List<WorkspaceInfo>,
    activeWorkspace: String,
    editorState: EditorState?,
    onRefresh: () -> Unit,
    onCreateWorkspace: (String) -> Unit,
    onSwitchWorkspace: (String) -> Unit,
    onDeleteWorkspace: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onUpdateEditor: (String) -> Unit,
    onSaveEditor: () -> Unit,
    onCloseEditor: () -> Unit,
    onCreateFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onRunPython: (String) -> Unit,
    onAskClaude: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (editorState != null) {
        BackHandler { onCloseEditor() }
        CodeEditorView(
            editorState = editorState,
            onContentChange = onUpdateEditor,
            onSave = onSaveEditor,
            onRun = { onRunPython(editorState.relativePath) },
            onAskClaude = { onAskClaude(editorState.relativePath) },
            onBack = onCloseEditor,
            modifier = modifier
        )
        return
    }

    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewWorkspaceDialog by remember { mutableStateOf(false) }

    val wsDisplayPath = if (activeWorkspace == "default") "~/workspace" else "~/ws/$activeWorkspace"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        Surface(color = ObsidianSurface, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Workspaces",
                            style = MaterialTheme.typography.titleLarge,
                            color = TerminalTextPrimary
                        )
                        Text(
                            text = "$wsDisplayPath • ${files.size} items",
                            style = MaterialTheme.typography.labelMedium,
                            color = TerminalCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { showNewFolderDialog = true }, modifier = Modifier.testTag("new_folder_button")) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder", tint = TerminalCyan)
                        }
                        IconButton(onClick = { showNewFileDialog = true }, modifier = Modifier.testTag("new_file_button")) {
                            Icon(Icons.Default.Add, contentDescription = "New File", tint = PhosphorGreen)
                        }
                        IconButton(onClick = onRefresh, modifier = Modifier.testTag("refresh_files_button")) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TerminalTextPrimary)
                        }
                    }
                }

                // Isolated Workspace Switcher Bar
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                ) {
                    workspaces.forEach { ws ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (ws.isActive) NvidiaLimeContainer else ObsidianElevated)
                                .border(
                                    1.dp,
                                    if (ws.isActive) PhosphorGreen else ObsidianOutline,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onSwitchWorkspace(ws.name) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("workspace_chip_${ws.name}")
                        ) {
                            Text(
                                text = (if (ws.isActive) "● " else "") + "${ws.name} (${ws.fileCount})",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (ws.isActive) PhosphorGreen else TerminalTextPrimary,
                                fontWeight = if (ws.isActive) FontWeight.Bold else FontWeight.Normal
                            )
                            if (ws.name != "default" && !ws.isActive) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete workspace ${ws.name}",
                                    tint = TerminalError,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onDeleteWorkspace(ws.name) }
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(ObsidianCard)
                            .border(1.dp, ClaudeAmber.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .clickable { showNewWorkspaceDialog = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("new_workspace_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = ClaudeAmber, modifier = Modifier.size(15.dp))
                        Text(
                            text = "New Workspace",
                            style = MaterialTheme.typography.labelMedium,
                            color = ClaudeAmber,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        if (files.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = TerminalTextMuted, modifier = Modifier.size(48.dp))
                    Text("Workspace is empty", style = MaterialTheme.typography.titleMedium, color = TerminalTextPrimary)
                    Text("Create a Python script or ask Claude Code to generate one.", style = MaterialTheme.typography.bodyMedium, color = TerminalTextMuted)
                    Button(
                        onClick = { showNewFileDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = PhosphorGreen, contentColor = ObsidianVoid)
                    ) {
                        Text("Create script.py")
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(files, key = { it.relativePath }) { item ->
                    WorkspaceItemCard(
                        item = item,
                        onOpen = { if (!item.isDirectory) onOpenFile(item.relativePath) },
                        onRun = { onRunPython(item.relativePath) },
                        onAskClaude = { onAskClaude(item.relativePath) },
                        onDelete = { onDeleteItem(item.relativePath) }
                    )
                }
            }
        }
    }

    if (showNewFileDialog) {
        CreatePathDialog(
            title = "New Workspace File",
            placeholder = "e.g. scraper.py or utils/helper.py",
            onDismiss = { showNewFileDialog = false },
            onConfirm = {
                showNewFileDialog = false
                onCreateFile(it)
            }
        )
    }

    if (showNewFolderDialog) {
        CreatePathDialog(
            title = "New Folder",
            placeholder = "e.g. scripts",
            onDismiss = { showNewFolderDialog = false },
            onConfirm = {
                showNewFolderDialog = false
                onCreateFolder(it)
            }
        )
    }

    if (showNewWorkspaceDialog) {
        CreatePathDialog(
            title = "Create Isolated Workspace",
            placeholder = "e.g. scraper-bot or ml-lab",
            onDismiss = { showNewWorkspaceDialog = false },
            onConfirm = {
                showNewWorkspaceDialog = false
                if (it.isNotBlank()) onCreateWorkspace(it)
            }
        )
    }
}

@Composable
private fun WorkspaceItemCard(
    item: WorkspaceFileItem,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onAskClaude: () -> Unit,
    onDelete: () -> Unit
) {
    val isPython = !item.isDirectory && item.name.endsWith(".py", ignoreCase = true)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianElevated)
            .border(1.dp, ObsidianOutline, RoundedCornerShape(14.dp))
            .clickable(enabled = !item.isDirectory, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("workspace_item_${item.name}")
    ) {
        Icon(
            imageVector = when {
                item.isDirectory -> Icons.Default.Folder
                isPython -> Icons.Default.Code
                else -> Icons.Default.Description
            },
            contentDescription = null,
            tint = when {
                item.isDirectory -> TerminalCyan
                isPython -> PhosphorGreen
                else -> TerminalTextMuted
            },
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.relativePath,
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                color = TerminalTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (item.isDirectory) "Directory" else "${item.sizeBytes} bytes",
                style = MaterialTheme.typography.labelSmall,
                color = TerminalTextMuted
            )
        }
        if (isPython) {
            IconButton(onClick = onRun, modifier = Modifier.testTag("run_py_${item.name}")) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Run Python", tint = PhosphorGreen)
            }
        }
        if (!item.isDirectory) {
            IconButton(onClick = onAskClaude, modifier = Modifier.testTag("claude_inspect_${item.name}")) {
                Icon(Icons.Default.AutoAwesome, contentDescription = "Ask Claude Code", tint = ClaudeAmber)
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.testTag("delete_${item.name}")) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = TerminalError)
        }
    }
}

@Composable
private fun CodeEditorView(
    editorState: EditorState,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onRun: () -> Unit,
    onAskClaude: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPython = editorState.relativePath.endsWith(".py", ignoreCase = true)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        Surface(color = ObsidianSurface, modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("editor_back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TerminalTextPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = editorState.relativePath + if (editorState.isDirty) " *" else "",
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = TerminalTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${editorState.content.lines().size} lines",
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalTextMuted
                    )
                }
                IconButton(onClick = onSave, modifier = Modifier.testTag("editor_save_button")) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = "Save",
                        tint = if (editorState.isDirty) TerminalCyan else TerminalTextMuted
                    )
                }
                IconButton(onClick = onAskClaude, modifier = Modifier.testTag("editor_claude_button")) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "Ask Claude Code", tint = ClaudeAmber)
                }
                if (isPython) {
                    Button(
                        onClick = onRun,
                        colors = ButtonDefaults.buttonColors(containerColor = PhosphorGreen, contentColor = ObsidianVoid),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("editor_run_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Run", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        val lines = remember(editorState.content) { editorState.content.lines() }
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            // Line numbers gutter
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                lines.forEachIndexed { idx, _ ->
                    Text(
                        text = "${idx + 1}",
                        style = TextStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        ),
                        color = TerminalTextMuted
                    )
                }
            }

            BasicTextField(
                value = editorState.content,
                onValueChange = onContentChange,
                textStyle = TextStyle(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = TerminalTextPrimary
                ),
                cursorBrush = SolidColor(PhosphorGreen),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("code_editor_textfield")
            )
        }
    }
}

@Composable
private fun CreatePathDialog(
    title: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        title = { Text(title, color = TerminalTextPrimary) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                colors = ButtonDefaults.buttonColors(containerColor = PhosphorGreen, contentColor = ObsidianVoid)
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
