package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.CoroutineContext

class AgentActivity : AppCompatActivity(), CoroutineScope {

    private lateinit var prefs: SharedPreferences
    private var chatClient: AiChatClient? = null
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
        prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
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
        val apiUrl = prefs.getString(SettingsActivity.KEY_API_URL, SettingsActivity.DEFAULT_API_URL) ?: SettingsActivity.DEFAULT_API_URL
        val apiKey = prefs.getString(SettingsActivity.KEY_API_KEY, SettingsActivity.DEFAULT_API_KEY) ?: SettingsActivity.DEFAULT_API_KEY
        val model = prefs.getString(SettingsActivity.KEY_MODEL, SettingsActivity.DEFAULT_MODEL) ?: SettingsActivity.DEFAULT_MODEL
        val maxTokens = prefs.getInt(SettingsActivity.KEY_MAX_TOKENS, SettingsActivity.DEFAULT_MAX_TOKENS)
        val temperature = prefs.getFloat(SettingsActivity.KEY_TEMPERATURE, SettingsActivity.DEFAULT_TEMPERATURE)

        try {
            chatClient = AiChatClient(
                apiUrl = apiUrl,
                apiKey = apiKey.ifEmpty { "test" },
                model = model,
                maxTokens = maxTokens,
                temperature = temperature
            )
            addStep("system", "Agent 已就绪，等待任务...")
        } catch (e: Exception) {
            addStep("error", "初始化失败: ${e.message}")
        }
    }

    private fun runAgent() {
        val task = taskEdit.text.toString().trim()
        if (task.isEmpty()) return

        // 清空之前的步骤
        stepsLayout.removeAllViews()
        answerText.text = "等待中..."
        runBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE

        launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    chatClient?.agentRun(task, 10) ?: "客户端未初始化"
                } catch (e: Exception) {
                    "{\"success\":false,\"answer\":\"异常: ${e.message}\",\"steps\":[]}"
                }
            }

            parseAndDisplayResult(result)
            runBtn.isEnabled = true
            progressBar.visibility = View.GONE
        }
    }

    private fun parseAndDisplayResult(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val success = json.getBoolean("success")
            val answer = json.getString("answer")
            val steps = json.getJSONArray("steps")

            // 显示步骤
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val type = step.getString("type")
                val content = step.getString("content")
                addStep(type, content)
            }

            // 显示最终答案
            answerText.text = answer
            if (success) {
                answerText.setBackgroundColor(0x3300FF00)
            } else {
                answerText.setBackgroundColor(0x33FF0000)
            }
        } catch (e: Exception) {
            answerText.text = "解析结果失败: $jsonStr"
            answerText.setBackgroundColor(0x33FF0000)
        }
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
        chatClient?.close()
    }
}
