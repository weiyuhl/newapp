package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

// ===== 数据模型 =====

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val name: String? = null,
    val tool_calls: List<ToolCallDef>? = null,
    val tool_call_id: String? = null
)

@Serializable
data class ToolCallDef(
    val id: String,
    val type: String = "function",
    val function: FunctionCallDef
)

@Serializable
data class FunctionCallDef(
    val name: String,
    val arguments: String
)

@Serializable
data class SamplingParams(
    val temperature: Float? = null,
    val top_p: Float? = null
)

@Serializable
data class Usage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)

@Serializable
data class ChatResult(
    val content: String,
    val usage: Usage? = null,
    val finish_reason: String? = null,
    val tool_calls: List<ToolCallResult> = emptyList()
)

@Serializable
data class ToolCallResult(
    val id: String,
    val name: String,
    val arguments: String
)

@Serializable
data class ModelInfo(
    val id: String,
    val object_type: String = "model",
    val owned_by: String = "",
    val display_name: String? = null,
    val context_length: Int? = null,
    val max_output_tokens: Int? = null,
    val description: String? = null,
    val temperature: Double? = null
)

@Serializable
data class BalanceDetail(
    val currency: String,
    val total_balance: String,
    val granted_balance: String,
    val topped_up_balance: String
)

@Serializable
data class BalanceInfo(
    val is_available: Boolean,
    val balances: List<BalanceDetail>
)

@Serializable
data class SiliconFlowUserInfo(
    val code: Int = 0,
    val message: String = "",
    val status: Boolean = false,
    val data: SiliconFlowUserData? = null
)

@Serializable
data class SiliconFlowUserData(
    val balance: String = "0",
    val chargeBalance: String = "0",
    val totalBalance: String = "0"
)

// ===== 供应商枚举 =====

enum class LLMProvider(
    val displayName: String
) {
    SiliconFlow("硅基流动"),
    OpenRouter("OpenRouter"),
    DeepSeek("DeepSeek"),
    Custom("自定义");

    val chatPath: String = "/v1/chat/completions"
    val modelsPath: String = "/v1/models"

    /**
     * 获取默认 API 地址（与 lib.rs 的 providers.rs 保持一致）
     * 不硬编码模型，用户应通过"一键获取模型列表"选择
     */
    val defaultBaseUrl: String
        get() = when (this) {
            SiliconFlow -> "https://api.siliconflow.cn"
            OpenRouter -> "https://openrouter.ai/api"
            DeepSeek -> "https://api.deepseek.com"
            Custom -> ""
        }
}

// ===== 统一供应商客户端 =====

