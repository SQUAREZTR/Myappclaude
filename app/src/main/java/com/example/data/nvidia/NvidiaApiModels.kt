package com.example.data.nvidia

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ModelsListResponse(
    val `object`: String? = null,
    val data: List<RemoteModelItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class RemoteModelItem(
    val id: String,
    val `object`: String? = null,
    val created: Long? = null,
    @Json(name = "owned_by") val ownedBy: String? = null
)

@JsonClass(generateAdapter = true)
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiChatMessage>,
    val temperature: Double = 0.2,
    @Json(name = "max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = true,
    val tools: List<ApiToolDefinition>? = null,
    @Json(name = "tool_choice") val toolChoice: String? = null
)

@JsonClass(generateAdapter = true)
data class ApiChatMessage(
    val role: String,
    val content: String? = null,
    @Json(name = "tool_calls") val toolCalls: List<ApiToolCall>? = null,
    @Json(name = "tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

@JsonClass(generateAdapter = true)
data class ApiToolDefinition(
    val type: String = "function",
    val function: ApiFunctionSchema
)

@JsonClass(generateAdapter = true)
data class ApiFunctionSchema(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

@JsonClass(generateAdapter = true)
data class ApiToolCall(
    val id: String,
    val type: String = "function",
    val function: ApiFunctionCall
)

@JsonClass(generateAdapter = true)
data class ApiFunctionCall(
    val name: String,
    val arguments: String
)

// Streaming & Non-streaming response DTOs
@JsonClass(generateAdapter = true)
data class ChatCompletionChunk(
    val id: String? = null,
    val choices: List<StreamChoice> = emptyList()
)

@JsonClass(generateAdapter = true)
data class StreamChoice(
    val index: Int = 0,
    val delta: StreamDelta? = null,
    val message: StreamDelta? = null,
    @Json(name = "finish_reason") val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class StreamDelta(
    val role: String? = null,
    val content: String? = null,
    @Json(name = "reasoning_content") val reasoningContent: String? = null,
    @Json(name = "tool_calls") val toolCalls: List<StreamToolCallDelta>? = null
)

@JsonClass(generateAdapter = true)
data class StreamToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: StreamFunctionDelta? = null
)

@JsonClass(generateAdapter = true)
data class StreamFunctionDelta(
    val name: String? = null,
    val arguments: String? = null
)
