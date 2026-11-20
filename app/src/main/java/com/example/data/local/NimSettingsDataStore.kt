package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.nimDataStore: DataStore<Preferences> by preferencesDataStore(name = "nimterm_settings")

data class NimSettings(
    val apiKey: String = "",
    val baseUrl: String = "https://integrate.api.nvidia.com/v1",
    val selectedModelId: String = "meta/llama-3.3-70b-instruct",
    val keepAliveInBackground: Boolean = true,
    val autoApproveTools: Boolean = true,
    val temperature: Float = 0.2f,
    val maxTokens: Int = 4096,
    val terminalFontSizeSp: Int = 13,
    val maxAgentSteps: Int = 12,
    val lastSyncedTimestamp: Long = 0L,
    val activeWorkspace: String = "default"
)

class NimSettingsDataStore(private val context: Context) {

    private object Keys {
        val API_KEY = stringPreferencesKey("nvidia_api_key")
        val BASE_URL = stringPreferencesKey("nvidia_base_url")
        val SELECTED_MODEL = stringPreferencesKey("selected_model_id")
        val KEEP_ALIVE = booleanPreferencesKey("keep_alive_background")
        val AUTO_APPROVE_TOOLS = booleanPreferencesKey("auto_approve_tools")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val FONT_SIZE_SP = intPreferencesKey("terminal_font_size_sp")
        val MAX_AGENT_STEPS = intPreferencesKey("max_agent_steps")
        val LAST_SYNCED = stringPreferencesKey("last_synced_ts")
        val ACTIVE_WORKSPACE = stringPreferencesKey("active_workspace")
    }

    val settingsFlow: Flow<NimSettings> = context.nimDataStore.data.map { prefs ->
        NimSettings(
            apiKey = prefs[Keys.API_KEY] ?: "",
            baseUrl = prefs[Keys.BASE_URL]?.ifBlank { "https://integrate.api.nvidia.com/v1" }
                ?: "https://integrate.api.nvidia.com/v1",
            selectedModelId = prefs[Keys.SELECTED_MODEL] ?: "meta/llama-3.3-70b-instruct",
            keepAliveInBackground = prefs[Keys.KEEP_ALIVE] ?: true,
            autoApproveTools = prefs[Keys.AUTO_APPROVE_TOOLS] ?: true,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.2f,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: 4096,
            terminalFontSizeSp = prefs[Keys.FONT_SIZE_SP] ?: 13,
            maxAgentSteps = prefs[Keys.MAX_AGENT_STEPS] ?: 12,
            lastSyncedTimestamp = prefs[Keys.LAST_SYNCED]?.toLongOrNull() ?: 0L,
            activeWorkspace = prefs[Keys.ACTIVE_WORKSPACE]?.ifBlank { "default" } ?: "default"
        )
    }

    suspend fun setApiKey(apiKey: String) {
        context.nimDataStore.edit { it[Keys.API_KEY] = apiKey.trim() }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        val cleaned = baseUrl.trim().trimEnd('/').ifBlank { "https://integrate.api.nvidia.com/v1" }
        context.nimDataStore.edit { it[Keys.BASE_URL] = cleaned }
    }

    suspend fun setSelectedModel(modelId: String) {
        context.nimDataStore.edit { it[Keys.SELECTED_MODEL] = modelId.trim() }
    }

    suspend fun setKeepAliveInBackground(enabled: Boolean) {
        context.nimDataStore.edit { it[Keys.KEEP_ALIVE] = enabled }
    }

    suspend fun setAutoApproveTools(enabled: Boolean) {
        context.nimDataStore.edit { it[Keys.AUTO_APPROVE_TOOLS] = enabled }
    }

    suspend fun setTemperature(temp: Float) {
        context.nimDataStore.edit { it[Keys.TEMPERATURE] = temp.coerceIn(0f, 1.5f) }
    }

    suspend fun setMaxTokens(tokens: Int) {
        context.nimDataStore.edit { it[Keys.MAX_TOKENS] = tokens.coerceIn(256, 32768) }
    }

    suspend fun setTerminalFontSizeSp(sizeSp: Int) {
        context.nimDataStore.edit { it[Keys.FONT_SIZE_SP] = sizeSp.coerceIn(10, 20) }
    }

    suspend fun setMaxAgentSteps(steps: Int) {
        context.nimDataStore.edit { it[Keys.MAX_AGENT_STEPS] = steps.coerceIn(3, 30) }
    }

    suspend fun setLastSyncedTimestamp(ts: Long) {
        context.nimDataStore.edit { it[Keys.LAST_SYNCED] = ts.toString() }
    }

    suspend fun setActiveWorkspace(name: String) {
        context.nimDataStore.edit { it[Keys.ACTIVE_WORKSPACE] = name.trim().ifBlank { "default" } }
    }
}