class ProviderClient(
    val provider: LLMProvider,
    val apiKey: String,
    val baseUrl: String,
    val chatPath: String = "/v1/chat/completions",
    connectTimeoutMs: Long = 30000,
    readTimeoutMs: Long = 60000
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 非流式聊天
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        model: String,
        maxTokens: Int? = null,
        temperature: Float? = null
    ): ChatResult = withContext(Dispatchers.IO) {
        val messagesJson = messages.joinToString(",") { m ->
            val toolCalls = m.tool_calls?.joinToString(",") { tc ->
                """{"id":"${tc.id}","type":"${tc.type}","function":{"name":"${tc.function.name}","arguments":${tc.function.arguments}}}"""
            }
            val toolCallId = if (m.tool_call_id != null) """,\"tool_call_id\":\"${m.tool_call_id}\"""" else ""
            val name = if (m.name != null) """,\"name\":\"${m.name}\"""" else ""
            val tc = if (toolCalls != null) """,\"tool_calls\":[$toolCalls]""" else ""
            """{"role":"${m.role}","content":${if (m.content.isEmpty()) "null" else "\"${m.content.replace("\"", "\\\"")}\""}$name$tc$toolCallId}"""
        }

        val maxTok = maxTokens?.toString() ?: "null"
        val temp = temperature?.toString() ?: "null"
        val jsonBody = """{"model":"$model","messages":[$messagesJson],"stream":false,"max_tokens":$maxTok,"temperature":$temp}"""

        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl$chatPath")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val body = response.body?.string() ?: ""
            throw ChatException("API 错误 (${response.code}): $body")
        }

        val body = response.body?.string() ?: throw ChatException("响应为空")
        parseChatResponse(body)
    }

    /**
     * 发送单条消息并获取回复（便捷方法）
     */
    suspend fun sendMessage(message: String, model: String): String {
        val messages = listOf(ChatMessage(role = "user", content = message))
        return chat(messages, model).content
    }

    /**
     * 获取模型列表
     */
    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl${provider.modelsPath}")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: java.net.UnknownHostException) {
            throw ChatException("无法连接到服务器，请检查网络")
        } catch (e: java.net.SocketTimeoutException) {
            throw ChatException("请求超时，请稍后重试")
        } catch (e: java.io.IOException) {
            throw ChatException("网络错误: ${e.message}")
        }

        if (!response.isSuccessful) {
            val errBody = response.body?.string() ?: ""
            response.close()
            throw ChatException("获取模型列表失败 (${response.code}): $errBody")
        }

        val body = response.body?.string()
        response.close()

        if (body.isNullOrEmpty()) return@withContext emptyList()

        // 检查是否是非 JSON 响应
        if (!body.trimStart().startsWith("{") && !body.trimStart().startsWith("[")) {
            throw ChatException("服务器返回非 JSON 响应: ${body.take(100)}")
        }

        val root = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw ChatException("解析 JSON 失败: ${e.message}")
        }

        val rootObj = root.jsonObject
        val dataArray = rootObj["data"]?.jsonArray ?: run {
            val errMsg = rootObj["message"]?.jsonPrimitive?.contentOrNull
                ?: rootObj["error"]?.jsonPrimitive?.contentOrNull
            if (errMsg != null) {
                throw ChatException("API 错误: $errMsg")
            }
            return@withContext emptyList()
        }

        dataArray.mapNotNull { elem ->
            try {
                val obj = elem.jsonObject
                ModelInfo(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    object_type = obj["object"]?.jsonPrimitive?.contentOrNull ?: "model",
                    owned_by = obj["owned_by"]?.jsonPrimitive?.contentOrNull ?: "",
                    display_name = obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: obj["display_name"]?.jsonPrimitive?.contentOrNull,
                    context_length = obj["context_length"]?.jsonPrimitive?.intOrNull
                        ?: obj["context_window"]?.jsonPrimitive?.intOrNull,
                    max_output_tokens = obj["max_output_tokens"]?.jsonPrimitive?.intOrNull
                        ?: obj["max_tokens"]?.jsonPrimitive?.intOrNull,
                    description = obj["description"]?.jsonPrimitive?.contentOrNull
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 硅基流动：查询余额
     */
    suspend fun getBalance(): BalanceInfo? {
        if (provider != LLMProvider.SiliconFlow) return null

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/v1/user/info")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val userInfo = json.decodeFromString<SiliconFlowUserInfo>(body)
            val data = userInfo.data ?: return@withContext null

            val totalBalance = data.totalBalance.toDoubleOrNull() ?: 0.0
            BalanceInfo(
                is_available = totalBalance > 0,
                balances = listOf(
                    BalanceDetail(
                        currency = "CNY",
                        total_balance = data.totalBalance,
                        granted_balance = data.balance,
                        topped_up_balance = data.chargeBalance
                    )
                )
            )
        }
    }

    /**
     * OpenRouter：获取密钥信息
     */
    suspend fun getKeyInfo(): String? {
        if (provider != LLMProvider.OpenRouter) return null

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/v1/key")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            response.body?.string()
        }
    }

    /**
     * 测试连接
     */
    suspend fun testConnection(): TestResult {
        return try {
            val request = Request.Builder()
                .url("$baseUrl$chatPath")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful || response.code == 400) {
                // 400 是正常的（空请求体），说明认证通过
                TestResult(success = true, message = "连接成功 (${provider.displayName})")
            } else {
                TestResult(success = false, message = "连接失败 (${response.code}): $body")
            }
        } catch (e: Exception) {
            TestResult(success = false, message = "连接异常: ${e.message}")
        }
    }

    private fun parseChatResponse(body: String): ChatResult {
        val root = json.parseToJsonElement(body).jsonObject
        val choices = root["choices"]?.jsonArray ?: JsonArray(emptyList())
        val firstChoice = choices.firstOrNull()?.jsonObject

        val message = firstChoice?.get("message")?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.contentOrNull ?: ""

        val finishReason = firstChoice?.get("finish_reason")?.jsonPrimitive?.contentOrNull

        val toolCalls = message?.get("tool_calls")?.jsonArray?.mapNotNull { tc ->
            val obj = tc.jsonObject
            val function = obj["function"]?.jsonObject
            ToolCallResult(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                name = function?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                arguments = function?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""
            )
        } ?: emptyList()

        val usageObj = root["usage"]?.jsonObject
        val usage = if (usageObj != null) {
            Usage(
                prompt_tokens = usageObj["prompt_tokens"]?.jsonPrimitive?.intOrNull,
                completion_tokens = usageObj["completion_tokens"]?.jsonPrimitive?.intOrNull,
                total_tokens = usageObj["total_tokens"]?.jsonPrimitive?.intOrNull
            )
        } else null

        return ChatResult(
            content = content,
            usage = usage,
            finish_reason = finishReason,
            tool_calls = toolCalls
        )
    }

    fun close() {
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
    }
}

