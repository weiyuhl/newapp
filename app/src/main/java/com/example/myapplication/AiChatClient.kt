package com.example.myapplication

/**
 * AI 聊天客户端 - JNI 桥接类
 * 负责调用 Rust 实现的 AI 聊天核心库
 */
class AiChatClient(
    apiUrl: String,
    apiKey: String,
    model: String,
    maxTokens: Int = 1024,
    temperature: Float = 0.7f
) : AutoCloseable {

    private var clientId: String? = null
    private var isClosed = false

    init {
        // 加载 native 库
        System.loadLibrary("ai_chat_core")
        
        // 创建 Rust 端客户端实例
        clientId = nativeCreate(apiUrl, apiKey, model, maxTokens, temperature)
    }

    /**
     * 发送消息并获取 AI 回复
     * @param message 用户消息
     * @return AI 回复内容或错误信息
     */
    fun send(message: String): String {
        checkNotNull(clientId) { "Client not initialized" }
        return nativeSend(clientId!!, message)
    }

    /**
     * 清除聊天历史记录
     */
    fun clearHistory() {
        clientId?.let { nativeClearHistory(it) }
    }

    /**
     * 释放 native 资源
     */
    override fun close() {
        if (!isClosed) {
            clientId?.let { nativeDestroy(it) }
            clientId = null
            isClosed = true
        }
    }

    /**
     * 执行 Agent 任务
     * @param task 任务描述
     * @param maxIterations 最大迭代次数
     * @return JSON 格式的结果
     */
    fun agentRun(task: String, maxIterations: Int = 10): String {
        checkNotNull(clientId) { "Client not initialized" }
        return nativeAgentRun(clientId!!, task, maxIterations)
    }

    // ===== JNI Native Methods =====

    private external fun nativeCreate(
        apiUrl: String,
        apiKey: String,
        model: String,
        maxTokens: Int,
        temperature: Float
    ): String

    private external fun nativeSend(
        clientId: String,
        message: String
    ): String

    private external fun nativeClearHistory(clientId: String)

    private external fun nativeDestroy(clientId: String)

    private external fun nativeAgentRun(
        clientId: String,
        task: String,
        maxIterations: Int
    ): String
}
