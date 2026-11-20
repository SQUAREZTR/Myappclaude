package com.example.service

import android.content.Context
import com.example.data.local.CommandHistoryEntity
import com.example.data.local.NimDatabase
import com.example.data.local.NimModelEntity
import com.example.data.local.NimSettings
import com.example.data.local.NimSettingsDataStore
import com.example.data.local.TerminalEntryEntity
import com.example.data.nvidia.NvidiaModelCatalog
import com.example.data.nvidia.NvidiaNimClient
import com.example.data.nvidia.RemoteModelItem
import com.example.engine.agent.AgentTerminalEvent
import com.example.engine.shell.TerminalMode
import com.example.engine.shell.TerminalShellEngine
import com.example.engine.shell.WorkspaceInfo
import com.example.engine.shell.WorkspaceManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PendingPromptRequest(
    val id: Long = System.currentTimeMillis(),
    val isApproval: Boolean,
    val title: String,
    val detail: String
)

class TerminalRuntimeHost(private val appContext: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsDataStore = NimSettingsDataStore(appContext)
    val database = NimDatabase.getInstance(appContext)
    val workspaceManager = WorkspaceManager(appContext)
    val nimClient = NvidiaNimClient()

    private val terminalDao = database.terminalEntryDao()
    private val historyDao = database.commandHistoryDao()
    private val modelDao = database.nimModelDao()

    val settings: StateFlow<NimSettings> = settingsDataStore.settingsFlow
        .stateIn(scope, SharingStarted.Eagerly, NimSettings())

    val terminalEntries: StateFlow<List<TerminalEntryEntity>> = terminalDao.observeAll()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val commandHistory: StateFlow<List<CommandHistoryEntity>> = historyDao.observeRecent()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val models: StateFlow<List<NimModelEntity>> = modelDao.observeAll()
        .stateIn(scope, SharingStarted.Eagerly, NvidiaModelCatalog.curatedModels)

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _mode = MutableStateFlow(TerminalMode.CLAUDE_CODE)
    val mode: StateFlow<TerminalMode> = _mode.asStateFlow()

    private val _cwdDisplay = MutableStateFlow("~/workspace")
    val cwdDisplay: StateFlow<String> = _cwdDisplay.asStateFlow()

    private val _availableSkills = MutableStateFlow<List<String>>(emptyList())
    val availableSkills: StateFlow<List<String>> = _availableSkills.asStateFlow()

    private val _workspaces = MutableStateFlow<List<WorkspaceInfo>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceInfo>> = _workspaces.asStateFlow()

    private val _activeWorkspace = MutableStateFlow("default")
    val activeWorkspace: StateFlow<String> = _activeWorkspace.asStateFlow()

    private val _isServiceActive = MutableStateFlow(false)
    val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

    private val _pendingPrompt = MutableStateFlow<PendingPromptRequest?>(null)
    val pendingPrompt: StateFlow<PendingPromptRequest?> = _pendingPrompt.asStateFlow()

    private val _isSyncingModels = MutableStateFlow(false)
    val isSyncingModels: StateFlow<Boolean> = _isSyncingModels.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private var activeExecutionJob: Job? = null
    private var stdinDeferred: CompletableDeferred<String>? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private val streamRowIdMap = mutableMapOf<String, Long>()

    private val shellEngine = TerminalShellEngine(
        workspace = workspaceManager,
        nimClient = nimClient,
        settingsProvider = { settings.value },
        onSetApiKey = { settingsDataStore.setApiKey(it) },
        onSetBaseUrl = { settingsDataStore.setBaseUrl(it) },
        onSetModel = { settingsDataStore.setSelectedModel(it) },
        onToggleAutoApprove = {
            val next = !settings.value.autoApproveTools
            settingsDataStore.setAutoApproveTools(next)
            next
        },
        onClearTerminal = {
            terminalDao.clearAll()
            appendBanner()
        },
        onStdout = { text -> appendEntry("STDOUT", text) },
        onStderr = { text -> appendEntry("STDERR", text) },
        onSystem = { text -> appendEntry("SYSTEM", text) },
        onAgentEvent = { event -> handleAgentEvent(event) },
        onRequestInput = { prompt ->
            appendEntry("SYSTEM", prompt.ifBlank { "[stdin waiting...]" })
            val def = CompletableDeferred<String>()
            stdinDeferred = def
            _pendingPrompt.value = PendingPromptRequest(
                isApproval = false,
                title = "Python input()",
                detail = prompt.ifBlank { "Enter input value" }
            )
            try {
                def.await()
            } finally {
                _pendingPrompt.value = null
                stdinDeferred = null
            }
        },
        onRequestApproval = { toolName, summary ->
            val def = CompletableDeferred<Boolean>()
            approvalDeferred = def
            _pendingPrompt.value = PendingPromptRequest(
                isApproval = true,
                title = "Approve $toolName?",
                detail = summary
            )
            try {
                def.await()
            } finally {
                _pendingPrompt.value = null
                approvalDeferred = null
            }
        },
        onModeChanged = { newMode -> _mode.value = newMode },
        onCwdChanged = { newCwd -> _cwdDisplay.value = newCwd },
        cachedModelsProvider = { models.value },
        onModelsSyncedFromV1 = { remoteList ->
            val newlyDiscovered = mergeLiveModelsIntoDb(remoteList)
            _syncMessage.value = "Synced ${remoteList.size} models from /v1/models ($newlyDiscovered newly discovered)."
        },
        onWorkspaceChanged = { wsName ->
            settingsDataStore.setActiveWorkspace(wsName)
            refreshWorkspaceMetadata()
        }
    )

    init {
        scope.launch {
            val initialSettings = settingsDataStore.settingsFlow.first()
            if (initialSettings.activeWorkspace != "default") {
                workspaceManager.switchWorkspace(initialSettings.activeWorkspace)
                shellEngine.resetCwdToWorkspaceRoot()
            }
            refreshWorkspaceMetadata()
            if (modelDao.count() == 0) {
                modelDao.upsertAll(NvidiaModelCatalog.curatedModels)
            }
            if (terminalDao.count() == 0) {
                appendBanner()
            }
        }
    }

    fun refreshWorkspaceMetadata() {
        _activeWorkspace.value = workspaceManager.activeWorkspaceName
        _workspaces.value = workspaceManager.listWorkspaces()
        _availableSkills.value = workspaceManager.listSkills()
        _cwdDisplay.value = workspaceManager.displayCwd(shellEngine.currentDir)
    }

    fun createWorkspace(name: String) {
        scope.launch {
            val created = workspaceManager.createWorkspace(name, withStarterMain = true)
            shellEngine.resetCwdToWorkspaceRoot()
            settingsDataStore.setActiveWorkspace(created)
            refreshWorkspaceMetadata()
        }
    }

    fun switchWorkspace(name: String) {
        scope.launch {
            if (workspaceManager.switchWorkspace(name)) {
                shellEngine.resetCwdToWorkspaceRoot()
                settingsDataStore.setActiveWorkspace(workspaceManager.activeWorkspaceName)
                refreshWorkspaceMetadata()
            }
        }
    }

    fun deleteWorkspace(name: String) {
        scope.launch {
            if (workspaceManager.deleteWorkspace(name)) {
                shellEngine.resetCwdToWorkspaceRoot()
                settingsDataStore.setActiveWorkspace(workspaceManager.activeWorkspaceName)
                refreshWorkspaceMetadata()
            }
        }
    }

    private suspend fun appendBanner() {
        val s = settings.value
        val skillsCount = workspaceManager.listSkills().size
        appendEntry(
            kind = "BANNER",
            title = "Claude Code CLI • NimTerm",
            text = "${s.selectedModelId} • ${_cwdDisplay.value}\n$skillsCount skills ready (.claude/skills) • Type `/` for commands or `!cmd` for shell/python",
            meta = s.apiKey.isNotBlank().toString()
        )
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceActive.value = running
    }

    fun onServiceStoppedExternally() {
        _isServiceActive.value = false
        scope.launch {
            settingsDataStore.setKeepAliveInBackground(false)
        }
    }

    fun startBackgroundService(status: String = "Terminal & Python session active") {
        runCatching {
            TerminalSessionService.start(appContext, status)
        }
    }

    fun stopBackgroundService() {
        runCatching {
            TerminalSessionService.stop(appContext)
        }
        _isServiceActive.value = false
    }

    fun toggleBackgroundKeepAlive(enabled: Boolean) {
        scope.launch {
            settingsDataStore.setKeepAliveInBackground(enabled)
            if (enabled) startBackgroundService("Session kept alive in background")
            else stopBackgroundService()
        }
    }

    fun switchMode(newMode: TerminalMode) {
        shellEngine.switchMode(newMode)
        if (newMode == TerminalMode.CLAUDE_CODE) {
            scope.launch {
                appendEntry(
                    kind = "BANNER",
                    title = "Claude Code CLI",
                    text = "Model: ${settings.value.selectedModelId} • Workspace: ${_cwdDisplay.value}"
                )
            }
        } else if (newMode == TerminalMode.PYTHON_REPL) {
            scope.launch {
                appendEntry("SYSTEM", "Python 3.12.0 Interactive REPL — type `exit()` to return.")
            }
        }
    }

    fun submitInput(rawInput: String) {
        // Check if Python input() is waiting
        stdinDeferred?.let { def ->
            scope.launch { appendEntry("COMMAND", rawInput) }
            def.complete(rawInput)
            return
        }

        if (_isBusy.value) {
            scope.launch { appendEntry("STDERR", "A task is currently running. Tap Ctrl+C to interrupt.") }
            return
        }

        val prefix = when (_mode.value) {
            TerminalMode.SHELL -> "${_cwdDisplay.value} $ "
            TerminalMode.PYTHON_REPL -> ">>> "
            TerminalMode.CLAUDE_CODE -> "claude > "
        }

        activeExecutionJob = scope.launch {
            _isBusy.value = true
            if (settings.value.keepAliveInBackground) {
                startBackgroundService("Running: ${rawInput.take(40)}")
            }
            try {
                appendEntry("COMMAND", "$prefix$rawInput")
                if (rawInput.isNotBlank()) {
                    historyDao.insert(CommandHistoryEntity(command = rawInput))
                }
                shellEngine.handleInput(rawInput)
                refreshWorkspaceMetadata()
            } catch (_: kotlinx.coroutines.CancellationException) {
                appendEntry("STDERR", "^C Interrupted")
            } finally {
                _isBusy.value = false
                if (settings.value.keepAliveInBackground) {
                    TerminalSessionService.updateStatus(appContext, "Session idle • Ready in background")
                }
            }
        }
    }

    fun runPythonFile(relativePath: String) {
        if (_isBusy.value) return
        activeExecutionJob = scope.launch {
            _isBusy.value = true
            if (settings.value.keepAliveInBackground) {
                startBackgroundService("Running Python: $relativePath")
            }
            try {
                appendEntry("COMMAND", "${_cwdDisplay.value} $ python3 $relativePath")
                shellEngine.runPythonFile(relativePath)
            } catch (_: kotlinx.coroutines.CancellationException) {
                appendEntry("STDERR", "^C Interrupted")
            } finally {
                _isBusy.value = false
                if (settings.value.keepAliveInBackground) {
                    TerminalSessionService.updateStatus(appContext, "Session idle • Ready in background")
                }
            }
        }
    }

    fun inspectFileWithClaude(relativePath: String) {
        if (_isBusy.value) return
        activeExecutionJob = scope.launch {
            _isBusy.value = true
            if (settings.value.keepAliveInBackground) {
                startBackgroundService("Claude analyzing $relativePath")
            }
            try {
                shellEngine.startClaudeWithFile(relativePath)
            } catch (_: kotlinx.coroutines.CancellationException) {
                appendEntry("STDERR", "^C Interrupted")
            } finally {
                _isBusy.value = false
                if (settings.value.keepAliveInBackground) {
                    TerminalSessionService.updateStatus(appContext, "Session idle • Ready in background")
                }
            }
        }
    }

    fun interruptCurrentExecution() {
        stdinDeferred?.cancel()
        approvalDeferred?.complete(false)
        activeExecutionJob?.cancel()
        _isBusy.value = false
    }

    fun respondToApproval(approved: Boolean) {
        approvalDeferred?.complete(approved)
    }

    private suspend fun mergeLiveModelsIntoDb(remoteList: List<RemoteModelItem>): Int {
        val now = System.currentTimeMillis()
        val existingMap = modelDao.getAllOnce().associateBy { it.id }
        val curatedIds = NvidiaModelCatalog.curatedModels.map { it.id }.toSet()
        val mergedCurated = NvidiaModelCatalog.curatedModels.map { c ->
            val prev = existingMap[c.id]
            val inLive = remoteList.any { it.id == c.id }
            c.copy(
                isFavorite = prev?.isFavorite ?: false,
                isLiveApi = inLive,
                lastSyncedAt = if (inLive) now else (prev?.lastSyncedAt ?: 0L)
            )
        }
        val discovered = remoteList
            .filter { it.id !in curatedIds }
            .map { NvidiaModelCatalog.fromLiveApiId(it.id, existingMap, now) }

        modelDao.upsertAll(mergedCurated + discovered)
        settingsDataStore.setLastSyncedTimestamp(now)
        return discovered.size
    }

    fun syncModelsFromApi() {
        if (_isSyncingModels.value) return
        scope.launch {
            _isSyncingModels.value = true
            _syncMessage.value = "Fetching models from /v1/models (${settings.value.baseUrl})..."
            val res = nimClient.fetchLiveModels(settings.value.apiKey, settings.value.baseUrl)
            res.onSuccess { remoteList ->
                val discoveredCount = mergeLiveModelsIntoDb(remoteList)
                _syncMessage.value = "Synced ${remoteList.size} models from /v1/models ($discoveredCount newly discovered)."
            }.onFailure { err ->
                _syncMessage.value = "Sync failed: ${err.message}"
            }
            _isSyncingModels.value = false
        }
    }

    fun addCustomModel(modelId: String) {
        val clean = modelId.trim()
        if (clean.isBlank()) return
        scope.launch {
            val existing = modelDao.getAllOnce().associateBy { it.id }
            val entity = NvidiaModelCatalog.fromLiveApiId(clean, existing, System.currentTimeMillis())
                .copy(isFavorite = true)
            modelDao.upsertAll(listOf(entity))
            settingsDataStore.setSelectedModel(clean)
        }
    }

    fun toggleModelFavorite(model: NimModelEntity) {
        scope.launch {
            modelDao.setFavorite(model.id, !model.isFavorite)
        }
    }

    fun selectModel(modelId: String) {
        scope.launch {
            settingsDataStore.setSelectedModel(modelId)
        }
    }

    private suspend fun handleAgentEvent(event: AgentTerminalEvent) {
        when (event) {
            is AgentTerminalEvent.Banner -> appendEntry("BANNER", event.text, title = "Claude Code CLI")
            is AgentTerminalEvent.ThinkingStart -> {
                val id = terminalDao.insert(
                    TerminalEntryEntity(kind = "AGENT_THINKING", title = "Reasoning", text = "")
                )
                streamRowIdMap[event.id] = id
            }
            is AgentTerminalEvent.ThinkingUpdate -> {
                val rowId = streamRowIdMap[event.id]
                if (rowId != null) terminalDao.updateText(rowId, event.fullText)
            }
            is AgentTerminalEvent.AssistantStart -> {
                val id = terminalDao.insert(
                    TerminalEntryEntity(kind = "AGENT_TEXT", title = settings.value.selectedModelId, text = "")
                )
                streamRowIdMap[event.id] = id
            }
            is AgentTerminalEvent.AssistantUpdate -> {
                val rowId = streamRowIdMap[event.id]
                if (rowId != null) terminalDao.updateText(rowId, event.fullText)
            }
            is AgentTerminalEvent.ToolStart -> {
                appendEntry("TOOL_CALL", event.detail, title = event.name)
            }
            is AgentTerminalEvent.ToolOutput -> {
                appendEntry(
                    kind = if (event.isError) "STDERR" else "TOOL_RESULT",
                    text = event.output,
                    title = event.name
                )
            }
            is AgentTerminalEvent.FileDiff -> {
                val body = event.diff.diffLines.joinToString("\n") { it.text }
                appendEntry(
                    kind = "DIFF",
                    title = "${event.diff.relativePath} (+${event.diff.addedCount} / -${event.diff.removedCount})",
                    text = body
                )
            }
            is AgentTerminalEvent.SystemInfo -> appendEntry("SYSTEM", event.text)
            is AgentTerminalEvent.Error -> appendEntry("STDERR", event.text)
        }
    }

    private suspend fun appendEntry(kind: String, text: String, title: String = "", meta: String = "") {
        terminalDao.insert(
            TerminalEntryEntity(
                kind = kind,
                text = text,
                title = title,
                meta = meta
            )
        )
    }
}
