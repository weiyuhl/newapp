package com.lhzkml.jasmine.core.prompt.executor

import com.lhzkml.jasmine.core.prompt.llm.ChatClientException
import com.lhzkml.jasmine.core.prompt.llm.ErrorType
import com.lhzkml.jasmine.core.prompt.llm.LLMProvider
import com.lhzkml.jasmine.core.prompt.llm.RetryConfig
import com.lhzkml.jasmine.core.prompt.llm.executeWithRetry
import com.lhzkml.jasmine.core.prompt.model.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OpenRouter 客户端
 * 支持 OpenRouter 特有的端点功能
 */
class OpenRouterClient(
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    retryConfig: RetryConfig = RetryConfig.DEFAULT,
    httpClient: OkHttpClient? = null
) : OpenAICompatibleClient(
    apiKey = apiKey,
    baseUrl = baseUrl,
    retryConfig = retryConfig,
    httpClient = httpClient,
    chatPath = "/v1/chat/completions"
) {
    
    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api"
    }
    
    override val provider: LLMProvider = LLMProvider.OpenRouter
    
    // ========== OpenRouter 特有端点 ==========
    
    /**
     * 获取指定模型的所有端点信息
     * GET /api/v1/models/:author/:slug/endpoints
     */
    suspend fun getModelEndpoints(author: String, slug: String): ModelEndpointsResponse {
        return executeWithRetry(retryConfig) {
            try {
                val httpRequest = Request.Builder()
                    .url("${baseUrl}/v1/models/$author/$slug/endpoints")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                withContext(Dispatchers.IO) {
                    val response = httpClient.newCall(httpRequest).execute()
                    
                    if (!response.isSuccessful) {
                        val body = response.body?.string()
                        throw ChatClientException.fromStatusCode(provider.name, response.code, body)
                    }
                    
                    val body = response.body?.string() 
                        ?: throw ChatClientException(provider.name, "Empty response body", ErrorType.UNKNOWN)
                    
                    json.decodeFromString<ModelEndpointsResponse>(body)
                }
            } catch (e: ChatClientException) { throw e }
            catch (e: Exception) { 
                throw ChatClientException(provider.name, "获取模型端点失败: ${e.message}", ErrorType.UNKNOWN, cause = e) 
            }
        }
    }
    
    /**
     * 获取生成请求的使用元数据
     * GET /api/v1/generation?id={generation_id}
     */
    suspend fun getGeneration(generationId: String): GenerationResponse {
        return executeWithRetry(retryConfig) {
            try {
                val httpRequest = Request.Builder()
                    .url("${baseUrl}/v1/generation?id=$generationId")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                withContext(Dispatchers.IO) {
                    val response = httpClient.newCall(httpRequest).execute()
                    
                    if (!response.isSuccessful) {
                        val body = response.body?.string()
                        throw ChatClientException.fromStatusCode(provider.name, response.code, body)
                    }
                    
                    val body = response.body?.string() 
                        ?: throw ChatClientException(provider.name, "Empty response body", ErrorType.UNKNOWN)
                    
                    json.decodeFromString<GenerationResponse>(body)
                }
            } catch (e: ChatClientException) { throw e }
            catch (e: Exception) { 
                throw ChatClientException(provider.name, "获取生成信息失败: ${e.message}", ErrorType.UNKNOWN, cause = e) 
            }
        }
    }
    
    /**
     * 检查 API 密钥的速率限制和剩余额度
     * GET /api/v1/key
     */
    suspend fun getKeyInfo(): KeyInfoResponse {
        return executeWithRetry(retryConfig) {
            try {
                val httpRequest = Request.Builder()
                    .url("${baseUrl}/v1/key")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                withContext(Dispatchers.IO) {
                    val response = httpClient.newCall(httpRequest).execute()
                    
                    if (!response.isSuccessful) {
                        val body = response.body?.string()
                        throw ChatClientException.fromStatusCode(provider.name, response.code, body)
                    }
                    
                    val body = response.body?.string() 
                        ?: throw ChatClientException(provider.name, "Empty response body", ErrorType.UNKNOWN)
                    
                    json.decodeFromString<KeyInfoResponse>(body)
                }
            } catch (e: ChatClientException) { throw e }
            catch (e: Exception) { 
                throw ChatClientException(provider.name, "获取密钥信息失败: ${e.message}", ErrorType.UNKNOWN, cause = e) 
            }
        }
    }
    
    /**
     * 获取所有可用模型列表（OpenRouter 增强版）
     * GET /api/v1/models
     * 返回包含定价、上下文长度等详细信息的模型列表
     */
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
                    
                    dataArray.map { parseOpenRouterModelInfo(it.jsonObject) }
                }
            } catch (e: ChatClientException) { throw e }
            catch (e: Exception) { 
                throw ChatClientException(provider.name, "获取模型列表失败: ${e.message}", ErrorType.UNKNOWN, cause = e) 
            }
        }
    }
    
    /**
     * 获取所有可用模型的原始 JSON 数据（包含完整的定价和参数信息）
     * GET /api/v1/models
     * 
     * @param outputModalities 按输出模态筛选，例如 "text,image"
     * @param supportedParameters 按支持的参数筛选，例如 "tools"
     */
    suspend fun listModelsRaw(
        outputModalities: String? = null,
        supportedParameters: String? = null
    ): String {
        return executeWithRetry(retryConfig) {
            try {
                val urlBuilder = StringBuilder("${baseUrl}/v1/models")
                val params = mutableListOf<String>()
                
                outputModalities?.let { params.add("output_modalities=$it") }
                supportedParameters?.let { params.add("supported_parameters=$it") }
                
                if (params.isNotEmpty()) {
                    urlBuilder.append("?").append(params.joinToString("&"))
                }
                
                val httpRequest = Request.Builder()
                    .url(urlBuilder.toString())
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                withContext(Dispatchers.IO) {
                    val response = httpClient.newCall(httpRequest).execute()
                    
                    if (!response.isSuccessful) {
                        val body = response.body?.string()
                        throw ChatClientException.fromStatusCode(provider.name, response.code, body)
                    }
                    
                    response.body?.string() ?: "{}"
                }
            } catch (e: ChatClientException) { throw e }
            catch (e: Exception) { 
                throw ChatClientException(provider.name, "获取模型列表失败: ${e.message}", ErrorType.UNKNOWN, cause = e) 
            }
        }
    }
    
    /**
     * 解析 OpenRouter 模型信息（包含定价等额外字段）
     */
    private fun parseOpenRouterModelInfo(obj: JsonObject): ModelInfo {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
        val contextLength = obj["context_length"]?.jsonPrimitive?.intOrNull
        
        // 解析定价信息
        val pricing = obj["pricing"]?.jsonObject
        val promptPrice = pricing?.get("prompt")?.jsonPrimitive?.contentOrNull
        val completionPrice = pricing?.get("completion")?.jsonPrimitive?.contentOrNull
        
        // 解析架构信息
        val architecture = obj["architecture"]?.jsonObject
        val modality = architecture?.get("modality")?.jsonPrimitive?.contentOrNull
        val tokenizer = architecture?.get("tokenizer")?.jsonPrimitive?.contentOrNull
        
        // 解析 top_provider 信息
        val topProvider = obj["top_provider"]?.jsonObject
        val maxCompletionTokens = topProvider?.get("max_completion_tokens")?.jsonPrimitive?.intOrNull
        
        return ModelInfo(
            id = id,
            objectType = "model",
            ownedBy = "",
            displayName = name,
            contextLength = contextLength,
            maxOutputTokens = maxCompletionTokens,
            description = description,
            temperature = null,
            maxTemperature = null,
            topP = null,
            topK = null
        )
    }
}

