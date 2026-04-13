package com.lhzkml.jasmine.core.prompt.executor

import com.lhzkml.jasmine.core.prompt.llm.ChatClientException
import com.lhzkml.jasmine.core.prompt.llm.ErrorType
import com.lhzkml.jasmine.core.prompt.llm.LLMProvider
import com.lhzkml.jasmine.core.prompt.llm.RetryConfig
import com.lhzkml.jasmine.core.prompt.llm.executeWithRetry
import com.lhzkml.jasmine.core.prompt.model.BalanceDetail
import com.lhzkml.jasmine.core.prompt.model.BalanceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 硅基流动客户端
 *
 * 余额查询: GET https://api.siliconflow.cn/v1/user/info
 * 认证: Authorization: Bearer <TOKEN>
 * 返回: { data: { balance: "0.88", chargeBalance: "88.00", totalBalance: "88.88" } }
 */
class SiliconFlowClient(
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    chatPath: String = "/v1/chat/completions",
    retryConfig: RetryConfig = RetryConfig.DEFAULT,
    httpClient: OkHttpClient? = null
) : OpenAICompatibleClient(apiKey, baseUrl, retryConfig, httpClient, chatPath) {

    companion object {
        const val DEFAULT_BASE_URL = "https://api.siliconflow.cn"
    }

    override val provider = LLMProvider.SiliconFlow

    @Serializable
    private data class SiliconFlowUserInfoResponse(
        val code: Int = 0,
        val message: String = "",
        val status: Boolean = false,
        val data: SiliconFlowUserData? = null
    )

    @Serializable
    private data class SiliconFlowUserData(
        val balance: String = "0",
        val chargeBalance: String = "0",
        val totalBalance: String = "0"
    )

    override suspend fun getBalance(): BalanceInfo {
        return executeWithRetry(retryConfig) {
            try {
                val httpRequest = Request.Builder()
                    .url("${baseUrl}/v1/user/info")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(httpRequest).execute()
                }

                if (!response.isSuccessful) {
                    val body = response.body?.string()
                    throw ChatClientException.fromStatusCode(provider.name, response.code, body)
                }

                val body = response.body?.string() ?: throw ChatClientException(provider.name, "响应为空", ErrorType.PARSE_ERROR)
                val result = json.decodeFromString<SiliconFlowUserInfoResponse>(body)
                val data = result.data
                    ?: throw ChatClientException(provider.name, "响应中没有用户数据", ErrorType.PARSE_ERROR)

                val totalBalance = try { data.totalBalance.toDouble() } catch (_: Exception) { 0.0 }

                BalanceInfo(
                    isAvailable = totalBalance > 0,
                    balances = listOf(
                        BalanceDetail(
                            currency = "CNY",
                            totalBalance = data.totalBalance,
                            grantedBalance = data.balance,
                            toppedUpBalance = data.chargeBalance
                        )
                    )
                )
            } catch (e: ChatClientException) {
                throw e
            } catch (e: Exception) {
                throw ChatClientException(provider.name, "查询余额失败: ${e.message}", ErrorType.UNKNOWN, cause = e)
            }
        }
    }

    /**
     * 获取模型列表（支持服务端筛选）
     * @param type 模型类型: text, image, audio, video
     * @param subType 子类型: chat, embedding, reranker, text-to-image, image-to-image, speech-to-text, text-to-video
     */
    suspend fun listModels(type: String? = null, subType: String? = null): List<com.lhzkml.jasmine.core.prompt.model.ModelInfo> {
        return executeWithRetry(retryConfig) {
            try {
                val urlBuilder = StringBuilder("${baseUrl}/v1/models")
                val params = mutableListOf<String>()
                
                type?.let { params.add("type=$it") }
                subType?.let { params.add("sub_type=$it") }
                
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
                    
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val root = json.parseToJsonElement(body).jsonObject
                    val dataArray = root["data"]?.jsonArray ?: return@withContext emptyList()
                    dataArray.map { parseModelInfoFromJson(it.jsonObject) }
                }
            } catch (e: ChatClientException) { throw e }
            catch (e: Exception) { throw ChatClientException(provider.name, "获取模型列表失败: ${e.message}", ErrorType.UNKNOWN, cause = e) }
        }
    }
}
