package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.local.NimSettings
import com.example.ui.ApiTestState
import com.example.ui.theme.ClaudeAmber
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NvidiaLime
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.TerminalCyan
import com.example.ui.theme.TerminalError
import com.example.ui.theme.TerminalSuccess
import com.example.ui.theme.TerminalTextMuted
import com.example.ui.theme.TerminalTextPrimary

@Composable
fun SettingsScreen(
    settings: NimSettings,
    isServiceActive: Boolean,
    apiTestState: ApiTestState,
    onSaveApiConfig: (String, String) -> Unit,
    onTestConnection: (String, String) -> Unit,
    onToggleBackgroundService: (Boolean) -> Unit,
    onToggleAutoApprove: (Boolean) -> Unit,
    onChangeTemperature: (Float) -> Unit,
    onChangeMaxTokens: (Int) -> Unit,
    onChangeMaxAgentSteps: (Int) -> Unit,
    onChangeFontSize: (Int) -> Unit,
    onClearTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var apiKeyInput by remember { mutableStateOf(settings.apiKey) }
    var baseUrlInput by remember { mutableStateOf(settings.baseUrl) }
    var revealKey by remember { mutableStateOf(false) }

    LaunchedEffect(settings.apiKey) {
        if (apiKeyInput.isBlank() && settings.apiKey.isNotBlank()) apiKeyInput = settings.apiKey
    }
    LaunchedEffect(settings.baseUrl) {
        if (baseUrlInput.isBlank() && settings.baseUrl.isNotBlank()) baseUrlInput = settings.baseUrl
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onToggleBackgroundService(true)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        Surface(color = ObsidianSurface, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text("Settings & API", style = MaterialTheme.typography.titleLarge, color = TerminalTextPrimary)
                Text(
                    "Configure NVIDIA NIM / LiteLLM key and background execution",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalTextMuted
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Card 1: NVIDIA NIM / LiteLLM API Configuration
            SettingsCard(title = "NVIDIA NIM & LiteLLM API") {
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("API Key (nvapi-... or LiteLLM key)") },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = ClaudeAmber) },
                    trailingIcon = {
                        IconButton(onClick = { revealKey = !revealKey }) {
                            Icon(
                                imageVector = if (revealKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle key visibility"
                            )
                        }
                    },
                    visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
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
                        .testTag("api_key_input")
                )

                OutlinedTextField(
                    value = baseUrlInput,
                    onValueChange = { baseUrlInput = it },
                    label = { Text("OpenAI-Compatible Base URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TerminalCyan,
                        unfocusedBorderColor = ObsidianOutline,
                        focusedContainerColor = ObsidianVoid,
                        unfocusedContainerColor = ObsidianVoid
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("base_url_input")
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = { baseUrlInput = "https://integrate.api.nvidia.com/v1" },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("NVIDIA NIM", color = PhosphorGreen)
                    }
                    TextButton(
                        onClick = { baseUrlInput = "http://127.0.0.1:4000/v1" },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("LiteLLM Local", color = TerminalCyan)
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { onSaveApiConfig(apiKeyInput, baseUrlInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = PhosphorGreen, contentColor = ObsidianVoid),
                        modifier = Modifier.weight(1f).testTag("save_api_config_button")
                    ) {
                        Text("Save Key", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { onTestConnection(apiKeyInput, baseUrlInput) },
                        enabled = apiTestState !is ApiTestState.Testing,
                        modifier = Modifier.weight(1f).testTag("test_connection_button")
                    ) {
                        if (apiTestState is ApiTestState.Testing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Test API")
                    }
                }

                when (apiTestState) {
                    is ApiTestState.Success -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = TerminalSuccess, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Connected! ${apiTestState.modelCount} live models available.",
                                style = MaterialTheme.typography.labelMedium,
                                color = TerminalSuccess
                            )
                        }
                    }
                    is ApiTestState.Error -> {
                        Text(
                            apiTestState.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = TerminalError
                        )
                    }
                    else -> Unit
                }
            }

            // Card 2: Background Keep-Alive Foreground Service
            SettingsCard(title = "Background Persistence") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Keep Session Alive in Background",
                            style = MaterialTheme.typography.titleSmall,
                            color = TerminalTextPrimary
                        )
                        Text(
                            "Runs an Android Foreground Service + WakeLock so Python & Claude Code never stop when minimized.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TerminalTextMuted
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = settings.keepAliveInBackground,
                        onCheckedChange = { enable ->
                            if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasPerm) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    onToggleBackgroundService(true)
                                }
                            } else {
                                onToggleBackgroundService(enable)
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = ObsidianVoid, checkedTrackColor = PhosphorGreen),
                        modifier = Modifier.testTag("keep_alive_switch")
                    )
                }
                Text(
                    text = "Service Status: ${if (isServiceActive) "ACTIVE (Protected)" else "STOPPED"}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = if (isServiceActive) PhosphorGreen else TerminalTextMuted
                )
            }

            // Card 3: Claude Code Agent & Terminal Behavior
            SettingsCard(title = "Claude Code & Terminal") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Auto-Approve Agent Tools",
                            style = MaterialTheme.typography.titleSmall,
                            color = TerminalTextPrimary
                        )
                        Text(
                            "When off, Claude Code asks Allow/Deny before writing files or running Python/Shell.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TerminalTextMuted
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = settings.autoApproveTools,
                        onCheckedChange = onToggleAutoApprove,
                        colors = SwitchDefaults.colors(checkedThumbColor = ObsidianVoid, checkedTrackColor = ClaudeAmber),
                        modifier = Modifier.testTag("auto_approve_switch")
                    )
                }

                Column {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Temperature", style = MaterialTheme.typography.labelLarge, color = TerminalTextPrimary)
                        Text(
                            String.format(java.util.Locale.US, "%.2f", settings.temperature),
                            style = MaterialTheme.typography.labelMedium,
                            color = ClaudeAmber
                        )
                    }
                    Slider(
                        value = settings.temperature,
                        onValueChange = onChangeTemperature,
                        valueRange = 0f..1.2f,
                        colors = SliderDefaults.colors(thumbColor = ClaudeAmber, activeTrackColor = ClaudeAmber)
                    )
                }

                Column {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Terminal Font Size", style = MaterialTheme.typography.labelLarge, color = TerminalTextPrimary)
                        Text("${settings.terminalFontSizeSp}sp", style = MaterialTheme.typography.labelMedium, color = TerminalCyan)
                    }
                    Slider(
                        value = settings.terminalFontSizeSp.toFloat(),
                        onValueChange = { onChangeFontSize(it.toInt()) },
                        valueRange = 10f..18f,
                        steps = 7,
                        colors = SliderDefaults.colors(thumbColor = TerminalCyan, activeTrackColor = TerminalCyan)
                    )
                }

                Column {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Max Agent Steps / Turn", style = MaterialTheme.typography.labelLarge, color = TerminalTextPrimary)
                        Text("${settings.maxAgentSteps}", style = MaterialTheme.typography.labelMedium, color = PhosphorGreen)
                    }
                    Slider(
                        value = settings.maxAgentSteps.toFloat(),
                        onValueChange = { onChangeMaxAgentSteps(it.toInt()) },
                        valueRange = 4f..24f,
                        steps = 19,
                        colors = SliderDefaults.colors(thumbColor = PhosphorGreen, activeTrackColor = PhosphorGreen)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(2048, 4096, 8192).forEach { tok ->
                        val active = settings.maxTokens == tok
                        OutlinedButton(
                            onClick = { onChangeMaxTokens(tok) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (active) ObsidianCard else ObsidianVoid,
                                contentColor = if (active) NvidiaLime else TerminalTextMuted
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("${tok}t")
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onClearTerminal,
                    modifier = Modifier.fillMaxWidth().testTag("clear_terminal_button")
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = TerminalError, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Clear Terminal Scrollback & Agent Context", color = TerminalError)
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ObsidianElevated)
            .border(1.dp, ObsidianOutline, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TerminalTextPrimary
        )
        content()
    }
}
