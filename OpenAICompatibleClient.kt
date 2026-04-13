package com.lhzkml.jasmine.core.prompt.executor

import com.lhzkml.jasmine.core.prompt.llm.ChatClient
import com.lhzkml.jasmine.core.prompt.llm.ThinkingChatClient
import com.lhzkml.jasmine.core.prompt.llm.ChatClientException
import com.lhzkml.jasmine.core.prompt.llm.ErrorType
import com.lhzkml.jasmine.core.prompt.llm.RetryConfig
import com.lhzkml.jasmine.core.prompt.llm.StreamResult
import com.lhzkml.jasmine.core.prompt.llm.executeWithRetry
import com.lhzkml.jasmine.core.prompt.model.ChatMessage
import com.lhzkml.jasmine.core.prompt.model.ChatRequest
import com.lhzkml.jasmine.core.prompt.model.ChatResponse
import com.lhzkml.jasmine.core.prompt.model.ChatResult
import com.lhzkml.jasmine.core.prompt.model.ChatStreamResponse
import com.lhzkml.jasmine.core.prompt.model.ModelInfo
import com.lhzkml.jasmine.core.prompt.model.OpenAIFunctionCallDef
import com.lhzkml.jasmine.core.prompt.model.OpenAIFunctionDef
import com.lhzkml.jasmine.core.prompt.model.OpenAIRequestMessage
import com.lhzkml.jasmine.core.prompt.model.OpenAIToolCallDef
import com.lhzkml.jasmine.core.prompt.model.OpenAIToolDef
import com.lhzkml.jasmine.core.prompt.model.SamplingParams
import com.lhzkml.jasmine.core.prompt.model.ToolCall
import com.lhzkml.jasmine.core.prompt.model.ToolChoice
import com.lhzkml.jasmine.core.prompt.model.ToolDescriptor
import com.lhzkml.jasmine.core.prompt.model.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI 兼容 API 的基础客户端
 * DeepSeek、硅基流动等供应商都使用兼容 OpenAI 的接口格式
 */
