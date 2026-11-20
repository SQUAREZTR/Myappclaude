package com.example.engine.agent

import com.example.data.local.NimSettings
import com.example.data.nvidia.ApiChatMessage
import com.example.data.nvidia.ApiFunctionSchema
import com.example.data.nvidia.ApiToolCall
import com.example.data.nvidia.ApiToolDefinition
import com.example.data.nvidia.NvidiaNimClient
import com.example.data.nvidia.NvidiaStreamEvent
import com.example.engine.python.PythonInterpreter
import com.example.engine.shell.FileEditResult
import com.example.engine.shell.WorkspaceManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File

sealed class AgentTerminalEvent {
    data class Banner(val text: String) : AgentTerminalEvent()
    data class ThinkingStart(val id: String) : AgentTerminalEvent()
    data class ThinkingUpdate(val id: String, val fullText: String) : AgentTerminalEvent()
    data class AssistantStart(val id: String) : AgentTerminalEvent()
    data class AssistantUpdate(val id: String, val fullText: String) : AgentTerminalEvent()
    data class ToolStart(val name: String, val detail: String) : AgentTerminalEvent()
    data class ToolOutput(val name: String, val output: String, val isError: Boolean = false) : AgentTerminalEvent()
    data class FileDiff(val diff: FileEditResult) : AgentTerminalEvent()
    data class SystemInfo(val text: String) : AgentTerminalEvent()
    data class Error(val text: String) : AgentTerminalEvent()
}