// ========== OpenRouter 数据模型 ==========

/**
 * 模型端点响应
 */
@Serializable
data class ModelEndpointsResponse(
    val data: ModelEndpointsData
)

@Serializable
data class ModelEndpointsData(
    val id: String,
    val name: String,
    val created: Long? = null,
    val description: String? = null,
    val architecture: ModelArchitecture? = null,
    val endpoints: List<EndpointInfo>
)

@Serializable
data class ModelArchitecture(
    val modality: String? = null,
    val tokenizer: String? = null,
    val instruct_type: String? = null,
    val input_modalities: List<String>? = null,
    val output_modalities: List<String>? = null
)

@Serializable
data class EndpointInfo(
    val name: String,
    val model_id: String,
    val model_name: String,
    val context_length: Int,
    val pricing: PricingInfo,
    val provider_name: String,
    val tag: String? = null,
    val quantization: String? = null,
    val max_completion_tokens: Int? = null,
    val max_prompt_tokens: Int? = null,
    val supported_parameters: List<String>? = null,
    val uptime_last_30m: Double? = null,
    val supports_implicit_caching: Boolean? = null,
    val latency_last_30m: LatencyInfo? = null,
    val throughput_last_30m: ThroughputInfo? = null,
    val status: Int? = null
)

@Serializable
data class PricingInfo(
    val prompt: String,
    val completion: String,
    val request: String,
    val image: String
)

