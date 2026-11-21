package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.NimTermApplication
import com.example.data.local.CommandHistoryEntity
import com.example.data.local.NimModelEntity
import com.example.data.local.NimSettings
import com.example.data.local.TerminalEntryEntity
import com.example.engine.shell.TerminalMode
import com.example.engine.shell.WorkspaceFileItem
import com.example.engine.shell.WorkspaceInfo
import com.example.service.PendingPromptRequest
import com.example.service.TerminalRuntimeHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class AppTab {
    TERMINAL,
    WORKSPACE,
    MODELS,
    SETTINGS
}

data class EditorState(
    val relativePath: String,
    val content: String,
    val isDirty: Boolean = false
)

sealed class ApiTestState {
    data object Idle : ApiTestState()
    data object Testing : ApiTestState()
    data class Success(val modelCount: Int) : ApiTestState()
    data class Error(val message: String) : ApiTestState()
}

class NimTermViewModel(application: Application) : AndroidViewModel(application) {

    private val host: TerminalRuntimeHost = (application as NimTermApplication).runtimeHost

    val settings: StateFlow<NimSettings> = host.settings
    val terminalEntries: StateFlow<List<TerminalEntryEntity>> = host.terminalEntries
    val commandHistory: StateFlow<List<CommandHistoryEntity>> = host.commandHistory
    val models: StateFlow<List<NimModelEntity>> = host.models
    val isBusy: StateFlow<Boolean> = host.isBusy
    val mode: StateFlow<TerminalMode> = host.mode
    val cwdDisplay: StateFlow<String> = host.cwdDisplay
    val availableSkills: StateFlow<List<String>> = host.availableSkills
    val workspaces: StateFlow<List<WorkspaceInfo>> = host.workspaces
    val activeWorkspace: StateFlow<String> = host.activeWorkspace
    val isServiceActive: StateFlow<Boolean> = host.isServiceActive
    val pendingPrompt: StateFlow<PendingPromptRequest?> = host.pendingPrompt
    val isSyncingModels: StateFlow<Boolean> = host.isSyncingModels
    val syncMessage: StateFlow<String?> = host.syncMessage

    private var didAutoSyncModels = false

    private val _selectedTab = MutableStateFlow(AppTab.TERMINAL)
    val selectedTab: StateFlow<AppTab> = _selectedTab.asStateFlow()

    private val _workspaceFiles = MutableStateFlow<List<WorkspaceFileItem>>(emptyList())
    val workspaceFiles: StateFlow<List<WorkspaceFileItem>> = _workspaceFiles.asStateFlow()

    private val _editorState = MutableStateFlow<EditorState?>(null)
    val editorState: StateFlow<EditorState?> = _editorState.asStateFlow()

    private val _apiTestState = MutableStateFlow<ApiTestState>(ApiTestState.Idle)
    val apiTestState: StateFlow<ApiTestState> = _apiTestState.asStateFlow()

