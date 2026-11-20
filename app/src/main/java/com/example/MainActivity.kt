package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AppTab
import com.example.ui.NimTermViewModel
import com.example.ui.screens.ModelsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TerminalScreen
import com.example.ui.screens.WorkspaceScreen
import com.example.ui.theme.ClaudeAmber
import com.example.ui.theme.NimTermTheme
import com.example.ui.theme.NvidiaLimeContainer
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.TerminalTextMuted

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NimTermTheme {
                NimTermAppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val host = (application as? NimTermApplication)?.runtimeHost ?: return
        if (host.settings.value.keepAliveInBackground && !host.isServiceActive.value) {
            host.startBackgroundService("NimTerm session ready")
        }
    }
}

private data class NavDestination(
    val tab: AppTab,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@Composable
fun NimTermAppRoot(viewModel: NimTermViewModel = viewModel()) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val entries by viewModel.terminalEntries.collectAsStateWithLifecycle()
    val history by viewModel.commandHistory.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val cwdDisplay by viewModel.cwdDisplay.collectAsStateWithLifecycle()
    val availableSkills by viewModel.availableSkills.collectAsStateWithLifecycle()
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val activeWorkspace by viewModel.activeWorkspace.collectAsStateWithLifecycle()
    val isServiceActive by viewModel.isServiceActive.collectAsStateWithLifecycle()
    val pendingPrompt by viewModel.pendingPrompt.collectAsStateWithLifecycle()
    val isSyncingModels by viewModel.isSyncingModels.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    val workspaceFiles by viewModel.workspaceFiles.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    val apiTestState by viewModel.apiTestState.collectAsStateWithLifecycle()

    val destinations = listOf(
        NavDestination(AppTab.TERMINAL, R.string.nav_terminal, Icons.Filled.Terminal, Icons.Outlined.Terminal, "tab_terminal"),
        NavDestination(AppTab.WORKSPACE, R.string.nav_files, Icons.Filled.Folder, Icons.Outlined.Folder, "tab_workspace"),
        NavDestination(AppTab.MODELS, R.string.nav_models, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome, "tab_models"),
        NavDestination(AppTab.SETTINGS, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings, "tab_settings")
    )

    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        if (useRail) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                NavigationRail(
                    containerColor = ObsidianSurface
                ) {
                    destinations.forEach { dest ->
                        val active = selectedTab == dest.tab
                        NavigationRailItem(
                            selected = active,
                            onClick = { viewModel.selectTab(dest.tab) },
                            icon = {
                                Icon(
                                    imageVector = if (active) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = stringResource(dest.labelRes)
                                )
                            },
                            label = { Text(stringResource(dest.labelRes)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = PhosphorGreen,
                                selectedTextColor = PhosphorGreen,
                                indicatorColor = NvidiaLimeContainer,
                                unselectedIconColor = TerminalTextMuted,
                                unselectedTextColor = TerminalTextMuted
                            ),
                            modifier = Modifier.testTag(dest.testTag)
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    TabContent(
                        selectedTab = selectedTab,
                        viewModel = viewModel,
                        settings = settings,
                        entries = entries,
                        history = history,
                        models = models,
                        isBusy = isBusy,
                        mode = mode,
                        cwdDisplay = cwdDisplay,
                        availableSkills = availableSkills,
                        workspaces = workspaces,
                        activeWorkspace = activeWorkspace,
                        isServiceActive = isServiceActive,
                        pendingPrompt = pendingPrompt,
                        isSyncingModels = isSyncingModels,
                        syncMessage = syncMessage,
                        workspaceFiles = workspaceFiles,
                        editorState = editorState,
                        apiTestState = apiTestState
                    )
                }
            }
        } else {
            Scaffold(
                containerColor = ObsidianVoid,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (!isImeVisible) {
                        NavigationBar(
                            containerColor = ObsidianSurface,
                            tonalElevation = 4.dp
                        ) {
                            destinations.forEach { dest ->
                                val active = selectedTab == dest.tab
                                NavigationBarItem(
                                    selected = active,
                                    onClick = { viewModel.selectTab(dest.tab) },
                                    icon = {
                                        Icon(
                                            imageVector = if (active) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(dest.labelRes),
                                            tint = if (active && dest.tab == AppTab.MODELS) ClaudeAmber else if (active) PhosphorGreen else TerminalTextMuted
                                        )
                                    },
                                    label = { Text(stringResource(dest.labelRes)) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = PhosphorGreen,
                                        selectedTextColor = PhosphorGreen,
                                        indicatorColor = NvidiaLimeContainer,
                                        unselectedIconColor = TerminalTextMuted,
                                        unselectedTextColor = TerminalTextMuted
                                    ),
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(innerPadding)
                        .imePadding()
                ) {
                    TabContent(
                        selectedTab = selectedTab,
                        viewModel = viewModel,
                        settings = settings,
                        entries = entries,
                        history = history,
                        models = models,
                        isBusy = isBusy,
                        mode = mode,
                        cwdDisplay = cwdDisplay,
                        availableSkills = availableSkills,
                        workspaces = workspaces,
                        activeWorkspace = activeWorkspace,
                        isServiceActive = isServiceActive,
                        pendingPrompt = pendingPrompt,
                        isSyncingModels = isSyncingModels,
                        syncMessage = syncMessage,
                        workspaceFiles = workspaceFiles,
                        editorState = editorState,
                        apiTestState = apiTestState
                    )
                }
            }
        }
    }
}

@Composable
private fun TabContent(
    selectedTab: AppTab,
    viewModel: NimTermViewModel,
    settings: com.example.data.local.NimSettings,
    entries: List<com.example.data.local.TerminalEntryEntity>,
    history: List<com.example.data.local.CommandHistoryEntity>,
    models: List<com.example.data.local.NimModelEntity>,
    isBusy: Boolean,
    mode: com.example.engine.shell.TerminalMode,
    cwdDisplay: String,
    availableSkills: List<String>,
    workspaces: List<com.example.engine.shell.WorkspaceInfo>,
    activeWorkspace: String,
    isServiceActive: Boolean,
    pendingPrompt: com.example.service.PendingPromptRequest?,
    isSyncingModels: Boolean,
    syncMessage: String?,
    workspaceFiles: List<com.example.engine.shell.WorkspaceFileItem>,
    editorState: com.example.ui.EditorState?,
    apiTestState: com.example.ui.ApiTestState
) {
    when (selectedTab) {
        AppTab.TERMINAL -> TerminalScreen(
            entries = entries,
            history = history,
            settings = settings,
            mode = mode,
            cwdDisplay = cwdDisplay,
            availableSkills = availableSkills,
            isBusy = isBusy,
            isServiceActive = isServiceActive,
            pendingPrompt = pendingPrompt,
            onSubmitInput = viewModel::submitTerminalInput,
            onInterrupt = viewModel::interruptExecution,
            onRespondApproval = viewModel::respondToApproval,
            onSwitchMode = viewModel::switchMode,
            onToggleService = viewModel::toggleBackgroundService,
            onOpenModels = { viewModel.selectTab(AppTab.MODELS) },
            onOpenSettings = { viewModel.selectTab(AppTab.SETTINGS) }
        )
        AppTab.WORKSPACE -> WorkspaceScreen(
            files = workspaceFiles,
            workspaces = workspaces,
            activeWorkspace = activeWorkspace,
            editorState = editorState,
            onRefresh = viewModel::refreshWorkspaceFiles,
            onCreateWorkspace = viewModel::createWorkspace,
            onSwitchWorkspace = viewModel::switchWorkspace,
            onDeleteWorkspace = viewModel::deleteWorkspace,
            onOpenFile = viewModel::openFileInEditor,
            onUpdateEditor = viewModel::updateEditorContent,
            onSaveEditor = viewModel::saveEditorFile,
            onCloseEditor = viewModel::closeEditor,
            onCreateFile = viewModel::createNewFile,
            onCreateFolder = viewModel::createNewFolder,
            onDeleteItem = viewModel::deleteWorkspaceItem,
            onRunPython = viewModel::runPythonFromWorkspace,
            onAskClaude = viewModel::askClaudeAboutFile
        )
        AppTab.MODELS -> ModelsScreen(
            models = models,
            selectedModelId = settings.selectedModelId,
            isSyncing = isSyncingModels,
            syncMessage = syncMessage,
            onSelectModel = {
                viewModel.selectModel(it)
                viewModel.selectTab(AppTab.TERMINAL)
            },
            onToggleFavorite = viewModel::toggleModelFavorite,
            onSyncLiveModels = viewModel::syncModelsFromApi,
            onAddCustomModel = viewModel::addCustomModel
        )
        AppTab.SETTINGS -> SettingsScreen(
            settings = settings,
            isServiceActive = isServiceActive,
            apiTestState = apiTestState,
            onSaveApiConfig = viewModel::saveApiConfig,
            onTestConnection = viewModel::testApiConnection,
            onToggleBackgroundService = viewModel::toggleBackgroundService,
            onToggleAutoApprove = viewModel::setAutoApproveTools,
            onChangeTemperature = viewModel::setTemperature,
            onChangeMaxTokens = viewModel::setMaxTokens,
            onChangeMaxAgentSteps = viewModel::setMaxAgentSteps,
            onChangeFontSize = viewModel::setFontSizeSp,
            onClearTerminal = viewModel::clearTerminal
        )
    }
}
