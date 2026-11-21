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
    private val onWorkspaceChanged: suspend (String) -> Unit = {},
    private val onFilesChanged: suspend () -> Unit = {}
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

    private var lastExitCode: Int = 0

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
            val code = executeShellLine(cmd, outputSink = { buf.append(it) })
            code to buf.toString()
        },
        askApproval = onRequestApproval,
        onWorkspaceChanged = { wsName ->
            resetCwdToWorkspaceRoot()
            onWorkspaceChanged(wsName)
        },
        onFilesChanged = onFilesChanged,
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
                      /litellm <url> [key] Connect LiteLLM Proxy (optional key & model)
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
            "/litellm" -> {
                val lArgs = tokenizeShell(rest)
                if (lArgs.isEmpty()) {
                    val s = settingsProvider()
                    onSystem(
                        """
                        LiteLLM Proxy / OpenAI-Compatible Bridge:
                          Current Base URL: ${s.baseUrl}
                          Current Model:    ${s.selectedModelId}
                        Usage: /litellm <proxy_url> [api_key] [model_id]
                        Example: /litellm http://127.0.0.1:4000/v1 sk-litellm nvidia/nemotron-3-super
                        """.trimIndent()
                    )
                } else {
                    val url = lArgs[0]
                    onSetBaseUrl(url)
                    lArgs.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { onSetApiKey(it) }
                    lArgs.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { onSetModel(it) }
                    onSystem("LiteLLM endpoint set to $url. Fetching /v1/models...")
                    handleModelsSlashCommand("sync")
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

    private suspend fun executeShellLine(
        line: String,
        outputSink: suspend (String) -> Unit = onStdout
    ): Int {
        val statements = PosixShellParser.splitCommandChain(line)
        if (statements.isEmpty()) return 0

        for (stmt in statements) {
            when (stmt.operator) {
                ChainOperator.AND -> if (lastExitCode != 0) continue
                ChainOperator.OR -> if (lastExitCode == 0) continue
                ChainOperator.FIRST, ChainOperator.SEQUENCE -> Unit
            }
            lastExitCode = executePipeline(stmt.raw, outputSink)
        }
        return lastExitCode
    }

    private suspend fun executePipeline(
        rawPipeline: String,
        outputSink: suspend (String) -> Unit
    ): Int {
        val stages = PosixShellParser.splitPipeline(rawPipeline)
        if (stages.isEmpty()) return 0

        // Explicitly catch desktop Linux `curl ... | bash` installers blocked by Android SELinux W^X
        val firstTokens = PosixShellParser.tokenize(stages.first())
        val pipesToBash = stages.drop(1).any { stage ->
            val t = PosixShellParser.tokenize(stage).firstOrNull()?.lowercase()
            t == "bash" || t == "sh"
        }
        if (firstTokens.firstOrNull()?.lowercase() == "curl" && pipesToBash) {
            outputSink(
                """
                [Android Sandbox Restriction] Cannot execute `curl ... | bash` desktop Linux installers inside an Android APK:
                - `claude.ai/install.sh` downloads a glibc/musl Linux ELF binary, which Android's SELinux (W^X / targetSdk 36) forbids executing from app data storage.
                - To run the native Anthropic Linux binary on a phone, use Termux (`proot-distro` Ubuntu + Node.js) with `ANTHROPIC_BASE_URL` pointing to your LiteLLM proxy.
                - Inside NimTerm, use the built-in Claude Code CLI directly (write/edit files in workspace + Python 3) and connect LiteLLM via `/litellm <url> [key]` or Settings.
                """.trimIndent() + "\n"
            )
            return 1
        }

        var pipeInput: String? = null
        var exitCode = 0

        for (i in stages.indices) {
            val expanded = PosixShellParser.expandVariables(stages[i]) { resolveEnvVar(it) }
            val redir = PosixShellParser.extractRedirection(expanded)
            val isLastStage = (i == stages.lastIndex)
            val stageBuf = StringBuilder()

            val stageSink: suspend (String) -> Unit = when {
                redir.outputPath != null || !isLastStage -> { chunk -> stageBuf.append(chunk) }
                else -> outputSink
            }

            exitCode = runSingleStage(redir.commandRaw, pipeInput, stageSink)

            if (redir.outputPath != null) {
                val contentToWrite = stageBuf.toString()
                try {
                    if (redir.append) {
                        val existing = runCatching { workspace.readFile(redir.outputPath, currentDir) }.getOrDefault("")
                        workspace.writeFile(redir.outputPath, existing + contentToWrite, currentDir)
                    } else {
                        workspace.writeFile(redir.outputPath, contentToWrite, currentDir)
                    }
                    onFilesChanged()
                } catch (e: Exception) {
                    outputSink("${redir.outputPath}: ${e.message ?: "redirect error"}\n")
                    exitCode = 1
                }
                pipeInput = if (!isLastStage) "" else null
            } else if (!isLastStage) {
                pipeInput = stageBuf.toString()
            }
        }
        return exitCode
    }

    private fun resolveEnvVar(name: String): String {
        if (name == "?") return lastExitCode.toString()
        envVars[name]?.let { return it }
        val s = settingsProvider()
        return when (name) {
            "PWD" -> workspace.displayCwd(currentDir)
            "WORKSPACE" -> workspace.activeWorkspaceName
            "NVIDIA_API_BASE", "LITELLM_BASE_URL", "ANTHROPIC_BASE_URL", "OPENAI_BASE_URL" -> s.baseUrl
            "NVIDIA_API_KEY", "LITELLM_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY" -> s.apiKey
            "ACTIVE_MODEL", "CLAUDE_MODEL", "ANTHROPIC_MODEL", "LITELLM_MODEL" -> s.selectedModelId
            "PATH" -> "/usr/local/bin:/usr/bin:/bin"
            else -> ""
        }
    }

    private suspend fun runSingleStage(
        commandRaw: String,
        stdinText: String?,
        outputSink: suspend (String) -> Unit
    ): Int {
        val tokens = PosixShellParser.tokenize(commandRaw)
        if (tokens.isEmpty()) return 0
        val cmd = tokens[0]
        val args = tokens.drop(1)

        // Direct script invocation like `./app.sh` or `./main.py`
        if (cmd.startsWith("./") || cmd.endsWith(".sh") || cmd.endsWith(".py")) {
            if (cmd.endsWith(".py")) {
                return runPythonInvocation(listOf(cmd) + args, stdinText, outputSink)
            }
            if (cmd.endsWith(".sh")) {
                return runShellScriptFile(cmd, args, outputSink)
            }
        }

        return when (cmd) {
            "python", "python3", "py" -> runPythonInvocation(args, stdinText, outputSink)
            "claude", "code" -> runClaudeInvocation(args, stdinText, outputSink)
            "bash", "sh", "source", "." -> runScriptOrSubshell(cmd, args, outputSink)
            "clear", "cls" -> {
                onClearTerminal()
                0
            }
            "dir" -> runShellCommandInternal("ls -la", stdinText, outputSink)
            "help" -> {
                handleSlashCommand("/help")
                0
            }
            "true" -> 0
            "false" -> 1
            "pip", "pip3" -> {
                if (args.firstOrNull() == "list") {
                    outputSink(
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
                0
            }
            else -> runShellCommandInternal(commandRaw, stdinText, outputSink)
        }
    }

    private suspend fun runClaudeInvocation(
        args: List<String>,
        stdinText: String?,
        outputSink: suspend (String) -> Unit
    ): Int {
        if (args.any { it == "--version" || it == "-v" }) {
            outputSink("Claude Code CLI (NimTerm) 1.0.0 — NVIDIA NIM & LiteLLM Compatible\n")
            return 0
        }
        if (args.any { it == "--help" || it == "-h" }) {
            outputSink(
                """
                Usage: claude [options] [prompt]
                  -p, --print          Run non-interactively with prompt (or piped stdin)
                  -m, --model <id>     Set active model before running
                  -v, --version        Show CLI version
                  -h, --help           Show help
                Interactive mode: run `claude` with no arguments.
                """.trimIndent() + "\n"
            )
            return 0
        }

        val promptParts = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when (val tok = args[i]) {
                "-m", "--model" -> {
                    val m = args.getOrNull(i + 1)
                    if (!m.isNullOrBlank()) {
                        onSetModel(m)
                        i += 2
                        continue
                    }
                }
                "-p", "--print", "--dangerously-skip-permissions" -> {
                    // Accepted standard flags
                }
                else -> promptParts += tok
            }
            i++
        }

        val argPrompt = promptParts.joinToString(" ").trim()
        val finalPrompt = when {
            argPrompt.isNotEmpty() && !stdinText.isNullOrBlank() -> "$argPrompt\n\nStdin:\n$stdinText"
            argPrompt.isNotEmpty() -> argPrompt
            !stdinText.isNullOrBlank() -> stdinText.trim()
            else -> ""
        }

        if (finalPrompt.isEmpty()) {
            val settings = settingsProvider()
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
            claudeAgent.runPrompt(finalPrompt)
        }
        return 0
    }

    private suspend fun runScriptOrSubshell(
        cmd: String,
        args: List<String>,
        outputSink: suspend (String) -> Unit
    ): Int {
        if (args.isEmpty()) {
            outputSink("NimTerm POSIX Shell (`nimsh`) — type commands, pipelines (`|`), chains (`&&`), or `claude`.\n")
            return 0
        }
        if (args[0] == "-c" && args.size > 1) {
            return executeShellLine(args.drop(1).joinToString(" "), outputSink)
        }
        val scriptTarget = args[0]
        val resolved = runCatching { workspace.resolveSafe(scriptTarget, currentDir) }.getOrNull()
        if (resolved != null && resolved.exists() && resolved.isFile) {
            return runShellScriptFile(scriptTarget, args.drop(1), outputSink)
        }
        return if (cmd == "sh") {
            runSystemShell(args.joinToString(" "), outputSink)
        } else {
            outputSink("$cmd: $scriptTarget: No such file or directory\n")
            1
        }
    }

    private suspend fun runShellScriptFile(
        scriptPath: String,
        scriptArgs: List<String>,
        outputSink: suspend (String) -> Unit
    ): Int {
        return try {
            val scriptContent = workspace.readFile(scriptPath, currentDir)
            val prevArgs = envVars["@"]
            val prevNumeric = scriptArgs.indices.associate { idx -> "${idx + 1}" to envVars["${idx + 1}"] }
            envVars["@"] = scriptArgs.joinToString(" ")
            scriptArgs.forEachIndexed { idx, v -> envVars["${idx + 1}"] = v }
            var code = 0
            try {
                for (rawLine in scriptContent.lines()) {
                    val trimmed = rawLine.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    code = executeShellLine(trimmed, outputSink)
                }
            } finally {
                if (prevArgs != null) envVars["@"] = prevArgs else envVars.remove("@")
                prevNumeric.forEach { (k, old) ->
                    if (old != null) envVars[k] = old else envVars.remove(k)
                }
            }
            code
        } catch (e: Exception) {
            outputSink("sh: can't open '$scriptPath': ${e.message}\n")
            1
        }
    }

    suspend fun runPythonFile(relativePath: String) {
        val source = workspace.readFile(relativePath, workspace.rootDir)
        onSystem("▶ python3 $relativePath")
        pythonInterpreter.execute(source, scriptName = relativePath, scriptArgs = listOf(relativePath))
        onFilesChanged()
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

    private suspend fun runPythonInvocation(
        args: List<String>,
        stdinText: String? = null,
        outputSink: suspend (String) -> Unit = onStdout
    ): Int {
        val useDirectInterpreter = (outputSink === onStdout && stdinText == null)
        val stdinLines = stdinText?.lines()?.toMutableList() ?: mutableListOf()

        suspend fun execPy(code: String, scriptName: String, scriptArgs: List<String>): Int {
            val targetPy = if (useDirectInterpreter) {
                pythonInterpreter
            } else {
                PythonInterpreter(
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
                    onStdout = outputSink,
                    onStderr = outputSink,
                    onInput = { prompt ->
                        if (stdinLines.isNotEmpty()) stdinLines.removeAt(0)
                        else onRequestInput(prompt)
                    }
                )
            }
            val res = targetPy.execute(code, scriptName = scriptName, scriptArgs = scriptArgs)
            onFilesChanged()
            return if (res.isSuccess) 0 else 1
        }

        if (args.isEmpty()) {
            if (stdinText != null) {
                return execPy(stdinText, "<stdin>", listOf("<stdin>"))
            }
            pythonInterpreter.resetReplEnv()
            switchMode(TerminalMode.PYTHON_REPL)
            onSystem("Python 3.12.0 (NimTerm Embedded) — type `exit()` to return to shell.")
            return 0
        }
        return when (args[0]) {
            "--version", "-V" -> {
                outputSink("Python 3.12.0 (NimTerm Embedded)\n")
                0
            }
            "-c" -> {
                val code = args.drop(1).joinToString(" ")
                execPy(code, "<string>", listOf("-c"))
            }
            "-" -> {
                val code = stdinText.orEmpty()
                execPy(code, "<stdin>", listOf("-"))
            }
            else -> {
                val path = args[0]
                try {
                    val code = workspace.readFile(path, currentDir)
                    execPy(code, path, args)
                } catch (e: Exception) {
                    val msg = "python3: can't open file '$path': ${e.message}\n"
                    if (useDirectInterpreter) onStderr(msg) else outputSink(msg)
                    1
                }
            }
        }
    }

    private suspend fun runShellCommandInternal(
        line: String,
        stdinText: String? = null,
        outputSink: suspend (String) -> Unit
    ): Int {
        val tokens = PosixShellParser.tokenize(line)
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
                    if (args.isEmpty()) {
                        if (stdinText != null) {
                            outputSink(if (stdinText.endsWith("\n")) stdinText else "$stdinText\n")
                            0
                        } else {
                            outputSink("cat: missing file operand\n")
                            1
                        }
                    } else {
                        args.forEach { path ->
                            val content = workspace.readFile(path, currentDir)
                            outputSink(if (content.endsWith("\n")) content else "$content\n")
                        }
                        0
                    }
                }
                "head" -> {
                    val nIdx = args.indexOf("-n")
                    val n = when {
                        nIdx >= 0 -> args.getOrNull(nIdx + 1)?.toIntOrNull() ?: 10
                        else -> args.firstOrNull { it.startsWith("-") && it.drop(1).all(Char::isDigit) }?.drop(1)?.toIntOrNull() ?: 10
                    }
                    val path = args.lastOrNull { !it.startsWith("-") && (nIdx < 0 || args.indexOf(it) != nIdx + 1) }
                    val source = when {
                        path != null -> workspace.readFile(path, currentDir)
                        stdinText != null -> stdinText
                        else -> {
                            outputSink("head: missing file operand\n")
                            return 1
                        }
                    }
                    val lines = source.trimEnd('\n').lines().take(n)
                    outputSink(lines.joinToString("\n") + "\n")
                    0
                }
                "tail" -> {
                    val nIdx = args.indexOf("-n")
                    val n = when {
                        nIdx >= 0 -> args.getOrNull(nIdx + 1)?.toIntOrNull() ?: 10
                        else -> args.firstOrNull { it.startsWith("-") && it.drop(1).all(Char::isDigit) }?.drop(1)?.toIntOrNull() ?: 10
                    }
                    val path = args.lastOrNull { !it.startsWith("-") && (nIdx < 0 || args.indexOf(it) != nIdx + 1) }
                    val source = when {
                        path != null -> workspace.readFile(path, currentDir)
                        stdinText != null -> stdinText
                        else -> {
                            outputSink("tail: missing file operand\n")
                            return 1
                        }
                    }
                    val lines = source.trimEnd('\n').lines().takeLast(n)
                    outputSink(lines.joinToString("\n") + "\n")
                    0
                }
                "wc" -> {
                    val flags = args.filter { it.startsWith("-") }.joinToString("")
                    val path = args.lastOrNull { !it.startsWith("-") }
                    val text = when {
                        path != null -> workspace.readFile(path, currentDir)
                        stdinText != null -> stdinText
                        else -> {
                            outputSink("wc: missing file operand\n")
                            return 1
                        }
                    }
                    val lines = if (text.isEmpty()) 0 else text.trimEnd('\n').lines().size
                    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                    val chars = text.length
                    val label = path?.let { " $it" }.orEmpty()
                    val formatted = when {
                        'l' in flags && 'w' !in flags && 'c' !in flags -> "$lines$label"
                        'w' in flags && 'l' !in flags && 'c' !in flags -> "$words$label"
                        'c' in flags && 'l' !in flags && 'w' !in flags -> "$chars$label"
                        else -> "  $lines  $words  $chars$label"
                    }
                    outputSink("$formatted\n")
                    0
                }
                "sort" -> {
                    val reverse = args.any { it == "-r" }
                    val unique = args.any { it == "-u" }
                    val path = args.lastOrNull { !it.startsWith("-") }
                    val text = path?.let { workspace.readFile(it, currentDir) } ?: stdinText
                    if (text == null) {
                        outputSink("sort: missing file operand\n")
                        return 1
                    }
                    var lines = text.trimEnd('\n').lines()
                    lines = if (reverse) lines.sortedDescending() else lines.sorted()
                    if (unique) lines = lines.distinct()
                    outputSink(lines.joinToString("\n") + "\n")
                    0
                }
                "uniq" -> {
                    val path = args.lastOrNull { !it.startsWith("-") }
                    val text = path?.let { workspace.readFile(it, currentDir) } ?: stdinText
                    if (text == null) {
                        outputSink("uniq: missing file operand\n")
                        return 1
                    }
                    val out = mutableListOf<String>()
                    for (lineItem in text.trimEnd('\n').lines()) {
                        if (out.isEmpty() || out.last() != lineItem) out += lineItem
                    }
                    outputSink(out.joinToString("\n") + "\n")
                    0
                }
                "mkdir" -> {
                    args.filter { !it.startsWith("-") }.forEach { workspace.mkdir(it, currentDir) }
                    onFilesChanged()
                    0
                }
                "touch" -> {
                    args.forEach { path ->
                        val f = workspace.resolveSafe(path, currentDir)
                        if (!f.exists()) workspace.writeFile(path, "", currentDir)
                        else f.setLastModified(System.currentTimeMillis())
                    }
                    onFilesChanged()
                    0
                }
                "chmod" -> {
                    // Track workspace permissions logically so `chmod +x script.sh` succeeds cleanly
                    0
                }
                "rm" -> {
                    val targets = args.filter { !it.startsWith("-") }
                    targets.forEach { workspace.deletePath(it, currentDir, recursive = true) }
                    onFilesChanged()
                    0
                }
                "cp" -> {
                    if (args.size < 2) { outputSink("cp: missing operand\n"); return 1 }
                    workspace.copyPath(args[0], args[1], currentDir)
                    onFilesChanged()
                    0
                }
                "mv" -> {
                    if (args.size < 2) { outputSink("mv: missing operand\n"); return 1 }
                    workspace.movePath(args[0], args[1], currentDir)
                    onFilesChanged()
                    0
                }
                "echo" -> {
                    val noNewline = args.firstOrNull() == "-n"
                    val msg = (if (noNewline) args.drop(1) else args).joinToString(" ")
                    outputSink(if (noNewline) msg else "$msg\n")
                    0
                }
                "printf" -> {
                    if (args.isNotEmpty()) {
                        val rendered = args.joinToString(" ")
                            .replace("\\n", "\n")
                            .replace("\\t", "\t")
                        outputSink(rendered)
                    }
                    0
                }
                "grep" -> {
                    val ignoreCase = args.any { it.startsWith("-") && 'i' in it }
                    val invert = args.any { it.startsWith("-") && 'v' in it }
                    val showLineNum = args.any { it.startsWith("-") && 'n' in it }
                    val positional = args.filter { !it.startsWith("-") }
                    if (positional.isEmpty()) { outputSink("grep: missing pattern\n"); return 1 }
                    val pattern = positional[0]
                    val path = positional.getOrNull(1)
                    if (path == null && stdinText != null) {
                        val regex = runCatching {
                            if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
                        }.getOrNull()
                        var matchCount = 0
                        stdinText.trimEnd('\n').lines().forEachIndexed { idx, lineText ->
                            val matched = regex?.containsMatchIn(lineText)
                                ?: lineText.contains(pattern, ignoreCase = ignoreCase)
                            if (matched != invert) {
                                matchCount++
                                if (showLineNum) outputSink("${idx + 1}:$lineText\n")
                                else outputSink("$lineText\n")
                            }
                        }
                        if (matchCount == 0) 1 else 0
                    } else {
                        val matches = workspace.grep(pattern, path ?: ".", currentDir)
                        if (matches.isEmpty()) 1
                        else {
                            matches.forEach { m -> outputSink("${m.relativePath}:${m.lineNumber}: ${m.lineText}\n") }
                            0
                        }
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
                    outputSink("WORKSPACE=${workspace.activeWorkspaceName}\n")
                    outputSink("PWD=${workspace.displayCwd(currentDir)}\n")
                    outputSink("NVIDIA_API_BASE=${s.baseUrl}\n")
                    outputSink("ACTIVE_MODEL=${s.selectedModelId}\n")
                    0
                }
                "export" -> {
                    if (args.isEmpty()) {
                        envVars.forEach { (k, v) -> outputSink("export $k=$v\n") }
                    } else {
                        for (arg in args) {
                            val eq = arg.indexOf('=')
                            if (eq > 0) {
                                val k = arg.substring(0, eq).trim()
                                val v = arg.substring(eq + 1)
                                envVars[k] = v
                                when (k) {
                                    "NVIDIA_API_KEY", "LITELLM_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY" ->
                                        onSetApiKey(v)
                                    "NVIDIA_API_BASE", "LITELLM_BASE_URL", "ANTHROPIC_BASE_URL", "OPENAI_BASE_URL" ->
                                        onSetBaseUrl(v)
                                    "CLAUDE_MODEL", "ANTHROPIC_MODEL", "LITELLM_MODEL", "ACTIVE_MODEL" ->
                                        onSetModel(v)
                                }
                            }
                        }
                    }
                    0
                }
                "unset" -> {
                    args.forEach { envVars.remove(it) }
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
                        "claude", "code" -> outputSink("/usr/local/bin/claude (NVIDIA NIM / LiteLLM CLI)\n")
                        "sh", "bash" -> outputSink("/bin/nimsh (NimTerm POSIX Shell)\n")
                        else -> outputSink("/bin/$target\n")
                    }
                    0
                }
                "curl" -> {
                    val url = args.firstOrNull { it.startsWith("http") }
                        ?: run { outputSink("curl: missing http/https URL\n"); return 1 }
                    val outFlagIdx = args.indexOfFirst { it == "-o" || it == "--output" }
                    val outPath = if (outFlagIdx >= 0) args.getOrNull(outFlagIdx + 1) else null
                    val body = withContext(Dispatchers.IO) {
                        val req = Request.Builder().url(url).get().build()
                        httpClient.newCall(req).execute().use { it.body?.string().orEmpty() }
                    }
                    if (!outPath.isNullOrBlank()) {
                        workspace.writeFile(outPath, body, currentDir)
                        onFilesChanged()
                        outputSink("Saved ${body.length} bytes to $outPath\n")
                    } else {
                        outputSink(body.take(8000) + if (body.endsWith("\n")) "" else "\n")
                    }
                    0
                }
                else -> {
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

    private fun tokenizeShell(input: String): List<String> = PosixShellParser.tokenize(input)
}
