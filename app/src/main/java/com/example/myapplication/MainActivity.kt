package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class MainActivity : AppCompatActivity(), CoroutineScope {

    private lateinit var chatLayout: LinearLayout
    private lateinit var inputEdit: EditText
    private lateinit var sendBtn: Button
    private var chatClient: AiChatClient? = null
    private var isInitialized = false
    private lateinit var prefs: SharedPreferences
    private val job = Job()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)

        // 初始化布局
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }

        // 标题栏
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(this).apply {
            text = "AI 聊天 (Rust Core)"
            textSize = 20f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val settingsBtn = Button(this).apply {
            text = "⚙️"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        val agentBtn = Button(this).apply {
            text = "🤖"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(8, 0, 0, 0)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AgentActivity::class.java))
            }
        }

        titleLayout.addView(titleText)
        titleLayout.addView(agentBtn)
        titleLayout.addView(settingsBtn)
        rootLayout.addView(titleLayout)
        val separator = View(this)
        separator.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        )
        separator.setBackgroundColor(0xFFCCCCCC.toInt())
        rootLayout.addView(separator)

        // 聊天消息区域
        val scrollview = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        chatLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollview.addView(chatLayout)
        rootLayout.addView(scrollview)

        // 输入区域
        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        inputEdit = EditText(this).apply {
            hint = "输入消息..."
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        sendBtn = Button(this).apply {
            text = "发送"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { sendMessage() }
        }

        inputLayout.addView(inputEdit)
        inputLayout.addView(sendBtn)
        rootLayout.addView(inputLayout)

        setContentView(rootLayout)

        // 初始化 AI 聊天客户端
        initChatClient()
    }

    override fun onResume() {
        super.onResume()
        // 从设置页面返回时重新初始化客户端
        initChatClient()
    }

    private fun initChatClient() {
        val apiUrl = prefs.getString(SettingsActivity.KEY_API_URL, SettingsActivity.DEFAULT_API_URL) ?: SettingsActivity.DEFAULT_API_URL
        val apiKey = prefs.getString(SettingsActivity.KEY_API_KEY, SettingsActivity.DEFAULT_API_KEY) ?: SettingsActivity.DEFAULT_API_KEY
        val model = prefs.getString(SettingsActivity.KEY_MODEL, SettingsActivity.DEFAULT_MODEL) ?: SettingsActivity.DEFAULT_MODEL
        val maxTokens = prefs.getInt(SettingsActivity.KEY_MAX_TOKENS, SettingsActivity.DEFAULT_MAX_TOKENS)
        val temperature = prefs.getFloat(SettingsActivity.KEY_TEMPERATURE, SettingsActivity.DEFAULT_TEMPERATURE)

        // 关闭旧客户端
        chatClient?.close()
        chatClient = null

        try {
            System.loadLibrary("ai_chat_core")

            chatClient = AiChatClient(
                apiUrl = apiUrl,
                apiKey = apiKey.ifEmpty { "test_key" },
                model = model,
                maxTokens = maxTokens,
                temperature = temperature
            )
            isInitialized = true

            if (apiKey.isEmpty()) {
                addMessage("system", "⚠️ 请先点击右上角 ⚙️ 配置 API Key")
            } else {
                addMessage("system", "✅ AI 聊天已就绪 ($model)")
            }
        } catch (e: UnsatisfiedLinkError) {
            addMessage("system", "❌ Native 库加载失败: ${e.message}")
        } catch (e: Exception) {
            addMessage("system", "❌ 初始化失败: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun sendMessage() {
        val message = inputEdit.text.toString().trim()
        if (message.isEmpty()) return

        inputEdit.text.clear()
        addMessage("user", message)
        sendBtn.isEnabled = false

        if (!isInitialized) {
            addMessage("system", "❌ AI 客户端未初始化，请检查配置")
            sendBtn.isEnabled = true
            return
        }

        val apiKey = prefs.getString(SettingsActivity.KEY_API_KEY, "") ?: ""
        if (apiKey.isEmpty()) {
            addMessage("system", "❌ 请先配置 API Key（点击右上角 ⚙️）")
            sendBtn.isEnabled = true
            return
        }

        launch {
            val reply = withContext(Dispatchers.IO) {
                try {
                    chatClient?.send(message) ?: "客户端未初始化"
                } catch (e: Exception) {
                    "发送失败: ${e.message}"
                }
            }
            addMessage("assistant", reply)
            sendBtn.isEnabled = true
        }
    }

    private fun addMessage(role: String, content: String) {
        runOnUiThread {
            val textView = TextView(this).apply {
                text = "[$role] $content"
                textSize = 14f
                setPadding(8, 8, 8, 8)
                when (role) {
                    "user" -> {
                        setBackgroundColor(0x330000FF)
                        gravity = Gravity.END
                    }
                    "assistant" -> {
                        setBackgroundColor(0x3300FF00)
                        gravity = Gravity.START
                    }
                    "system" -> {
                        setBackgroundColor(0x33FF0000)
                        gravity = Gravity.CENTER
                        textSize = 12f
                    }
                }
            }
            chatLayout.addView(textView)

            (chatLayout.parent as? ScrollView)?.post {
                (chatLayout.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        chatClient?.close()
    }
}
