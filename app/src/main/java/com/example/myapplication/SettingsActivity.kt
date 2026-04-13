package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var apiUrlEdit: EditText
    private lateinit var apiKeyEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var maxTokensEdit: EditText
    private lateinit var temperatureEdit: EditText
    private lateinit var saveBtn: Button
    private lateinit var testBtn: Button
    private lateinit var statusText: TextView

    companion object {
        const val PREFS_NAME = "ai_chat_settings"
        const val KEY_API_URL = "api_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_TEMPERATURE = "temperature"

        // 默认值
        const val DEFAULT_API_URL = "https://api.siliconflow.cn/v1/chat/completions"
        const val DEFAULT_API_KEY = ""
        const val DEFAULT_MODEL = "Qwen/Qwen2.5-7B-Instruct"
        const val DEFAULT_MAX_TOKENS = 1024
        const val DEFAULT_TEMPERATURE = 0.7f

        // 常用模型列表
        val MODELS = arrayOf(
            "Qwen/Qwen2.5-7B-Instruct",
            "Qwen/Qwen2.5-14B-Instruct",
            "Qwen/Qwen2.5-32B-Instruct",
            "Qwen/Qwen2.5-72B-Instruct",
            "THUDM/glm-4-9b-chat",
            "deepseek-ai/DeepSeek-V3",
            "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
            "internlm/internlm2_5-7b-chat",
            "01-ai/Yi-1.5-9B-Chat-16K"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        buildUI()
        loadSettings()
    }

    private fun buildUI() {
        val scrollview = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(24, 24, 24, 24)
        }
        scrollview.addView(rootLayout)

        // 标题
        TextView(this).apply {
            text = "AI 聊天配置"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
            rootLayout.addView(this)
        }

        TextView(this).apply {
            text = "硅基流动 (SiliconFlow)"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
            setTextColor(0xFF666666.toInt())
            rootLayout.addView(this)
        }

        // API URL
        addLabelAndEdit(rootLayout, "API 地址:", "https://api.siliconflow.cn/v1/chat/completions").also {
            apiUrlEdit = it
        }

        // API Key
        addLabelAndEdit(rootLayout, "API Key:", "sk-xxxxxx").also {
            apiKeyEdit = it
            apiKeyEdit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        // 模型选择
        val modelLabel = TextView(this).apply {
            text = "模型:"
            textSize = 14f
            setPadding(0, 16, 0, 4)
            rootLayout.addView(this)
        }

        val modelSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            adapter = ArrayAdapter(this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item, MODELS)
            rootLayout.addView(this)
        }

        // 自定义模型输入
        modelEdit = EditText(this).apply {
            hint = "或输入自定义模型名称"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rootLayout.addView(this)
        }

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                modelEdit.setText(MODELS[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Max Tokens
        addLabelAndEdit(rootLayout, "最大 Tokens:", "1024").also {
            maxTokensEdit = it
            maxTokensEdit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        // Temperature
        addLabelAndEdit(rootLayout, "Temperature (0.0-2.0):", "0.7").also {
            temperatureEdit = it
            temperatureEdit.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        // 说明
        TextView(this).apply {
            text = "💡 提示: API Key 可在硅基流动官网获取\nhttps://cloud.siliconflow.cn"
            textSize = 12f
            setPadding(0, 16, 0, 16)
            setTextColor(0xFF888888.toInt())
            rootLayout.addView(this)
        }

        // 按钮区域
        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        saveBtn = Button(this).apply {
            text = "保存配置"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveSettings() }
        }

        testBtn = Button(this).apply {
            text = "测试连接"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { testConnection() }
        }

        btnLayout.addView(saveBtn)
        btnLayout.addView(testBtn)
        rootLayout.addView(btnLayout)

        // 状态文本
        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 16, 0, 0)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(statusText)

        setContentView(scrollview)
    }

    private fun addLabelAndEdit(root: LinearLayout, label: String, hint: String): EditText {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 14f
        tv.setPadding(0, 16, 0, 4)
        root.addView(tv)

        val edit = EditText(this)
        edit.hint = hint
        edit.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        root.addView(edit)

        return edit
    }

    private fun loadSettings() {
        apiUrlEdit.setText(prefs.getString(KEY_API_URL, DEFAULT_API_URL))
        apiKeyEdit.setText(prefs.getString(KEY_API_KEY, DEFAULT_API_KEY))
        modelEdit.setText(prefs.getString(KEY_MODEL, DEFAULT_MODEL))
        maxTokensEdit.setText(prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS).toString())
        temperatureEdit.setText(prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE).toString())
    }

    private fun saveSettings() {
        val editor = prefs.edit()
        editor.putString(KEY_API_URL, apiUrlEdit.text.toString().trim())
        editor.putString(KEY_API_KEY, apiKeyEdit.text.toString().trim())
        editor.putString(KEY_MODEL, modelEdit.text.toString().trim())
        editor.putInt(KEY_MAX_TOKENS, maxTokensEdit.text.toString().toIntOrNull() ?: DEFAULT_MAX_TOKENS)
        editor.putFloat(KEY_TEMPERATURE, temperatureEdit.text.toString().toFloatOrNull() ?: DEFAULT_TEMPERATURE)
        editor.apply()

        statusText.text = "✅ 配置已保存！"
        statusText.setTextColor(0xFF00AA00.toInt())
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        val apiKey = apiKeyEdit.text.toString().trim()
        if (apiKey.isEmpty() || apiKey == "sk-xxxxxx") {
            statusText.text = "❌ 请先填写 API Key"
            statusText.setTextColor(0xFFFF0000.toInt())
            return
        }

        statusText.text = "⏳ 正在测试连接..."
        statusText.setTextColor(0xFF888888.toInt())
        saveBtn.isEnabled = false
        testBtn.isEnabled = false

        Thread {
            try {
                val testUrl = apiUrlEdit.text.toString().trim().ifEmpty { SettingsActivity.DEFAULT_API_URL }
                val testModel = modelEdit.text.toString().trim().ifEmpty { SettingsActivity.DEFAULT_MODEL }
                
                android.util.Log.d("AiChatTest", "Testing URL: $testUrl")
                android.util.Log.d("AiChatTest", "Testing Model: $testModel")
                android.util.Log.d("AiChatTest", "API Key length: ${apiKey.length}")
                
                val client = AiChatClient(
                    apiUrl = testUrl,
                    apiKey = apiKey,
                    model = testModel,
                    maxTokens = 100,
                    temperature = 0.1f
                )
                
                android.util.Log.d("AiChatTest", "AiChatClient created")
                
                val reply = client.send("你好")
                client.close()
                
                android.util.Log.d("AiChatTest", "Reply: $reply")

                runOnUiThread {
                    if (reply.startsWith("Error:")) {
                        statusText.text = "❌ 测试失败: $reply"
                        statusText.setTextColor(0xFFFF0000.toInt())
                    } else {
                        statusText.text = "✅ 连接成功！AI 回复: $reply"
                        statusText.setTextColor(0xFF00AA00.toInt())
                    }
                    saveBtn.isEnabled = true
                    testBtn.isEnabled = true
                }
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("AiChatTest", "Native lib not loaded", e)
                runOnUiThread {
                    statusText.text = "❌ Native 库未加载: ${e.message}"
                    statusText.setTextColor(0xFFFF0000.toInt())
                    saveBtn.isEnabled = true
                    testBtn.isEnabled = true
                }
            } catch (e: Exception) {
                android.util.Log.e("AiChatTest", "Test failed", e)
                runOnUiThread {
                    statusText.text = "❌ 异常: ${e::class.java.simpleName} - ${e.message}"
                    statusText.setTextColor(0xFFFF0000.toInt())
                    saveBtn.isEnabled = true
                    testBtn.isEnabled = true
                }
            }
        }.start()
    }
}