    init {
        refreshWorkspaceFiles()
        viewModelScope.launch {
            host.activeWorkspace.collectLatest {
                refreshWorkspaceFiles()
            }
        }
        viewModelScope.launch {
            host.workspaceFilesVersion.collectLatest {
                refreshWorkspaceFiles()
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
        if (tab == AppTab.WORKSPACE) {
            host.refreshWorkspaceMetadata()
            refreshWorkspaceFiles()
        } else if (tab == AppTab.MODELS && !didAutoSyncModels) {
            didAutoSyncModels = true
            syncModelsFromApi()
        }
    }

    fun submitTerminalInput(input: String) {
        host.submitInput(input)
    }

    fun interruptExecution() {
        host.interruptCurrentExecution()
    }

    fun respondToApproval(approved: Boolean) {
        host.respondToApproval(approved)
    }

    fun switchMode(newMode: TerminalMode) {
        host.switchMode(newMode)
    }

    fun toggleBackgroundService(enabled: Boolean) {
        host.toggleBackgroundKeepAlive(enabled)
    }

    fun refreshWorkspaceFiles() {
        _workspaceFiles.value = host.workspaceManager.listFiles(".", host.workspaceManager.rootDir, recursive = true)
    }

    fun createWorkspace(name: String) {
        _editorState.value = null
        host.createWorkspace(name)
    }

    fun switchWorkspace(name: String) {
        _editorState.value = null
        host.switchWorkspace(name)
    }

    fun deleteWorkspace(name: String) {
        _editorState.value = null
        host.deleteWorkspace(name)
    }

    fun openFileInEditor(relativePath: String) {
        val text = runCatching {
            host.workspaceManager.readFile(relativePath, host.workspaceManager.rootDir)
        }.getOrElse { "" }
        _editorState.value = EditorState(relativePath = relativePath, content = text, isDirty = false)
    }

    fun updateEditorContent(newContent: String) {
        _editorState.value = _editorState.value?.copy(content = newContent, isDirty = true)
    }

    fun saveEditorFile() {
        val ed = _editorState.value ?: return
        host.workspaceManager.writeFile(ed.relativePath, ed.content, host.workspaceManager.rootDir)
        _editorState.value = ed.copy(isDirty = false)
        refreshWorkspaceFiles()
        host.refreshWorkspaceMetadata()
    }

    fun closeEditor() {
        _editorState.value = null
    }

    fun createNewFile(relativePath: String) {
        val clean = relativePath.trim().trimStart('/')
        if (clean.isBlank()) return
        val initial = if (clean.endsWith(".py")) "# $clean\n\nprint(\"Hello from $clean\")\n" else ""
        host.workspaceManager.writeFile(clean, initial, host.workspaceManager.rootDir)
        refreshWorkspaceFiles()
        host.refreshWorkspaceMetadata()
        openFileInEditor(clean)
    }

    fun createNewFolder(relativePath: String) {
        val clean = relativePath.trim().trimStart('/')
        if (clean.isBlank()) return
        host.workspaceManager.mkdir(clean, host.workspaceManager.rootDir)
        refreshWorkspaceFiles()
        host.refreshWorkspaceMetadata()
    }

    fun deleteWorkspaceItem(relativePath: String) {
        host.workspaceManager.deletePath(relativePath, host.workspaceManager.rootDir, recursive = true)
        if (_editorState.value?.relativePath == relativePath) {
            _editorState.value = null
        }
        refreshWorkspaceFiles()
        host.refreshWorkspaceMetadata()
    }

    fun runPythonFromWorkspace(relativePath: String) {
        if (_editorState.value?.relativePath == relativePath && _editorState.value?.isDirty == true) {
            saveEditorFile()
        }
        _editorState.value = null
        _selectedTab.value = AppTab.TERMINAL
        host.runPythonFile(relativePath)
    }

    fun askClaudeAboutFile(relativePath: String) {
        if (_editorState.value?.relativePath == relativePath && _editorState.value?.isDirty == true) {
            saveEditorFile()
        }
        _editorState.value = null
        _selectedTab.value = AppTab.TERMINAL
        host.inspectFileWithClaude(relativePath)
    }

    fun syncModelsFromApi() {
        host.syncModelsFromApi()
    }

    fun selectModel(modelId: String) {
        host.selectModel(modelId)
    }

    fun toggleModelFavorite(model: NimModelEntity) {
        host.toggleModelFavorite(model)
    }

    fun addCustomModel(modelId: String) {
        host.addCustomModel(modelId)
    }

    fun saveApiConfig(apiKey: String, baseUrl: String) {
        viewModelScope.launch {
            host.settingsDataStore.setApiKey(apiKey)
            host.settingsDataStore.setBaseUrl(baseUrl)
            _apiTestState.value = ApiTestState.Idle
        }
    }

    fun testApiConnection(apiKey: String, baseUrl: String) {
        viewModelScope.launch {
            _apiTestState.value = ApiTestState.Testing
            host.settingsDataStore.setApiKey(apiKey)
            host.settingsDataStore.setBaseUrl(baseUrl)
            val result = host.nimClient.testConnection(apiKey, baseUrl)
            _apiTestState.value = result.fold(
                onSuccess = { count -> ApiTestState.Success(count) },
                onFailure = { err -> ApiTestState.Error(err.message ?: "Connection failed") }
            )
        }
    }

    fun setAutoApproveTools(enabled: Boolean) {
        viewModelScope.launch { host.settingsDataStore.setAutoApproveTools(enabled) }
    }

    fun setTemperature(temp: Float) {
        viewModelScope.launch { host.settingsDataStore.setTemperature(temp) }
    }

    fun setMaxTokens(tokens: Int) {
        viewModelScope.launch { host.settingsDataStore.setMaxTokens(tokens) }
    }

    fun setMaxAgentSteps(steps: Int) {
        viewModelScope.launch { host.settingsDataStore.setMaxAgentSteps(steps) }
    }

    fun setFontSizeSp(sizeSp: Int) {
        viewModelScope.launch { host.settingsDataStore.setTerminalFontSizeSp(sizeSp) }
    }

    fun clearTerminal() {
        host.submitInput("/clear")
    }
}
