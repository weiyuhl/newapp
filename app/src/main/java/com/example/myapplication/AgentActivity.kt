package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class AgentActivity : AppCompatActivity(), CoroutineScope {

    private lateinit var prefs: SharedPreferences
    private var providerClient: ProviderClient? = null
    private lateinit var taskEdit: EditText
    private lateinit var runBtn: Button
    private lateinit var stepsLayout: LinearLayout
    private lateinit var answerText: TextView
    private lateinit var progressBar: ProgressBar
    private val job = Job()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ai_chat_settings", Context.MODE_PRIVATE)
        buildUI()
        initClient()
    }

    private fun buildUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }

        // 标题
        TextView(this).apply {
            text = "🤖 AI Agent Loop"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
            rootLayout.addView(this)
        }

        TextView(this).apply {
            text = "ReAct 模式: Thought → Action → Observation"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF888888.toInt())
            rootLayout.addView(this)
        }

        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            )
            setBackgroundColor(0xFFCCCCCC.toInt())
            setPadding(0, 8, 0, 8)
            rootLayout.addView(this)
        }

        // 任务输入
        TextView(this).apply {
            text = "任务:"
            textSize = 14f
            setPadding(0, 8, 0, 4)
            rootLayout.addView(this)
        }

        taskEdit = EditText(this).apply {
            setText("计算 123 + 456 * 2 的结果，并告诉我当前时间")
            hint = "例如: 计算 123 * 456 的平方根"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            maxLines = 3
            rootLayout.addView(this)
        }

        // 运行按钮
        runBtn = Button(this).apply {
            text = "🚀 运行 Agent"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { runAgent() }
            rootLayout.addView(this)
        }

        // 进度条
        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rootLayout.addView(this)
        }

        // 滚动区域
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        stepsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(stepsLayout)
        rootLayout.addView(scrollView)

        // 最终答案
        TextView(this).apply {
            text = "最终答案:"
            textSize = 14f
            setPadding(0, 8, 0, 4)
            rootLayout.addView(this)
        }

        answerText = TextView(this).apply {
            textSize = 14f
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0x3300FF00)
            rootLayout.addView(this)
        }

        setContentView(rootLayout)
    }

    private fun initClient() {
        val providerManager = ProviderManager(this)
        val provider = providerManager.currentProvider
        val apiKey = providerManager.apiKey
        val baseUrl = providerManager.baseUrl
        val model = providerManager.model

        try {
            providerClient = ProviderClient(
                provider = provider,
                apiKey = apiKey.ifEmpty { "test" },
                baseUrl = baseUrl,
                chatPath = provider.chatPath
            )
            addStep("system", "Agent 已就绪 (${provider.displayName})")
        } catch (e: Exception) {
            addStep("error", "初始化失败: ${e.message}")
        }
    }

    private fun runAgent() {
        val task = taskEdit.text.toString().trim()
        if (task.isEmpty()) return

        stepsLayout.removeAllViews()
        answerText.text = "等待中..."
        runBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE

        launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = providerClient ?: return@withContext "客户端未初始化"
                    val model = prefs.getString("model", "Qwen/Qwen2.5-7B-Instruct") ?: "Qwen/Qwen2.5-7B-Instruct"
                    runAgentLoop(client, task, model)
                } catch (e: Exception) {
                    "异常: ${e.message}"
                }
            }

            answerText.text = result
            answerText.setBackgroundColor(0x3300FF00)
            runBtn.isEnabled = true
            progressBar.visibility = View.GONE
        }
    }

    private suspend fun runAgentLoop(client: ProviderClient, task: String, model: String): String {
        val messages = mutableListOf(
            ChatMessage(
                role = "system",
                content = """你是一个智能助手，可以使用工具来完成任务。

可用工具:
- calculator: 执行数学计算，参数: expression (数学表达式)
- current_time: 获取当前日期和时间，无参数
- echo: 回显输入内容，参数: text (要回显的文本)

响应格式，使用 XML 标签:
<thought>你的思考过程</thought>
<action>工具名称</action>
<action_input>key=value</action_input>

当你得到最终答案时，使用:
<final_answer>你的最终答案</final_answer>

规则:
1. 每次只能调用一个工具
2. 等待工具返回结果后再继续
3. 如果可以直接回答用户问题，使用 final_answer"""
            ),
            ChatMessage(role = "user", content = "任务: $task")
        )

        val steps = mutableListOf<String>()
        val maxIterations = 10

        for (i in 0 until maxIterations) {
            // 获取 AI 回复
            val reply = try {
                client.chat(messages, model, maxTokens = 512).content
            } catch (e: Exception) {
                return "请求失败: ${e.message}"
            }

            messages.add(ChatMessage(role = "assistant", content = reply))

            // 检查 final_answer
            val finalAnswer = extractTag(reply, "final_answer")
            if (finalAnswer != null) {
                addStep("final_answer", finalAnswer)
                steps.add("✅ $finalAnswer")
                return finalAnswer
            }

            // 提取 thought
            val thought = extractTag(reply, "thought")
            if (thought != null) {
                addStep("thought", thought)
                steps.add("💭 $thought")
            }

            // 提取 action
            val action = extractTag(reply, "action")
            if (action != null) {
                val actionInput = extractTag(reply, "action_input") ?: ""
                addStep("action", "$action($actionInput)")
                steps.add("🔧 $action($actionInput)")

                // 执行工具
                val toolResult = executeTool(action, actionInput)
                addStep("observation", toolResult)
                steps.add("👁️ $toolResult")

                messages.add(ChatMessage(
                    role = "user",
                    content = "工具 $action 执行结果:\n$toolResult"
                ))
            } else {
                // 没有 action 也没有 final_answer，直接回复
                addStep("final_answer", reply)
                return reply
            }
        }

        return "超过最大迭代次数 ($maxIterations)"
    }

    private fun extractTag(content: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = content.indexOf(open)
        if (start == -1) return null
        val end = content.indexOf(close, start + open.length)
        if (end == -1) return null
        return content.substring(start + open.length, end).trim()
    }

    private fun executeTool(toolName: String, args: String): String {
        return when (toolName) {
            "calculator" -> {
                val expr = args.substringAfter("expression=", "").substringBefore(",").trim()
                if (expr.isEmpty()) return "错误: 缺少 expression 参数"
                try {
                    val result = evalExpression(expr)
                    "$expr = $result"
                } catch (e: Exception) {
                    "计算错误: ${e.message}"
                }
            }
            "current_time" -> {
                val now = System.currentTimeMillis() / 1000
                val days = now / 86400
                val hours = (now % 86400) / 3600
                val mins = (now % 3600) / 60
                val secs = now % 60
                "Unix时间戳: $now (${days}天 ${hours}:${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')} UTC)"
            }
            "echo" -> {
                args.substringAfter("text=", "").trim()
            }
            else -> "错误: 未知工具 '$toolName'"
        }
    }

    private fun evalExpression(expr: String): Double {
        val e = expr.trim()
        if (e.isEmpty()) throw IllegalArgumentException("空表达式")
        
        // 简单计算器
        if (e.contains('+')) {
            val parts = e.split('+', limit = 2)
            return evalExpression(parts[0]) + evalExpression(parts[1])
        }
        if (e.contains('-') && !e.startsWith('-')) {
            val parts = e.split('-', limit = 2)
            if (parts[0].isNotEmpty()) {
                return evalExpression(parts[0]) - evalExpression(parts[1])
            }
        }
        if (e.contains('*')) {
            val parts = e.split('*', limit = 2)
            return evalExpression(parts[0]) * evalExpression(parts[1])
        }
        if (e.contains('/')) {
            val parts = e.split('/', limit = 2)
            val right = evalExpression(parts[1])
            if (right == 0.0) throw ArithmeticException("除以零")
            return evalExpression(parts[0]) / right
        }
        return e.toDoubleOrNull() ?: throw IllegalArgumentException("无法解析: $e")
    }

    private fun addStep(type: String, content: String) {
        runOnUiThread {
            val bgColor = when (type) {
                "thought" -> 0x330000FF
                "action" -> 0x33FFA500
                "observation" -> 0x33808080
                "final_answer" -> 0x3300FF00
                "error" -> 0x33FF0000
                "system" -> 0x33FFFFFF
                else -> 0x33CCCCCC
            }

            val icon = when (type) {
                "thought" -> "💭"
                "action" -> "🔧"
                "observation" -> "👁️"
                "final_answer" -> "✅"
                "error" -> "❌"
                "system" -> "ℹ️"
                else -> "•"
            }

            val tv = TextView(this).apply {
                text = "$icon [$type]\n$content"
                textSize = 13f
                setPadding(8, 8, 8, 8)
                setBackgroundColor(bgColor)
            }
            stepsLayout.addView(tv)

            (stepsLayout.parent as? ScrollView)?.post {
                (stepsLayout.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        providerClient?.close()
    }
}