abstract class OpenAICompatibleClient(
    protected val apiKey: String,
    protected val baseUrl: String,
    protected val retryConfig: RetryConfig = RetryConfig.DEFAULT,
    httpClient: OkHttpClient? = null,
    protected val chatPath: String = "/v1/chat/completions"
) : ThinkingChatClient {

    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    internal val httpClient: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(retryConfig.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(retryConfig.socketTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(retryConfig.socketTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(retryConfig.requestTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    // ========== 消息/工具转换 ==========

    private fun convertMessages(messages: List<ChatMessage>): List<OpenAIRequestMessage> {
        return messages.map { msg ->
            val tc = msg.toolCalls
            when {
                msg.role == "assistant" && !tc.isNullOrEmpty() -> {
                    // 当有 tool_calls 时，根据 API 要求设置 content
                    // SiliconFlow/OpenAI 兼容 API 通常要求：
                    // - 有 tool_calls 时，content 可以为 null 或空字符串
                    // - 为确保兼容性，使用空字符串
                    OpenAIRequestMessage(
                        role = "assistant",
                        content = msg.content.ifEmpty { null },  // 使用 null 更符合 OpenAI 标准
                        toolCalls = tc.map {
                            OpenAIToolCallDef(
                                id = it.id,
                                function = OpenAIFunctionCallDef(name = it.name, arguments = it.arguments)
                            )
                        }
                    )
                }
                msg.role == "tool" -> {
                    // tool 消息必须有 tool_call_id 和 content
                    require(msg.toolCallId != null) {
                        "Tool 消息必须包含 toolCallId"
                    }
                    OpenAIRequestMessage(
                        role = "tool",
                        content = msg.content.ifEmpty { "Tool returned empty result" },
                        toolCallId = msg.toolCallId
                    )
                }
                msg.role == "user" -> OpenAIRequestMessage(
                    role = "user",
                    content = msg.content.ifEmpty { "" }
                )
                msg.role == "assistant" -> OpenAIRequestMessage(
                    role = "assistant",
                    content = msg.content.ifEmpty { "" }
                )
                msg.role == "system" -> OpenAIRequestMessage(
                    role = "system",
                    content = msg.content.ifEmpty { "" }
                )
                else -> OpenAIRequestMessage(
                    role = msg.role,
                    content = msg.content.ifEmpty { "" }
                )
            }
        }
    }

    private fun convertTools(tools: List<ToolDescriptor>): List<OpenAIToolDef>? {
        if (tools.isEmpty()) return null
        return tools.map {
            OpenAIToolDef(
                function = OpenAIFunctionDef(
                    name = it.name,
                    description = it.description,
                    parameters = it.toJsonSchema()
                )
            )
        }
    }

    // ========== ChatClient 实现 ==========

    /**
     * 将 ToolChoice 转换为 OpenAI API 的 tool_choice JSON 格式
     */
    protected fun convertToolChoice(toolChoice: ToolChoice?): kotlinx.serialization.json.JsonElement? {
        return when (toolChoice) {
            null -> null
            is ToolChoice.Auto -> kotlinx.serialization.json.JsonPrimitive("auto")
            is ToolChoice.Required -> kotlinx.serialization.json.JsonPrimitive("required")
            is ToolChoice.None -> kotlinx.serialization.json.JsonPrimitive("none")
            is ToolChoice.Named -> kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("function"))
                put("function", kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive(toolChoice.toolName))
                })
            }
        }
    }

    override suspend fun chat(
        messages: List<ChatMessage>, model: String, maxTokens: Int?,
        samplingParams: SamplingParams?, tools: List<ToolDescriptor>,
        toolChoice: ToolChoice?
    ): String {
        return chatWithUsage(messages, model, maxTokens, samplingParams, tools, toolChoice).content
    }

    override suspend fun chatWithUsage(
        messages: List<ChatMessage>, model: String, maxTokens: Int?,
        samplingParams: SamplingParams?, tools: List<ToolDescriptor>,
        toolChoice: ToolChoice?
    ): ChatResult {
        // 通过流式实现非流式：收集所有 chunk 拼接为完整结果
        val content = StringBuilder()
        val streamResult = chatStreamWithUsage(messages, model, maxTokens, samplingParams, tools, toolChoice) { chunk ->
            content.append(chunk)
        }
        return ChatResult(
            content = content.toString(),
            usage = streamResult.usage,
            finishReason = streamResult.finishReason,
            toolCalls = streamResult.toolCalls,
            thinking = streamResult.thinking
        )
    }

    override fun chatStream(
        messages: List<ChatMessage>, model: String, maxTokens: Int?,
        samplingParams: SamplingParams?, tools: List<ToolDescriptor>,
        toolChoice: ToolChoice?
    ): Flow<String> = flow {
        chatStreamWithUsage(messages, model, maxTokens, samplingParams, tools, toolChoice) { emit(it) }
    }

    override suspend fun chatStreamWithUsage(
        messages: List<ChatMessage>, model: String, maxTokens: Int?,
        samplingParams: SamplingParams?, tools: List<ToolDescriptor>,
        toolChoice: ToolChoice?,
        onChunk: suspend (String) -> Unit
    ): StreamResult = chatStreamWithThinking(messages, model, maxTokens, samplingParams, tools, toolChoice, onChunk, {})

    override suspend fun chatStreamWithThinking(
        messages: List<ChatMessage>, model: String, maxTokens: Int?,
        samplingParams: SamplingParams?, tools: List<ToolDescriptor>,
        toolChoice: ToolChoice?,
        onChunk: suspend (String) -> Unit,
        onThinking: suspend (String) -> Unit
    ): StreamResult {
        return executeWithRetry(retryConfig) {
            try {
                val request = ChatRequest(
                    model = model,
                    messages = convertMessages(messages),
                    stream = true,
                    temperature = samplingParams?.temperature,
                    topP = samplingParams?.topP,
                    maxTokens = maxTokens,
                    tools = convertTools(tools),
                    toolChoice = convertToolChoice(toolChoice)
                )
                
                val requestBody = json.encodeToString(request)
                    .toRequestBody("application/json".toMediaType())

                android.util.Log.d("OpenAICompat", "Request: POST ${baseUrl}${chatPath} model=$model tools=${tools.size}")
                android.util.Log.d("OpenAICompat", "Request Body: $requestBody")
                android.util.Log.d("OpenAICompat", "Messages count: ${request.messages.size}")
                request.messages.forEachIndexed { index, msg ->
                    android.util.Log.d("OpenAICompat", "  Message[$index]: role=${msg.role}, content=${msg.content}, toolCalls=${msg.toolCalls?.size}, toolCallId=${msg.toolCallId}")
                }
                
                val httpRequest = Request.Builder()
                    .url("${baseUrl}${chatPath}")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                val fullContent = StringBuilder()
                val thinkingContent = StringBuilder()
                var lastUsage: Usage? = null
                var lastFinishReason: String? = null
                val toolCallAccumulator = mutableMapOf<Int, Triple<String, String, StringBuilder>>()

                withContext(Dispatchers.IO) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val call = httpClient.newCall(httpRequest)
                        
                        continuation.invokeOnCancellation {
                            call.cancel()
                        }
                        
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                continuation.resumeWithException(e)
                            }

                            override fun onResponse(call: Call, response: Response) {
                                try {
                                    if (!response.isSuccessful) {
                                        val body = response.body?.string() ?: ""
                                        android.util.Log.e("OpenAICompat", "API error: code=${response.code}, body=$body")
                                        val logMsg = buildString {
                                            appendLine("=== API Error Response ===")
                                            appendLine("URL: ${baseUrl}${chatPath}")
                                            appendLine("Status: ${response.code}")
                                            appendLine("Request model: $model")
                                            appendLine("Stream: true")
                                            if (tools.isNotEmpty()) {
                                                appendLine("Tools: ${tools.map { it.name }}")
                                            }
                                            appendLine("Response Body:")
                                            appendLine(body)
                                            appendLine("=== End API Error Response ===")
                                        }
                                        android.util.Log.e("API_Error_Log", logMsg)
                                        continuation.resumeWithException(
                                            ChatClientException.fromStatusCode(provider.name, response.code, body)
                                        )
                                        return
                                    }

                                    response.body?.charStream()?.buffered()?.use { reader ->
                                        reader.lineSequence().forEach { line ->
                                            if (line.startsWith("data: ")) {
                                                val data = line.substring(6).trim()
                                                if (data == "[DONE]") return@forEach
                                                
                                                try {
                                                    val chunk = json.decodeFromString<ChatStreamResponse>(data)
                                                    if (chunk.usage != null) lastUsage = chunk.usage
                                                    val firstChoice = chunk.choices.firstOrNull()
                                                    if (firstChoice?.finishReason != null) lastFinishReason = firstChoice.finishReason
                                                    
                                                    val content = firstChoice?.delta?.content
                                                    if (!content.isNullOrEmpty()) {
                                                        fullContent.append(content)
                                                        kotlinx.coroutines.runBlocking { onChunk(content) }
                                                    }
                                                    
                                                    val reasoning = firstChoice?.delta?.reasoningContent
                                                    if (!reasoning.isNullOrEmpty()) {
                                                        thinkingContent.append(reasoning)
                                                        kotlinx.coroutines.runBlocking { onThinking(reasoning) }
                                                    }
                                                    
                                                    firstChoice?.delta?.toolCalls?.forEach { stc ->
                                                        val tcId = stc.id
                                                        if (tcId != null) {
                                                            toolCallAccumulator[stc.index] = Triple(
                                                                tcId,
                                                                stc.function?.name ?: "",
                                                                StringBuilder(stc.function?.arguments ?: "")
                                                            )
                                                        } else {
                                                            toolCallAccumulator[stc.index]?.let { (_, _, args) ->
                                                                args.append(stc.function?.arguments ?: "")
                                                            }
                                                        }
                                                    }
                                                } catch (_: Exception) { }
                                            }
                                        }
                                    }
                                    
                                    continuation.resume(Unit)
                                } catch (e: Exception) {
                                    continuation.resumeWithException(e)
                                }
                            }
                        })
                    }
                }

                val toolCalls = toolCallAccumulator.entries
                    .sortedBy { it.key }
                    .map { (_, t) -> ToolCall(id = t.first, name = t.second, arguments = t.third.toString()) }

                StreamResult(
                    content = fullContent.toString(),
                    usage = lastUsage,
                    finishReason = lastFinishReason,
                    toolCalls = toolCalls,
                    thinking = thinkingContent.toString().ifEmpty { null }
                )
            } catch (e: ChatClientException) { throw e }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: UnknownHostException) { throw ChatClientException(provider.name, "无法连接到服务器，请检查网络", ErrorType.NETWORK, cause = e) }
            catch (e: ConnectException) { throw ChatClientException(provider.name, "连接失败，请检查网络", ErrorType.NETWORK, cause = e) }
            catch (e: SocketTimeoutException) { throw ChatClientException(provider.name, "请求超时，请稍后重试", ErrorType.NETWORK, cause = e) }
            catch (e: Exception) { throw ChatClientException(provider.name, "流式请求失败: ${e.message}", ErrorType.UNKNOWN, cause = e) }
        }
    }

    // ========== 模型列表 ==========

    override suspend fun listModels(): List<ModelInfo> {
        return executeWithRetry(retryConfig) {
            try {
                val httpRequest = Request.Builder()
                    .url("${baseUrl}/v1/models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                withContext(Dispatchers.IO) {
                    val response = httpClient.newCall(httpRequest).execute()
                    
                    if (!response.isSuccessful) {
                        val body = response.body?.string()
                        throw ChatClientException.fromStatusCode(provider.name, response.code, body)
                    }
                    
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val root = json.parseToJsonElement(body).jsonObject
                    val dataArray = root["data"]?.jsonArray ?: return@withContext emptyList()
                    dataArray.map { parseModelInfoFromJson(it.jsonObject) }
                }
            } catch (e: ChatClientException) { throw e }
            catch (e: Exception) { throw ChatClientException(provider.name, "获取模型列表失败: ${e.message}", ErrorType.UNKNOWN, cause = e) }
        }
    }

    protected fun parseModelInfoFromJson(obj: JsonObject): ModelInfo {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val objectType = obj["object"]?.jsonPrimitive?.contentOrNull ?: "model"
        val ownedBy = obj["owned_by"]?.jsonPrimitive?.contentOrNull ?: ""
        val contextLength = (obj["context_length"] ?: obj["context_window"]
            ?: obj["max_context_length"])?.jsonPrimitive?.intOrNull
        val maxOutputTokens = (obj["max_tokens"] ?: obj["max_output_tokens"]
            ?: obj["max_completion_tokens"])?.jsonPrimitive?.intOrNull
        val displayName = (obj["display_name"] ?: obj["name"])?.jsonPrimitive?.contentOrNull
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
        val temperature = (obj["temperature"] ?: obj["default_temperature"])?.jsonPrimitive?.doubleOrNull
        val maxTemperature = (obj["max_temperature"] ?: obj["top_temperature"])?.jsonPrimitive?.doubleOrNull
        val topP = obj["top_p"]?.jsonPrimitive?.doubleOrNull
        val topK = obj["top_k"]?.jsonPrimitive?.intOrNull
        return ModelInfo(
            id = id, objectType = objectType, ownedBy = ownedBy,
            displayName = displayName, contextLength = contextLength,
            maxOutputTokens = maxOutputTokens, description = description,
            temperature = temperature, maxTemperature = maxTemperature,
            topP = topP, topK = topK
        )
    }

    override fun close() {
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
    }
}
