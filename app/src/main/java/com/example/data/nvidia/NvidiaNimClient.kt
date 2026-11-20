package com.example.data.nvidia

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class NvidiaStreamEvent {
    data class ReasoningDelta(val text: String) : NvidiaStreamEvent()
    data class ContentDelta(val text: String) : NvidiaStreamEvent()
    data class Completed(
        val content: String,
        val reasoning: String,
        val toolCalls: List<ApiToolCall>
    ) : NvidiaStreamEvent()
}

class NvidiaNimClient {

    companion object {
        const val BASE_URL = "https://integrate.api.nvidia.com/v1"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val modelsAdapter = moshi.adapter(ModelsListResponse::class.java)
    private val requestAdapter = moshi.adapter(ChatCompletionRequest::class.java)
    private val chunkAdapter = moshi.adapter(ChatCompletionChunk::class.java)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun normalizeBaseUrl(baseUrl: String): String {
        val cleaned = baseUrl.trim().trimEnd('/').ifBlank { BASE_URL }
        return if (Regex("/v\\d+$").containsMatchIn(cleaned) || cleaned.endsWith("/v1beta")) {
            cleaned
        } else {
            "$cleaned/v1"
        }
    }

    suspend fun fetchLiveModels(
        apiKey: String,
        baseUrl: String = BASE_URL
    ): Result<List<RemoteModelItem>> = withContext(Dispatchers.IO) {
        try {
            val resolvedBase = normalizeBaseUrl(baseUrl)
            val reqBuilder = Request.Builder()
                .url("$resolvedBase/models")
                .get()
                .header("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
            }
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("API HTTP ${response.code}: ${extractErrorMessage(bodyStr)}")
                    )
                }
                val parsed = modelsAdapter.fromJson(bodyStr)
                    ?: return@withContext Result.failure(IOException("Empty response from /v1/models"))
                Result.success(parsed.data)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun testConnection(apiKey: String, baseUrl: String = BASE_URL): Result<Int> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return Result.failure(IllegalArgumentException("API key is empty"))
        }
        return fetchLiveModels(cleanKey, baseUrl).map { it.size }
    }

    suspend fun completeChat(
        apiKey: String,
        model: String,
        messages: List<ApiChatMessage>,
        tools: List<ApiToolDefinition>?,
        temperature: Double,
        maxTokens: Int,
        baseUrl: String = BASE_URL,
        onEvent: suspend (NvidiaStreamEvent) -> Unit
    ): Result<NvidiaStreamEvent.Completed> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("API key not set. Open Settings tab or run `/key <nvapi-...>`.")
            )
        }

        // First try streaming SSE request
        val streamResult = executeCompletionRequest(
            apiKey = cleanKey,
            baseUrl = normalizeBaseUrl(baseUrl),
            model = model,
            messages = messages,
            tools = tools,
            temperature = temperature,
            maxTokens = maxTokens,
            stream = true,
            onEvent = onEvent
        )

        // If a model rejects tools parameter (some distilled/older NIM models don't support OpenAI tools schema),
        // retry automatically without tools so prompt-based tool parsing still works!
        if (streamResult.isFailure && tools != null) {
            val msg = streamResult.exceptionOrNull()?.message.orEmpty().lowercase()
            if (msg.contains("tool") || msg.contains("400") || msg.contains("422") || msg.contains("unsupported")) {
                return@withContext executeCompletionRequest(
                    apiKey = cleanKey,
                    baseUrl = normalizeBaseUrl(baseUrl),
                    model = model,
                    messages = messages,
                    tools = null,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    stream = true,
                    onEvent = onEvent
                )
            }
        }

        streamResult
    }

    private suspend fun executeCompletionRequest(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ApiChatMessage>,
        tools: List<ApiToolDefinition>?,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean,
        onEvent: suspend (NvidiaStreamEvent) -> Unit
    ): Result<NvidiaStreamEvent.Completed> {
        val reqObj = ChatCompletionRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            stream = stream,
            tools = tools,
            toolChoice = if (tools != null) "auto" else null
        )
        val jsonBody = requestAdapter.toJson(reqObj)
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .header("Content-Type", "application/json")
            .build()

        return try {
            val call = httpClient.newCall(request)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string().orEmpty()
                    return Result.failure(
                        IOException("NVIDIA NIM HTTP ${response.code} ($model): ${extractErrorMessage(err)}")
                    )
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val body = response.body ?: return Result.failure(IOException("Empty response body"))

                if (stream && (contentType.contains("event-stream") || contentType.isBlank())) {
                    parseSseStream(body.source(), onEvent)
                } else {
                    val raw = body.string()
                    val parsed = chunkAdapter.fromJson(raw)
                        ?: return Result.failure(IOException("Malformed JSON response from NIM"))
                    val msg = parsed.choices.firstOrNull()?.message ?: parsed.choices.firstOrNull()?.delta
                    val content = msg?.content.orEmpty()
                    val reasoning = msg?.reasoningContent.orEmpty()
                    if (reasoning.isNotEmpty()) onEvent(NvidiaStreamEvent.ReasoningDelta(reasoning))
                    if (content.isNotEmpty()) onEvent(NvidiaStreamEvent.ContentDelta(content))
                    val toolCalls = msg?.toolCalls.orEmpty().mapIndexed { idx, tc ->
                        ApiToolCall(
                            id = tc.id ?: "call_$idx",
                            type = tc.type ?: "function",
                            function = ApiFunctionCall(
                                name = tc.function?.name.orEmpty(),
                                arguments = tc.function?.arguments.orEmpty()
                            )
                        )
                    }.filter { it.function.name.isNotBlank() }

                    val completed = NvidiaStreamEvent.Completed(content, reasoning, toolCalls)
                    onEvent(completed)
                    Result.success(completed)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun parseSseStream(
        source: BufferedSource,
        onEvent: suspend (NvidiaStreamEvent) -> Unit
    ): Result<NvidiaStreamEvent.Completed> {
        val contentSb = StringBuilder()
        val reasoningSb = StringBuilder()
        val toolCallBuilders = sortedMapOf<Int, MutableToolCallBuilder>()

        while (!source.exhausted()) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue
            val payload = trimmed.removePrefix("data:").trim()
            if (payload == "[DONE]") break

            val chunk = try {
                chunkAdapter.fromJson(payload)
            } catch (_: Exception) {
                null
            } ?: continue

            val choice = chunk.choices.firstOrNull() ?: continue
            val delta = choice.delta ?: choice.message ?: continue

            val rDelta = delta.reasoningContent
            if (!rDelta.isNullOrEmpty()) {
                reasoningSb.append(rDelta)
                onEvent(NvidiaStreamEvent.ReasoningDelta(rDelta))
            }

            val cDelta = delta.content
            if (!cDelta.isNullOrEmpty()) {
                contentSb.append(cDelta)
                onEvent(NvidiaStreamEvent.ContentDelta(cDelta))
            }

            delta.toolCalls?.forEach { tcDelta ->
                val builder = toolCallBuilders.getOrPut(tcDelta.index) { MutableToolCallBuilder() }
                if (!tcDelta.id.isNullOrBlank()) builder.id = tcDelta.id
                if (!tcDelta.type.isNullOrBlank()) builder.type = tcDelta.type
                tcDelta.function?.let { fn ->
                    if (!fn.name.isNullOrBlank()) builder.name.append(fn.name)
                    if (!fn.arguments.isNullOrEmpty()) builder.arguments.append(fn.arguments)
                }
            }
        }

        val toolCalls = toolCallBuilders.values
            .filter { it.name.isNotBlank() }
            .mapIndexed { idx, b ->
                ApiToolCall(
                    id = b.id.ifBlank { "call_${System.currentTimeMillis()}_$idx" },
                    type = b.type.ifBlank { "function" },
                    function = ApiFunctionCall(
                        name = b.name.toString().trim(),
                        arguments = b.arguments.toString().trim()
                    )
                )
            }

        val completed = NvidiaStreamEvent.Completed(
            content = contentSb.toString(),
            reasoning = reasoningSb.toString(),
            toolCalls = toolCalls
        )
        onEvent(completed)
        return Result.success(completed)
    }

    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return "Unknown server error"
        return Regex("\"message\"\\s*:\\s*\"([^\"]+)\"")
            .find(body)?.groupValues?.getOrNull(1)
            ?: Regex("\"detail\"\\s*:\\s*\"([^\"]+)\"")
                .find(body)?.groupValues?.getOrNull(1)
            ?: body.take(240)
    }

    private class MutableToolCallBuilder {
        var id: String = ""
        var type: String = "function"
        val name = StringBuilder()
        val arguments = StringBuilder()
    }
}