class ClaudeCodeAgent(
    private val nimClient: NvidiaNimClient,
    private val workspace: WorkspaceManager,
    private val cwdProvider: () -> File,
    private val settingsProvider: () -> NimSettings,
    private val pythonRunner: suspend (code: String, scriptName: String) -> Pair<Int, String>,
    private val shellRunner: suspend (command: String) -> Pair<Int, String>,
    private val askApproval: suspend (toolName: String, summary: String) -> Boolean,
    private val onWorkspaceChanged: suspend (String) -> Unit = {},
    private val onEvent: suspend (AgentTerminalEvent) -> Unit
) {
    private val history = mutableListOf<ApiChatMessage>()
    val activeSkills = mutableListOf<String>()
    var totalTurns: Int = 0
        private set
    var totalToolCalls: Int = 0
        private set

    fun clearHistory() {
        history.clear()
        totalTurns = 0
        totalToolCalls = 0
    }

    fun activateSkill(name: String): Boolean {
        val clean = name.trim().removeSuffix(".md")
        val exists = workspace.readSkill(clean) != null
        if (!exists) return false
        if (clean !in activeSkills) activeSkills += clean
        return true
    }

    fun deactivateSkill(name: String): Boolean {
        return activeSkills.remove(name.trim().removeSuffix(".md"))
    }

    fun compactHistory(): Int {
        if (history.size <= 4) return 0
        val kept = history.takeLast(4)
        val droppedCount = history.size - kept.size
        history.clear()
        history += ApiChatMessage(
            role = "system",
            content = "Previous $droppedCount messages were compacted. Continue assisting in ~/workspace."
        )
        history.addAll(kept)
        return droppedCount
    }

    private fun buildSystemPrompt(): String {
        val files = workspace.listFiles(".", workspace.rootDir, recursive = true)
            .take(50)
            .joinToString("\n") { f ->
                if (f.isDirectory) "  [dir]  ${f.relativePath}/"
                else "  [file] ${f.relativePath} (${f.sizeBytes}B)"
            }.ifBlank { "  (empty workspace)" }

        val claudeMd = workspace.readClaudeMd()?.take(2000)?.let {
            "\nProject Memory (CLAUDE.md):\n$it\n"
        }.orEmpty()

        val loadedSkillsText = if (activeSkills.isEmpty()) "" else {
            activeSkills.mapNotNull { skillName ->
                workspace.readSkill(skillName)?.let { body -> "\n[Active Skill: $skillName]\n$body" }
            }.joinToString("\n")
        }

        return """
You are Claude Code inside NimTerm, an autonomous terminal coding agent running on Android with a real local sandboxed workspace and embedded Python 3.12 interpreter.

Workspace: ${workspace.activeWorkspaceName} (${workspace.displayCwd(workspace.rootDir)})
Current directory: ${workspace.displayCwd(cwdProvider())}
Current workspace tree:
$files
$claudeMd$loadedSkillsText
Available Python stdlib + built-in modules: math, random, json, os, sys, time, datetime, re, collections, pathlib, statistics, string, urllib.request, nvidia, litellm.

Rules:
1. You have your own real sandboxed project workspace and can create/switch dedicated isolated workspaces with `manage_workspace` (list, create, switch).
2. Use the provided tools (read_file, write_file, edit_file, list_files, grep_files, run_python, run_shell, manage_workspace) to inspect, create, edit, and verify code directly in the workspace.
3. When writing or editing Python files, run them with `run_python` to verify there are no syntax or runtime errors before reporting success.
4. Keep terminal responses concise, technical, and clear. Never invent file contents you haven't read.
5. Reply in the user's language (e.g. Turkish or English) while keeping code, commands, and file paths exact.
        """.trimIndent()
    }

    private val toolDefinitions: List<ApiToolDefinition> = listOf(
        ApiToolDefinition(
            function = ApiFunctionSchema(
                name = "read_file",
                description = "Read the full contents of a file in the workspace.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "Relative path to file in workspace")
                    ),
                    "required" to listOf("path")
                )
            )
        ),
        ApiToolDefinition(
            function = ApiFunctionSchema(
                name = "write_file",
                description = "Create or overwrite a file in the workspace with full content.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "Relative file path"),
                        "content" to mapOf("type" to "string", "description" to "Complete file content")
                    ),
                    "required" to listOf("path", "content")
                )
            )
        ),
        ApiToolDefinition(
            function = ApiFunctionSchema(
                name = "edit_file",
                description = "Replace an exact snippet in an existing file with new text.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "Relative file path"),
                        "old_text" to mapOf("type" to "string", "description" to "Exact existing snippet to replace"),
                        "new_text" to mapOf("type" to "string", "description" to "Replacement text")
                    ),
                    "required" to listOf("path", "old_text", "new_text")
                )
            )
        ),
        ApiToolDefinition(
            function = ApiFunctionSchema(
                name = "list_files",
                description = "List files and directories inside the workspace.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "Directory path, default '.'"),
                        "recursive" to mapOf("type" to "boolean", "description" to "List recursively")
                    )
                )
            )
        ),
        ApiToolDefinition(
            function = ApiFunctionSchema(
                name = "grep_files",
                description = "Search for a regex or substring pattern across workspace files.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "pattern" to mapOf("type" to "string", "description" to "Search regex or text"),
                        "path" to mapOf("type" to "string", "description" to "Directory or file to search")
                    ),
                    "required" to listOf("pattern")
                )
            )
        ),
        ApiToolDefinition(
            function = ApiFunctionSchema(
                name = "run_python",
                description = "Execute a Python script file or inline Python code inside the workspace.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "file" to mapOf("type" to "string", "description" to "Workspace .py file to execute"),
                        "code" to mapOf("type" to "string", "description" to "Inline Python code to execute if file is omitted")
                    )
                )
            )
        ),
        ApiToolDefinition(
            function = ApiFunctionSchema(
                name = "run_shell",
                description = "Run a shell command in the workspace (ls, mkdir, cat, rm, cp, mv, sh ...).",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "command" to mapOf("type" to "string", "description" to "Shell command string")
                    ),
                    "required" to listOf("command")
                )
            )
        ),
        ApiToolDefinition(
            function = ApiFunctionSchema(
                name = "manage_workspace",
                description = "List, create, or switch isolated project workspaces so code and runs stay organized in their own directory.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf("type" to "string", "description" to "One of: list, create, switch"),
                        "name" to mapOf("type" to "string", "description" to "Workspace name for create or switch")
                    ),
                    "required" to listOf("action")
                )
            )
        )
    )

    suspend fun runPrompt(userPrompt: String) {
        val settings = settingsProvider()
        if (settings.apiKey.isBlank()) {
            onEvent(AgentTerminalEvent.Error("NVIDIA API key is missing. Open the Settings tab or run `/key nvapi-...` first."))
            return
        }

        history += ApiChatMessage(role = "user", content = userPrompt)
        totalTurns++

        var step = 0
        val maxSteps = settings.maxAgentSteps.coerceIn(2, 25)
        while (step < maxSteps) {
            currentCoroutineContext().ensureActive()
            step++

            val messages = buildList {
                add(ApiChatMessage(role = "system", content = buildSystemPrompt()))
                addAll(history.takeLast(24))
            }

            val thinkingId = "think_${System.nanoTime()}"
            val assistantId = "asst_${System.nanoTime()}"
            var thinkingStarted = false
            var assistantStarted = false
            val thinkingBuf = StringBuilder()
            val assistantBuf = StringBuilder()

            val result = nimClient.completeChat(
                apiKey = settings.apiKey,
                model = settings.selectedModelId,
                messages = messages,
                tools = toolDefinitions,
                temperature = settings.temperature.toDouble(),
                maxTokens = settings.maxTokens,
                baseUrl = settings.baseUrl
            ) { event ->
                when (event) {
                    is NvidiaStreamEvent.ReasoningDelta -> {
                        if (!thinkingStarted) {
                            thinkingStarted = true
                            onEvent(AgentTerminalEvent.ThinkingStart(thinkingId))
                        }
                        thinkingBuf.append(event.text)
                        onEvent(AgentTerminalEvent.ThinkingUpdate(thinkingId, thinkingBuf.toString()))
                    }
                    is NvidiaStreamEvent.ContentDelta -> {
                        if (!assistantStarted) {
                            assistantStarted = true
                            onEvent(AgentTerminalEvent.AssistantStart(assistantId))
                        }
                        assistantBuf.append(event.text)
                        onEvent(AgentTerminalEvent.AssistantUpdate(assistantId, assistantBuf.toString()))
                    }
                    is NvidiaStreamEvent.Completed -> Unit
                }
            }

            val completed = result.getOrElse { err ->
                onEvent(AgentTerminalEvent.Error(err.message ?: "NVIDIA NIM request failed"))
                return
            }

            val resolvedToolCalls = if (completed.toolCalls.isNotEmpty()) {
                completed.toolCalls
            } else {
                parseFallbackToolCalls(completed.content)
            }

            if (resolvedToolCalls.isEmpty()) {
                history += ApiChatMessage(role = "assistant", content = completed.content)
                return
            }

            history += ApiChatMessage(
                role = "assistant",
                content = completed.content.ifBlank { null },
                toolCalls = resolvedToolCalls
            )

            for (tc in resolvedToolCalls) {
                currentCoroutineContext().ensureActive()
                totalToolCalls++
                val toolOutput = executeToolCall(tc, settings)
                history += ApiChatMessage(
                    role = "tool",
                    toolCallId = tc.id,
                    name = tc.function.name,
                    content = toolOutput
                )
            }
        }

        onEvent(AgentTerminalEvent.SystemInfo("Reached max agent steps ($maxSteps). Send a follow-up prompt to continue."))
    }

    private suspend fun executeToolCall(tc: ApiToolCall, settings: NimSettings): String {
        val name = tc.function.name
        val args = runCatching { JSONObject(tc.function.arguments) }.getOrElse { JSONObject() }

        return try {
            when (name) {
                "read_file" -> {
                    val path = args.optString("path")
                    onEvent(AgentTerminalEvent.ToolStart("Read", path))
                    val text = workspace.readFile(path, cwdProvider())
                    val preview = if (text.length > 4000) text.take(4000) + "\n... (truncated)" else text
                    onEvent(AgentTerminalEvent.ToolOutput("Read", "Read ${text.lines().size} lines from $path"))
                    preview
                }
                "write_file" -> {
                    val path = args.optString("path")
                    val content = args.optString("content")
                    if (!settings.autoApproveTools) {
                        val ok = askApproval("write_file", "Write $path (${content.lines().size} lines)")
                        if (!ok) {
                            onEvent(AgentTerminalEvent.ToolOutput("Write", "Denied by user", isError = true))
                            return "Tool execution denied by user."
                        }
                    }
                    onEvent(AgentTerminalEvent.ToolStart("Write", path))
                    val diff = workspace.writeFile(path, content, cwdProvider())
                    onEvent(AgentTerminalEvent.FileDiff(diff))
                    "Wrote ${diff.relativePath} (+${diff.addedCount} / -${diff.removedCount} lines)"
                }
                "edit_file" -> {
                    val path = args.optString("path")
                    val oldText = args.optString("old_text")
                    val newText = args.optString("new_text")
                    if (!settings.autoApproveTools) {
                        val ok = askApproval("edit_file", "Edit $path")
                        if (!ok) {
                            onEvent(AgentTerminalEvent.ToolOutput("Edit", "Denied by user", isError = true))
                            return "Tool execution denied by user."
                        }
                    }
                    onEvent(AgentTerminalEvent.ToolStart("Edit", path))
                    val diff = workspace.editFile(path, oldText, newText, cwdProvider())
                    onEvent(AgentTerminalEvent.FileDiff(diff))
                    "Edited ${diff.relativePath} (+${diff.addedCount} / -${diff.removedCount} lines)"
                }
                "list_files" -> {
                    val path = args.optString("path", ".")
                    val recursive = args.optBoolean("recursive", false)
                    onEvent(AgentTerminalEvent.ToolStart("List", path))
                    val items = workspace.listFiles(path, cwdProvider(), recursive)
                    val out = if (items.isEmpty()) "(empty)" else items.joinToString("\n") {
                        if (it.isDirectory) "${it.relativePath}/" else "${it.relativePath} (${it.sizeBytes}B)"
                    }
                    onEvent(AgentTerminalEvent.ToolOutput("List", "${items.size} entries"))
                    out
                }
                "grep_files" -> {
                    val pattern = args.optString("pattern")
                    val path = args.optString("path", ".")
                    onEvent(AgentTerminalEvent.ToolStart("Grep", "$pattern in $path"))
                    val matches = workspace.grep(pattern, path, cwdProvider())
                    val out = if (matches.isEmpty()) "No matches found."
                    else matches.joinToString("\n") { "${it.relativePath}:${it.lineNumber}: ${it.lineText}" }
                    onEvent(AgentTerminalEvent.ToolOutput("Grep", "${matches.size} match(es)"))
                    out
                }
                "run_python" -> {
                    val file = args.optString("file")
                    val codeArg = args.optString("code")
                    val label = if (file.isNotBlank()) file else "-c <inline>"
                    if (!settings.autoApproveTools) {
                        val ok = askApproval("run_python", "Run python $label")
                        if (!ok) {
                            onEvent(AgentTerminalEvent.ToolOutput("Python", "Denied by user", isError = true))
                            return "Tool execution denied by user."
                        }
                    }
                    onEvent(AgentTerminalEvent.ToolStart("Python", label))
                    val (exitCode, output) = if (file.isNotBlank()) {
                        val source = workspace.readFile(file, cwdProvider())
                        pythonRunner(source, file)
                    } else {
                        pythonRunner(codeArg, "<agent>")
                    }
                    val trimmedOut = output.ifBlank { "(no output)" }
                    onEvent(AgentTerminalEvent.ToolOutput("Python", trimmedOut, isError = exitCode != 0))
                    "Exit code: $exitCode\n$trimmedOut"
                }
                "run_shell" -> {
                    val cmd = args.optString("command")
                    if (!settings.autoApproveTools) {
                        val ok = askApproval("run_shell", cmd)
                        if (!ok) {
                            onEvent(AgentTerminalEvent.ToolOutput("Shell", "Denied by user", isError = true))
                            return "Tool execution denied by user."
                        }
                    }
                    onEvent(AgentTerminalEvent.ToolStart("Shell", cmd))
                    val (exitCode, output) = shellRunner(cmd)
                    val trimmedOut = output.ifBlank { "(no output)" }
                    onEvent(AgentTerminalEvent.ToolOutput("Shell", trimmedOut, isError = exitCode != 0))
                    "Exit code: $exitCode\n$trimmedOut"
                }
                "manage_workspace" -> {
                    val action = args.optString("action", "list").lowercase()
                    val wsName = args.optString("name", "")
                    onEvent(AgentTerminalEvent.ToolStart("Workspace", "$action $wsName".trim()))
                    val resultText = when (action) {
                        "create" -> {
                            val created = workspace.createWorkspace(wsName, withStarterMain = true)
                            onWorkspaceChanged(created)
                            "Created & switched to workspace '$created' (${workspace.displayCwd(workspace.rootDir)})."
                        }
                        "switch" -> {
                            val ok = workspace.switchWorkspace(wsName)
                            if (ok) {
                                onWorkspaceChanged(workspace.activeWorkspaceName)
                                "Switched to workspace '${workspace.activeWorkspaceName}'."
                            } else {
                                "Workspace '$wsName' not found."
                            }
                        }
                        else -> {
                            workspace.listWorkspaces().joinToString("\n") { w ->
                                val mark = if (w.isActive) "● [ACTIVE]" else "○"
                                "$mark ${w.name} (${w.fileCount} items)"
                            }
                        }
                    }
                    onEvent(AgentTerminalEvent.ToolOutput("Workspace", resultText))
                    resultText
                }
                else -> "Unknown tool: $name"
            }
        } catch (e: Exception) {
            val msg = e.message ?: e::class.simpleName ?: "Tool error"
            onEvent(AgentTerminalEvent.ToolOutput(name, msg, isError = true))
            "Error: $msg"
        }
    }

    private fun parseFallbackToolCalls(content: String): List<ApiToolCall> {
        val regex = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
        val matches = regex.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexedNotNull { idx, match ->
            runCatching {
                val obj = JSONObject(match.groupValues[1])
                val name = obj.getString("name")
                val argsObj = obj.optJSONObject("arguments")?.toString() ?: obj.optString("arguments", "{}")
                ApiToolCall(
                    id = "fallback_call_$idx",
                    function = com.example.data.nvidia.ApiFunctionCall(name = name, arguments = argsObj)
                )
            }.getOrNull()
        }
    }
}