class ChatException(message: String) : Exception(message)

data class TestResult(
    val success: Boolean,
    val message: String
)

// ===== 供应商管理器 =====

class ProviderManager(context: Context) {

    private val prefs = context.getSharedPreferences("provider_settings", Context.MODE_PRIVATE)
    private var currentClient: ProviderClient? = null

    companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_API_KEY = "api_key"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
    }

    val availableProviders = listOf(
        LLMProvider.SiliconFlow,
        LLMProvider.OpenRouter,
        LLMProvider.DeepSeek,
        LLMProvider.Custom
    )

    var currentProvider: LLMProvider
        get() = LLMProvider.valueOf(prefs.getString(KEY_PROVIDER, LLMProvider.SiliconFlow.name) ?: LLMProvider.SiliconFlow.name)
        set(value) = prefs.edit().putString(KEY_PROVIDER, value.name).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var baseUrl: String
        get() {
            val custom = prefs.getString(KEY_BASE_URL, "") ?: ""
            return custom.ifEmpty { currentProvider.defaultBaseUrl }
        }
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, getDefaultModel(currentProvider)) ?: getDefaultModel(currentProvider)
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    fun getDefaultModel(provider: LLMProvider): String {
        // 不硬编码，用户应通过"一键获取模型列表"来选择模型
        return ""
    }

    fun getProviderBaseUrl(provider: LLMProvider): String {
        // 从 lib.rs 获取，保持与 Rust 端一致
        return when (provider) {
            LLMProvider.SiliconFlow -> "https://api.siliconflow.cn"
            LLMProvider.OpenRouter -> "https://openrouter.ai/api"
            LLMProvider.DeepSeek -> "https://api.deepseek.com"
            LLMProvider.Custom -> ""
        }
    }

    fun getClient(): ProviderClient {
        val client = currentClient
        if (client != null &&
            client.provider == currentProvider &&
            client.apiKey == apiKey &&
            client.baseUrl == baseUrl) {
            return client
        }

        // 关闭旧客户端
        client?.close()

        val newClient = ProviderClient(
            provider = currentProvider,
            apiKey = apiKey,
            baseUrl = baseUrl,
            chatPath = currentProvider.chatPath
        )
        currentClient = newClient
        return newClient
    }

    fun close() {
        currentClient?.close()
        currentClient = null
    }
}
