package com.example.engine.shell

import com.example.data.local.NimSettings
import com.example.data.nvidia.NvidiaNimClient
import com.example.engine.agent.AgentTerminalEvent
import com.example.engine.agent.ClaudeCodeAgent
import com.example.engine.python.PythonInterpreter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class TerminalMode {
    SHELL,
    PYTHON_REPL,
    CLAUDE_CODE
}

class TerminalShellEngine(
    private val workspace: WorkspaceManager,
    private val nimClient: NvidiaNimClient,
    private val settingsProvider: () -> NimSettings,
    private val onSetApiKey: suspend (String) -> Unit,
    private val onSetBaseUrl: suspend (String) -> Unit,
    private val onSetModel: suspend (String) -> Unit,
    private val onToggleAutoApprove: suspend () -> Boolean,
    private val onClearTerminal: suspend () -> Unit,
    private val onStdout: suspend (String) -> Unit,
    private val onStderr: suspend (String) -> Unit,
    private val onSystem: suspend (String) -> Unit,
    private val onAgentEvent: suspend (AgentTerminalEvent) -> Unit,
    private val onRequestInput: suspend (String) -> String,
    private val onRequestApproval: suspend (toolName: String, summary: String) -> Boolean,
    private val onModeChanged: (TerminalMode) -> Unit,
    private val onCwdChanged: (String) -> Unit,
    private val cachedModelsProvider: () -> List<com.example.data.local.NimModelEntity> = { emptyList() },
    private val onModelsSyncedFromV1: suspend (List<com.example.data.nvidia.RemoteModelItem>) -> Unit = {},
    private val onWorkspaceChanged: suspend (String) -> Unit = {}
) {
    var currentDir: File = workspace.rootDir
        private set

    var mode: TerminalMode = TerminalMode.CLAUDE_CODE
        private set

    private var lastListedModels: List<com.example.data.local.NimModelEntity> = emptyList()

    private val envVars = linkedMapOf(
        "HOME" to "~/workspace",
        "USER" to "developer",
        "SHELL" to "/bin/nimsh",
        "TERM" to "xterm-256color",
        "LANG" to "en_US.UTF-8"
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val pythonInterpreter = PythonInterpreter(
        workspace = workspace,
        cwdProvider = { currentDir },
        cwdSetter = { dir ->
            currentDir = dir
            onCwdChanged(workspace.displayCwd(dir))
        },
        nimClient = nimClient,
        apiKeyProvider = { settingsProvider().apiKey },
        baseUrlProvider = { settingsProvider().baseUrl },
        activeModelProvider = { settingsProvider().selectedModelId },
        onStdout = onStdout,
        onStderr = onStderr,
        onInput = onRequestInput
    )

    private val claudeAgent = ClaudeCodeAgent(
        nimClient = nimClient,
        workspace = workspace,
        cwdProvider = { currentDir },
        settingsProvider = settingsProvider,
        pythonRunner = { code, name ->
            val buf = StringBuilder()
            val subPy = PythonInterpreter(
                workspace = workspace,
                cwdProvider = { currentDir },
                cwdSetter = { currentDir = it },
                nimClient = nimClient,
                apiKeyProvider = { settingsProvider().apiKey },
                baseUrlProvider = { settingsProvider().baseUrl },
                activeModelProvider = { settingsProvider().selectedModelId },
                onStdout = { buf.append(it) },
                onStderr = { buf.append(it) },
                onInput = onRequestInput
            )
            val res = subPy.execute(code, scriptName = name)
            (if (res.isSuccess) 0 else 1) to buf.toString()
        },
        shellRunner = { cmd ->
            val buf = StringBuilder()
            val code = runShellCommandInternal(cmd, outputSink = { buf.append(it) })
            code to buf.toString()
        },
        askApproval = onRequestApproval,
        onWorkspaceChanged = { wsName ->
            resetCwdToWorkspaceRoot()
            onWorkspaceChanged(wsName)
        },
        onEvent = onAgentEvent
    )

    fun resetCwdToWorkspaceRoot() {
        currentDir = workspace.rootDir
        onCwdChanged(workspace.displayCwd(currentDir))
    }

    fun switchMode(newMode: TerminalMode) {
        mode = newMode
        onModeChanged(newMode)
    }

    suspend fun handleInput(rawInput: String) {
        val trimmed = rawInput.trim()

        // Global slash commands work across modes
        if (trimmed.startsWith("/")) {
            if (handleSlashCommand(trimmed)) return
        }

        when (mode) {
            TerminalMode.PYTHON_REPL -> {
                if (trimmed == "exit()" || trimmed == "quit()" || trimmed == "exit") {
                    switchMode(TerminalMode.SHELL)
                    onSystem("Exited Python REPL.")
                    return
                }
                if (trimmed.isEmpty()) return
                pythonInterpreter.execute(rawInput, scriptName = "<repl>", isRepl = true)
            }
            TerminalMode.CLAUDE_CODE -> {
                if (trimmed == "exit" || trimmed == "quit") {
                    switchMode(TerminalMode.SHELL)
                    onSystem("Exited Claude Code CLI. Type `claude` to return.")
                    return
                }
                if (trimmed.startsWith("!")) {
                    val bashCmd = trimmed.removePrefix("!").trim()
                    if (bashCmd.isNotEmpty()) {
                        executeShellLine(bashCmd)
                    }
                    return
                }
                if (trimmed.isEmpty()) return
                claudeAgent.runPrompt(rawInput)
            }
            TerminalMode.SHELL -> {
                if (trimmed.isEmpty()) return
                executeShellLine(trimmed)
            }
        }
    }

    private suspend fun handleSlashCommand(cmdLine: String): Boolean {
        val parts = tokenizeShell(cmdLine)
        val cmd = parts.firstOrNull()?.lowercase() ?: return false
        val rest = cmdLine.removePrefix(parts[0]).trim()

        when (cmd) {
            "/help" -> {
                onSystem(
                    """
                    Claude Code CLI Slash Commands:
                      /skills              List available .claude/skills/*.md skills
                      /skill <name>        Activate a skill (or `off <name>` / `create <name>`)
                      /init                Generate CLAUDE.md project memory from workspace
                      /memory              View CLAUDE.md project memory
                      /status              Show model, API status, turns, and active skills
                      /doctor              Run diagnostics on NVIDIA API, Python 3, & service
                      /compact             Compact conversation history to save context
                      /permissions         Toggle auto-approve vs interactive Allow/Deny
                      /model [id]          Show or switch active NVIDIA NIM / LiteLLM model
                      /modelno <no>        Select model by list number from /models
                      /models [page|sync]  Fetch /v1/models & list in pages
                      /workspace [cmd]     List, create, or switch isolated workspaces
                      /key <api_key>       Save API key directly from terminal
                      /base <url>          Set Base URL (NVIDIA NIM or LiteLLM Proxy)
                      /files               List workspace files
                      /python [file.py]    Enter Python REPL or run a .py file
                      /clear               Clear terminal & reset conversation
                      /exit                Drop to `${'$'}` workspace shell (or use `!<cmd>` directly)
                    """.trimIndent()
                )
                return true
            }
            "/skills" -> {
                val all = workspace.listSkills()
                val active = claudeAgent.activeSkills.toSet()
                val lines = all.joinToString("\n") { s ->
                    val mark = if (s in active) "● [ACTIVE]" else "○"
                    "  $mark $s  (.claude/skills/$s.md)"
                }
                onSystem("Available Skills (${all.size}):\n$lines\n\nUse `/skill <name>` to load or `/skill off <name>` to unload.")
                return true
            }
            "/skill" -> {
                val argParts = rest.split(Regex("\\s+"), limit = 2).filter { it.isNotBlank() }
                when {
                    argParts.isEmpty() -> {
                        val active = claudeAgent.activeSkills
                        onSystem(
                            if (active.isEmpty()) "No active skills. Run `/skills` to list or `/skill python-dev` to activate."
                            else "Active skills: ${active.joinToString(", ")}"
                        )
                    }
                    argParts[0].equals("off", ignoreCase = true) && argParts.size > 1 -> {
                        val removed = claudeAgent.deactivateSkill(argParts[1])
                        onSystem(if (removed) "Deactivated skill `${argParts[1]}`." else "Skill `${argParts[1]}` was not active.")
                    }
                    argParts[0].equals("create", ignoreCase = true) && argParts.size > 1 -> {
                        val created = workspace.createSkill(argParts[1], "Custom user skill created via /skill create.")
                        claudeAgent.activateSkill(created)
                        onSystem("Created & activated `.claude/skills/$created.md`.")
                    }
                    else -> {
                        val name = argParts[0]
                        if (claudeAgent.activateSkill(name)) {
                            onSystem("● Loaded skill `$name` into Claude Code context.")
                        } else {
                            onStderr("Skill `$name` not found. Run `/skills` to see available skills or `/skill create $name`.\n")
                        }
                    }
                }
                return true
            }
            "/init" -> {
                val files = workspace.listFiles(".", workspace.rootDir, recursive = true)
                    .filter { !it.relativePath.startsWith(".claude") }
                    .joinToString("\n") { "- `${it.relativePath}` (${if (it.isDirectory) "dir" else "${it.sizeBytes}B"})" }
                    .ifBlank { "- (empty workspace)" }
                val md = """
                    # CLAUDE.md
                    
                    Project workspace for NimTerm (Android embedded Python 3.12 + NVIDIA NIM / LiteLLM).
                    
                    ## Workspace Files
                    $files
                    
                    ## Conventions
                    - Run Python scripts with `python3 <file.py>` after editing to verify behavior.
                    - Keep secrets out of source files; use `import nvidia` / `import litellm` for API calls.
                """.trimIndent() + "\n"
                workspace.writeClaudeMd(md)
                onSystem("Initialized `CLAUDE.md` in ~/workspace.")
                return true
            }
            "/memory" -> {
                val md = workspace.readClaudeMd()
                if (md == null) onSystem("No `CLAUDE.md` found yet. Run `/init` to generate it.")
                else onStdout(md + "\n")
                return true
            }
            "/status" -> {
                val s = settingsProvider()
                val keyState = if (s.apiKey.isBlank()) "NOT SET" else "Configured (${s.apiKey.take(8)}...)"
                val activeSkills = claudeAgent.activeSkills.ifEmpty { listOf("none") }.joinToString(", ")
                onSystem(
                    """
                    Claude Code CLI Status:
                      Mode:         ${mode.name}
                      Model:        ${s.selectedModelId}
                      Endpoint:     ${s.baseUrl}
                      API Key:      $keyState
                      Permissions:  ${if (s.autoApproveTools) "Auto-approve tools" else "Ask before edits/exec"}
                      Active Skills:$activeSkills
                      Session:      ${claudeAgent.totalTurns} turns • ${claudeAgent.totalToolCalls} tool calls
                      CWD:          ${workspace.displayCwd(currentDir)}
                    """.trimIndent()
                )
                return true
            }
            "/doctor" -> {
                val s = settingsProvider()
                onSystem("Running NimTerm CLI diagnostics...")
                val sb = StringBuilder()
                sb.appendLine("  [✓] Embedded Python 3.12 runtime ready")
                sb.appendLine("  [✓] Workspace sandbox: ${workspace.displayCwd(currentDir)}")
                sb.appendLine("  [✓] Skills loaded: ${workspace.listSkills().size} in .claude/skills/")
                if (s.apiKey.isBlank()) {
                    sb.appendLine("  [!] API Key missing — run `/key nvapi-...` or open Settings")
                } else {
                    val testRes = nimClient.testConnection(s.apiKey, s.baseUrl)
                    testRes.onSuccess { count ->
                        sb.appendLine("  [✓] API connected (${s.baseUrl}): $count live models")
                    }.onFailure { err ->
                        sb.appendLine("  [✗] API check failed: ${err.message}")
                    }
                }
                onStdout(sb.toString())
                return true
            }
            "/compact" -> {
                val dropped = claudeAgent.compactHistory()
                onSystem(if (dropped > 0) "Compacted $dropped older messages from agent context." else "Context already compact.")
                return true
            }
            "/clear" -> {
                claudeAgent.clearHistory()
                onClearTerminal()
                return true
            }
            "/exit" -> {
                switchMode(TerminalMode.SHELL)
                onSystem("Switched to Workspace Shell (`${'$'}`). Type `claude` to re-enter Claude Code CLI.")
                return true
            }
            "/model" -> {
                if (rest.isBlank()) {
                    val s = settingsProvider()
                    onSystem("Active model: ${s.selectedModelId}\nEndpoint: ${s.baseUrl}")
                } else if (rest.toIntOrNull() != null && cachedModelsProvider().none { it.id == rest }) {
                    onStderr("'$rest' is a number, not a model ID. Use `/modelno $rest` to select by list number, or `/model <full_id>`.\n")
                } else {
                    onSetModel(rest)
                    onSystem("Switched active model to: $rest")
                }
                return true
            }
            "/modelno" -> {
                val source = lastListedModels.ifEmpty { cachedModelsProvider() }
                val num = rest.toIntOrNull()
                if (num == null || num !in 1..source.size) {
                    onStderr("Invalid model number '${rest.ifBlank { "?" }}'. Run `/models` to view numbers (1–${source.size}).\n")
                } else {
                    val chosen = source[num - 1]
                    onSetModel(chosen.id)
                    onSystem("Switched active model (#$num) to: ${chosen.id}")
                }
                return true
            }
            "/models" -> {
                handleModelsSlashCommand(rest)
                return true
            }
            "/workspace", "/ws" -> {
                handleWorkspaceSlashCommand(rest)
                return true
            }
            "/key" -> {
                if (rest.isBlank()) {
                    val k = settingsProvider().apiKey
                    val masked = if (k.length > 10) "${k.take(8)}...${k.takeLast(4)}" else "(not set)"
                    onSystem("Current API key: $masked\nUsage: /key nvapi-YOUR_KEY")
                } else {
                    onSetApiKey(rest)
                    onSystem("API key saved securely.")
                }
                return true
            }
            "/base" -> {
                if (rest.isBlank()) {
                    onSystem("Current Base URL: ${settingsProvider().baseUrl}\nUsage: /base https://integrate.api.nvidia.com/v1")
                } else {
                    onSetBaseUrl(rest)
                    onSystem("Base URL updated to: $rest")
                }
                return true
            }
            "/files" -> {
                val items = workspace.listFiles(".", currentDir, recursive = true)
                if (items.isEmpty()) onStdout("(workspace empty)\n")
                else onStdout(items.joinToString("\n") {
                    if (it.isDirectory) "📁 ${it.relativePath}/" else "📄 ${it.relativePath} (${it.sizeBytes}B)"
                } + "\n")
                return true
            }
            "/auto", "/permissions" -> {
                val nowAuto = onToggleAutoApprove()
                onSystem("Permissions: ${if (nowAuto) "▶▶ bypass permissions (auto-approve)" else "Ask [Allow/Deny] before write/exec"}.")
                return true
            }
            "/python" -> {
                if (rest.isBlank()) {
                    pythonInterpreter.resetReplEnv()
                    switchMode(TerminalMode.PYTHON_REPL)
                    onSystem("Python 3.12.0 (NimTerm Embedded) — type `exit()` to return.")
                } else {
                    runPythonInvocation(listOf(rest))
                }
                return true
            }
        }
        return false
    }

    private suspend fun handleWorkspaceSlashCommand(rest: String) {
        val args = tokenizeShell(rest)
        val sub = args.firstOrNull()?.lowercase()
        when (sub) {
            null, "list", "ls" -> {
                val list = workspace.listWorkspaces()
                val lines = list.joinToString("\n") { w ->
                    val mark = if (w.isActive) "● [ACTIVE]" else "○"
                    "  $mark ${w.name} (${w.fileCount} files)"
                }
                onSystem("Workspaces:\n$lines\n\nCommands: /workspace new <name> • /workspace use <name> • /workspace delete <name>")
            }
            "new", "create" -> {
                val name = args.getOrNull(1)
                if (name.isNullOrBlank()) {
                    onStderr("Usage: /workspace new <project_name>\n")
                } else {
                    val created = workspace.createWorkspace(name, withStarterMain = true)
                    resetCwdToWorkspaceRoot()
                    onWorkspaceChanged(created)
                    onSystem("Created & switched to isolated workspace `$created` (${workspace.displayCwd(currentDir)}).")
                }
            }
            "use", "switch", "cd" -> {
                val name = args.getOrNull(1)
                if (name.isNullOrBlank()) {
                    onStderr("Usage: /workspace use <name>\n")
                } else if (workspace.switchWorkspace(name)) {
                    resetCwdToWorkspaceRoot()
                    onWorkspaceChanged(workspace.activeWorkspaceName)
                    onSystem("Switched to workspace `${workspace.activeWorkspaceName}` (${workspace.displayCwd(currentDir)}).")
                } else {
                    onStderr("Workspace `$name` not found. Run `/workspace new $name` to create it.\n")
                }
            }
            "delete", "rm" -> {
                val name = args.getOrNull(1)
                if (name.isNullOrBlank()) {
                    onStderr("Usage: /workspace delete <name>\n")
                } else if (workspace.deleteWorkspace(name)) {
                    resetCwdToWorkspaceRoot()
                    onWorkspaceChanged(workspace.activeWorkspaceName)
                    onSystem("Deleted workspace `$name`. Active: `${workspace.activeWorkspaceName}`.")
                } else {
                    onStderr("Cannot delete workspace `$name` (not found or is `default`).\n")
                }
            }
            else -> {
                // Shorthand: `/workspace <name>` switches or prompts
                if (workspace.switchWorkspace(sub)) {
                    resetCwdToWorkspaceRoot()
                    onWorkspaceChanged(workspace.activeWorkspaceName)
                    onSystem("Switched to workspace `${workspace.activeWorkspaceName}`.")
                } else {
                    onStderr("Unknown workspace command or name `$sub`. Try `/workspace new $sub`.\n")
                }
            }
        }
    }

    private suspend fun handleModelsSlashCommand(rest: String) {
        val args = tokenizeShell(rest)
        val first = args.firstOrNull()?.lowercase()
        val settings = settingsProvider()

        val shouldFetchV1 = first == null || first == "sync" || first == "v1"
        var v1Note = ""
        if (shouldFetchV1) {
            onSystem("Fetching models from /v1/models (${settings.baseUrl})...")
            val res = nimClient.fetchLiveModels(settings.apiKey, settings.baseUrl)
            res.onSuccess { remoteList ->
                onModelsSyncedFromV1(remoteList)
                v1Note = "Live /v1/models: ${remoteList.size} models fetched."
            }.onFailure { err ->
                v1Note = "Live /v1/models unavailable (${err.message}); showing local catalog."
            }
        }

        val pageSize = 10
        var searchFilter = ""
        var requestedPage = 1

        when {
            first == null || first == "sync" || first == "v1" -> {
                requestedPage = args.getOrNull(1)?.toIntOrNull() ?: 1
            }
            first.toIntOrNull() != null -> {
                requestedPage = first.toInt()
            }
            first == "search" -> {
                searchFilter = args.getOrNull(1).orEmpty()
                requestedPage = args.getOrNull(2)?.toIntOrNull() ?: 1
            }
            else -> {
                searchFilter = first
                requestedPage = args.getOrNull(1)?.toIntOrNull() ?: 1
            }
        }

        val allModels = cachedModelsProvider()
        val filtered = if (searchFilter.isBlank()) allModels else {
            val q = searchFilter.lowercase()
            allModels.filter {
                it.id.lowercase().contains(q) ||
                    it.displayName.lowercase().contains(q) ||
                    it.provider.lowercase().contains(q)
            }
        }

        if (filtered.isEmpty()) {
            onSystem("$v1Note\nNo models matched '$searchFilter'.")
            return
        }

        lastListedModels = filtered
        val totalPages = ((filtered.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val page = requestedPage.coerceIn(1, totalPages)
        val slice = filtered.drop((page - 1) * pageSize).take(pageSize)
        val startNum = (page - 1) * pageSize + 1
        val endNum = startNum + slice.size - 1

        val lines = slice.mapIndexed { idx, m ->
            val num = startNum + idx
            val activeMark = if (m.id == settings.selectedModelId) "★" else " "
            val liveMark = if (m.isLiveApi) "[V1]" else "    "
            "$activeMark %2d. %s %s (%s)".format(num, liveMark, m.id, m.category)
        }.joinToString("\n")

        val header = buildString {
            if (v1Note.isNotBlank()) appendLine(v1Note)
            append("Models Page $page/$totalPages ($startNum–$endNum of ${filtered.size})")
            if (searchFilter.isNotBlank()) append(" • filter='$searchFilter'")
        }
        val footer = "\nUse `/models ${if (page < totalPages) page + 1 else 1}` for ${if (page < totalPages) "next page" else "page 1"} • `/modelno <no>` or `/model <id>` to select."
        onSystem("$header\n$lines$footer")
    }

    private suspend fun executeShellLine(line: String) {
        val tokens = tokenizeShell(line)
        if (tokens.isEmpty()) return
        val cmd = tokens[0]
        val args = tokens.drop(1)

        when (cmd) {
            "python", "python3", "py" -> runPythonInvocation(args)
            "claude", "code" -> {
                val settings = settingsProvider()
                if (args.isEmpty()) {
                    switchMode(TerminalMode.CLAUDE_CODE)
                    val keyNote = if (settings.apiKey.isBlank()) {
                        "\n⚠ API key not set yet. Enter `/key nvapi-YOUR_KEY` here or open Settings."
                    } else ""
                    onAgentEvent(
                        AgentTerminalEvent.Banner(
                            "Claude Code CLI • Model: ${settings.selectedModelId}\nWorkspace: ${workspace.displayCwd(currentDir)} • Type `/help` or `/exit`$keyNote"
                        )
                    )
                } else {
                    val prompt = line.removePrefix(cmd).trim().removeSurrounding("\"").removeSurrounding("'")
                    claudeAgent.runPrompt(prompt)
                }
            }
            "clear", "cls" -> onClearTerminal()
            "dir" -> runShellCommandInternal("ls -la") { chunk -> onStdout(chunk) }
            "help" -> handleSlashCommand("/help")
            "pip", "pip3" -> {
                if (args.firstOrNull() == "list") {
                    onStdout(
                        """
                        Package         Version
                        --------------- ---------
                        nvidia-nim      1.0.0
                        litellm         1.60.0 (built-in bridge)
                        math / random   3.12 stdlib
                        json / re       3.12 stdlib
                        os / pathlib    3.12 stdlib
                        datetime / time 3.12 stdlib
                        urllib          3.12 stdlib
                        """.trimIndent() + "\n"
                    )
                } else {
                    onSystem("Embedded Python includes stdlib + `nvidia` + `litellm` modules natively.")
                }
            }
            else -> {
                runShellCommandInternal(line) { chunk -> onStdout(chunk) }
            }
        }
    }

    suspend fun runPythonFile(relativePath: String) {
        val source = workspace.readFile(relativePath, workspace.rootDir)
        onSystem("▶ python3 $relativePath")
        pythonInterpreter.execute(source, scriptName = relativePath, scriptArgs = listOf(relativePath))
    }

    suspend fun startClaudeWithFile(relativePath: String) {
        switchMode(TerminalMode.CLAUDE_CODE)
        val settings = settingsProvider()
        onAgentEvent(
            AgentTerminalEvent.Banner(
                "Claude Code CLI • Inspecting $relativePath • Model: ${settings.selectedModelId}"
            )
        )
        claudeAgent.runPrompt("Read `$relativePath`, explain what it does concisely, and suggest or fix any improvements.")
    }

    private suspend fun runPythonInvocation(args: List<String>) {
        if (args.isEmpty()) {
            pythonInterpreter.resetReplEnv()
            switchMode(TerminalMode.PYTHON_REPL)
            onSystem("Python 3.12.0 (NimTerm Embedded) — type `exit()` to return to shell.")
            return
        }
        when (args[0]) {
            "--version", "-V" -> onStdout("Python 3.12.0 (NimTerm Embedded)\n")
            "-c" -> {
                val code = args.drop(1).joinToString(" ")
                pythonInterpreter.execute(code, scriptName = "<string>")
            }
            else -> {
                val path = args[0]
                try {
                    val code = workspace.readFile(path, currentDir)
                    pythonInterpreter.execute(code, scriptName = path, scriptArgs = args)
                } catch (e: Exception) {
                    onStderr("python3: can't open file '$path': ${e.message}\n")
                }
            }
        }
    }

    private suspend fun runShellCommandInternal(
        line: String,
        outputSink: suspend (String) -> Unit
    ): Int {
        val tokens = tokenizeShell(line)
        if (tokens.isEmpty()) return 0
        val cmd = tokens[0]
        val args = tokens.drop(1)

        return try {
            when (cmd) {
                "pwd" -> {
                    outputSink(workspace.displayCwd(currentDir) + "\n")
                    0
                }
                "cd" -> {
                    val target = args.firstOrNull() ?: "~"
                    val resolved = workspace.resolveSafe(target, currentDir)
                    if (!resolved.exists() || !resolved.isDirectory) {
                        outputSink("cd: no such directory: $target\n")
                        1
                    } else {
                        currentDir = resolved
                        onCwdChanged(workspace.displayCwd(resolved))
                        0
                    }
                }
                "ls" -> {
                    val showLong = args.any { it.startsWith("-") && ('l' in it || 'a' in it) }
                    val pathArg = args.firstOrNull { !it.startsWith("-") } ?: "."
                    val items = workspace.listFiles(pathArg, currentDir, recursive = false)
                    if (items.isEmpty()) {
                        outputSink("(empty)\n")
                    } else if (showLong) {
                        val fmt = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                        items.forEach { item ->
                            val typeChar = if (item.isDirectory) "d" else "-"
                            val dateStr = fmt.format(Date(item.lastModified))
                            val name = if (item.isDirectory) "${item.name}/" else item.name
                            outputSink(String.format(Locale.US, "%srw-r--r-- %7d %s %s\n", typeChar, item.sizeBytes, dateStr, name))
                        }
                    } else {
                        outputSink(items.joinToString("  ") { if (it.isDirectory) "${it.name}/" else it.name } + "\n")
                    }
                    0
                }
                "tree" -> {
                    val items = workspace.listFiles(args.firstOrNull() ?: ".", currentDir, recursive = true)
                    if (items.isEmpty()) outputSink("(empty)\n")
                    else items.forEach { item ->
                        val depth = item.relativePath.count { it == '/' }
                        val indent = "  ".repeat(depth)
                        outputSink("$indent├── ${item.name}${if (item.isDirectory) "/" else ""}\n")
                    }
                    0
                }
                "cat" -> {
                    if (args.isEmpty()) { outputSink("cat: missing file operand\n"); return 1 }
                    args.forEach { path ->
                        val content = workspace.readFile(path, currentDir)
                        outputSink(if (content.endsWith("\n")) content else "$content\n")
                    }
                    0
                }
                "head" -> {
                    val path = args.lastOrNull() ?: return 1
                    val n = args.indexOf("-n").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: 10
                    val lines = workspace.readFile(path, currentDir).lines().take(n)
                    outputSink(lines.joinToString("\n") + "\n")
                    0
                }
                "tail" -> {
                    val path = args.lastOrNull() ?: return 1
                    val n = args.indexOf("-n").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: 10
                    val lines = workspace.readFile(path, currentDir).lines().takeLast(n)
                    outputSink(lines.joinToString("\n") + "\n")
                    0
                }
                "wc" -> {
                    val path = args.lastOrNull() ?: return 1
                    val text = workspace.readFile(path, currentDir)
                    val lines = text.lines().size
                    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                    outputSink("  $lines  $words  ${text.length} $path\n")
                    0
                }
                "mkdir" -> {
                    args.filter { !it.startsWith("-") }.forEach { workspace.mkdir(it, currentDir) }
                    0
                }
                "touch" -> {
                    args.forEach { path ->
                        val f = workspace.resolveSafe(path, currentDir)
                        if (!f.exists()) workspace.writeFile(path, "", currentDir)
                        else f.setLastModified(System.currentTimeMillis())
                    }
                    0
                }
                "rm" -> {
                    val targets = args.filter { !it.startsWith("-") }
                    targets.forEach { workspace.deletePath(it, currentDir, recursive = true) }
                    0
                }
                "cp" -> {
                    if (args.size < 2) { outputSink("cp: missing operand\n"); return 1 }
                    workspace.copyPath(args[0], args[1], currentDir)
                    0
                }
                "mv" -> {
                    if (args.size < 2) { outputSink("mv: missing operand\n"); return 1 }
                    workspace.movePath(args[0], args[1], currentDir)
                    0
                }
                "echo" -> {
                    val redirIdx = args.indexOfFirst { it == ">" || it == ">>" }
                    if (redirIdx >= 0 && redirIdx + 1 < args.size) {
                        val text = args.take(redirIdx).joinToString(" ") + "\n"
                        val file = args[redirIdx + 1]
                        if (args[redirIdx] == ">>") {
                            val existing = runCatching { workspace.readFile(file, currentDir) }.getOrDefault("")
                            workspace.writeFile(file, existing + text, currentDir)
                        } else {
                            workspace.writeFile(file, text, currentDir)
                        }
                    } else {
                        outputSink(args.joinToString(" ") + "\n")
                    }
                    0
                }
                "grep" -> {
                    if (args.isEmpty()) { outputSink("grep: missing pattern\n"); return 1 }
                    val pattern = args[0]
                    val path = args.getOrNull(1) ?: "."
                    val matches = workspace.grep(pattern, path, currentDir)
                    if (matches.isEmpty()) 1
                    else {
                        matches.forEach { m -> outputSink("${m.relativePath}:${m.lineNumber}: ${m.lineText}\n") }
                        0
                    }
                }
                "find" -> {
                    val items = workspace.listFiles(args.firstOrNull() ?: ".", currentDir, recursive = true)
                    items.forEach { outputSink("${it.relativePath}\n") }
                    0
                }
                "env" -> {
                    envVars.forEach { (k, v) -> outputSink("$k=$v\n") }
                    val s = settingsProvider()
                    outputSink("NVIDIA_API_BASE=${s.baseUrl}\n")
                    outputSink("ACTIVE_MODEL=${s.selectedModelId}\n")
                    0
                }
                "export" -> {
                    args.forEach { arg ->
                        val eq = arg.indexOf('=')
                        if (eq > 0) envVars[arg.substring(0, eq)] = arg.substring(eq + 1)
                    }
                    0
                }
                "uname" -> {
                    outputSink("Linux localhost Android-ARM64 NimTerm/1.0\n")
                    0
                }
                "whoami" -> {
                    outputSink("${envVars["USER"] ?: "developer"}\n")
                    0
                }
                "date" -> {
                    outputSink(Date().toString() + "\n")
                    0
                }
                "which" -> {
                    val target = args.firstOrNull().orEmpty()
                    when (target) {
                        "python", "python3" -> outputSink("/usr/bin/python3 (embedded)\n")
                        "claude", "code" -> outputSink("/usr/local/bin/claude (NVIDIA NIM CLI)\n")
                        else -> outputSink("/bin/$target\n")
                    }
                    0
                }
                "curl" -> {
                    val url = args.firstOrNull { it.startsWith("http") }
                        ?: run { outputSink("curl: missing http/https URL\n"); return 1 }
                    val body = withContext(Dispatchers.IO) {
                        val req = Request.Builder().url(url).get().build()
                        httpClient.newCall(req).execute().use { it.body?.string().orEmpty() }
                    }
                    outputSink(body.take(8000) + "\n")
                    0
                }
                "sh" -> {
                    val subCmd = line.removePrefix("sh").trim()
                    if (subCmd.isBlank()) {
                        outputSink("Usage: sh <android-shell-command>\n")
                        return 1
                    }
                    runSystemShell(subCmd, outputSink)
                }
                else -> {
                    // Attempt real Android /system/bin/sh execution for native commands like df, id, uptime, etc.
                    runSystemShell(line, outputSink)
                }
            }
        } catch (e: Exception) {
            outputSink("$cmd: ${e.message ?: "error"}\n")
            1
        }
    }

    private suspend fun runSystemShell(cmd: String, outputSink: suspend (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.directory(currentDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            try {
                val reader = proc.inputStream.bufferedReader()
                val buf = CharArray(1024)
                while (true) {
                    val read = reader.read(buf)
                    if (read == -1) break
                    outputSink(String(buf, 0, read))
                }
                proc.waitFor()
            } finally {
                if (proc.isAlive) proc.destroyForcibly()
            }
        }

    private fun tokenizeShell(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '\\' && !inSingle && i + 1 < input.length -> {
                    cur.append(input[i + 1]); i += 2; continue
                }
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (cur.isNotEmpty()) {
                        tokens += cur.toString()
                        cur.clear()
                    }
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) tokens += cur.toString()
        return tokens
    }
}