@Serializable
data class LatencyInfo(
    val p50: Double? = null,
    val p75: Double? = null,
    val p90: Double? = null,
    val p99: Double? = null
)

@Serializable
data class ThroughputInfo(
    val p50: Double? = null,
    val p75: Double? = null,
    val p90: Double? = null,
    val p99: Double? = null
)

/**
 * 生成信息响应
 */
@Serializable
data class GenerationResponse(
    val data: GenerationData
)

@Serializable
data class GenerationData(
    val id: String,
    val upstream_id: String? = null,
    val total_cost: Double,
    val cache_discount: Double? = null,
    val upstream_inference_cost: Double? = null,
    val created_at: String,
    val model: String,
    val app_id: Int? = null,
    val streamed: Boolean? = null,
    val cancelled: Boolean? = null,
    val provider_name: String? = null,
    val latency: Int? = null,
    val moderation_latency: Int? = null,
    val generation_time: Int? = null,
    val finish_reason: String? = null,
    val tokens_prompt: Int,
    val tokens_completion: Int,
    val native_tokens_prompt: Int? = null,
    val native_tokens_completion: Int? = null,
    val native_tokens_completion_images: Int? = null,
    val native_tokens_reasoning: Int? = null,
    val native_tokens_cached: Int? = null,
    val num_media_prompt: Int? = null,
    val num_input_audio_prompt: Int? = null,
    val num_media_completion: Int? = null,
    val num_search_results: Int? = null,
    val origin: String? = null,
    val usage: Double,
    val is_byok: Boolean? = null,
    val native_finish_reason: String? = null,
    val external_user: String? = null,
    val api_type: String? = null,
    val router: String? = null,
    val provider_responses: List<ProviderResponse>? = null,
    val user_agent: String? = null,
    val http_referer: String? = null
)

@Serializable
data class ProviderResponse(
    val status: Int,
    val id: String,
    val endpoint_id: String? = null,
    val model_permaslug: String? = null,
    val provider_name: String,
    val latency: Int,
    val is_byok: Boolean? = null
)

/**
 * 密钥信息响应
 */
@Serializable
data class KeyInfoResponse(
    val data: KeyInfoData
)

@Serializable
data class KeyInfoData(
    val label: String? = null,
    val usage: Double? = null,
    val usage_daily: Double? = null,
    val usage_weekly: Double? = null,
    val usage_monthly: Double? = null,
    val limit: Double? = null,
    val limit_remaining: Double? = null,
    val limit_reset: String? = null,
    val is_free_tier: Boolean? = null,
    val is_management_key: Boolean? = null,
    val byok_usage: Double? = null,
    val byok_usage_daily: Double? = null,
    val byok_usage_weekly: Double? = null,
    val byok_usage_monthly: Double? = null,
    val include_byok_in_limit: Boolean? = null,
    val rate_limit: RateLimitInfo? = null,
    val expires_at: String? = null
)

@Serializable
data class RateLimitInfo(
    val requests: Int? = null,
    val interval: String? = null
)
