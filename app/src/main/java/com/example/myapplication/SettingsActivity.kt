package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var providerSpinner: Spinner
    private lateinit var baseUrlEdit: EditText
    private lateinit var apiKeyEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var maxTokensEdit: EditText
    private lateinit var temperatureEdit: EditText
    private lateinit var saveBtn: Button
    private lateinit var testBtn: Button
    private lateinit var balanceText: TextView
    private lateinit var modelsBtn: Button
    private var providerManager: ProviderManager? = null
    private val scope = MainScope()

    companion object {
        const val PREFS_NAME = "provider_settings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        providerManager = ProviderManager(this)
        buildUI()
        loadSettings()
    }

    private fun buildUI() {
        val scrollView = ScrollView(this).apply {
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
        scrollView.addView(rootLayout)

        // 标题
        TextView(this).apply {
            text = "🤖 AI 供应商配置"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
            rootLayout.addView(this)
        }

        // 供应商选择
        TextView(this).apply {
            text = "供应商:"
            textSize = 14f
            setPadding(0, 8, 0, 4)
            rootLayout.addView(this)
        }

        providerSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            adapter = ArrayAdapter(this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("硅基流动", "OpenRouter", "DeepSeek", "OpenAI", "自定义"))
            setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val provider = ProviderManager(this@SettingsActivity).availableProviders[position]
                    baseUrlEdit.hint = provider.defaultBaseUrl.ifEmpty { "https://your-api.com" }
                    modelEdit.hint = providerManager?.getDefaultModel(provider) ?: ""
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            })
            rootLayout.addView(this)
        }

        // API 地址
        TextView(this).apply {
            text = "API 地址:"
            textSize = 14f
            setPadding(0, 12, 0, 4)
            rootLayout.addView(this)
        }

        baseUrlEdit = EditText(this).apply {
            hint = "https://api.siliconflow.cn"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rootLayout.addView(this)
        }

        // API Key
        TextView(this).apply {
            text = "API Key:"
            textSize = 14f
            setPadding(0, 12, 0, 4)
            rootLayout.addView(this)
        }

        apiKeyEdit = EditText(this).apply {
            hint = "sk-xxxxxx"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            rootLayout.addView(this)
        }

        // 余额显示（仅硅基流动）
        balanceText = TextView(this).apply {
            textSize = 13f
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER
            visibility = View.GONE
            rootLayout.addView(this)
        }

        // 模型
        TextView(this).apply {
            text = "模型:"
            textSize = 14f
            setPadding(0, 12, 0, 4)
            rootLayout.addView(this)
        }

        modelEdit = EditText(this).apply {
            hint = "Qwen/Qwen2.5-7B-Instruct"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rootLayout.addView(this)
        }

        // Max Tokens
        TextView(this).apply {
            text = "最大 Tokens:"
            textSize = 14f
            setPadding(0, 12, 0, 4)
            rootLayout.addView(this)
        }

        maxTokensEdit = EditText(this).apply {
            setText("1024")
            hint = "1024"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            rootLayout.addView(this)
        }

        // Temperature
        TextView(this).apply {
            text = "Temperature (0.0-2.0):"
            textSize = 14f
            setPadding(0, 12, 0, 4)
            rootLayout.addView(this)
        }

        temperatureEdit = EditText(this).apply {
            setText("0.7")
            hint = "0.7"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            rootLayout.addView(this)
        }

        // 说明
        TextView(this).apply {
            text = "💡 硅基流动: cloud.siliconflow.cn\n💡 OpenRouter: openrouter.ai\n💡 DeepSeek: platform.deepseek.com"
            textSize = 12f
            setPadding(0, 12, 0, 12)
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
            text = "💾 保存"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveSettings() }
        }

        testBtn = Button(this).apply {
            text = "🔍 测试"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { testConnection() }
        }

        modelsBtn = Button(this).apply {
            text = "📋 模型"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            visibility = View.GONE
            setOnClickListener { fetchModels() }
        }

        btnLayout.addView(saveBtn)
        btnLayout.addView(testBtn)
        btnLayout.addView(modelsBtn)
        rootLayout.addView(btnLayout)

        // 状态文本
        val statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 12, 0, 0)
            gravity = Gravity.CENTER
            id = View.generateViewId()
        }
        rootLayout.addView(statusText)

        setContentView(scrollView)
    }

    private fun loadSettings() {
        val provider = providerManager?.currentProvider ?: LLMProvider.SiliconFlow
        providerSpinner.setSelection(providerManager?.availableProviders?.indexOf(provider) ?: 0)
        baseUrlEdit.setText(providerManager?.baseUrl ?: "")
        apiKeyEdit.setText(providerManager?.apiKey ?: "")
        modelEdit.setText(providerManager?.model ?: "")
    }

    private fun saveSettings() {
        val providerIndex = providerSpinner.selectedItemPosition
        val provider = providerManager?.availableProviders?.get(providerIndex) ?: LLMProvider.SiliconFlow

        providerManager?.currentProvider = provider
        providerManager?.baseUrl = baseUrlEdit.text.toString().trim()
        providerManager?.apiKey = apiKeyEdit.text.toString().trim()
        providerManager?.model = modelEdit.text.toString().trim()

        // 保存到聊天设置
        val chatPrefs = getSharedPreferences("ai_chat_settings", Context.MODE_PRIVATE)
        chatPrefs.edit().apply {
            putString("api_url", "${providerManager?.baseUrl}${provider.chatPath}")
            putString("api_key", providerManager?.apiKey ?: "")
            putString("model", providerManager?.model ?: "")
            putInt("max_tokens", maxTokensEdit.text.toString().toIntOrNull() ?: 1024)
            putFloat("temperature", temperatureEdit.text.toString().toFloatOrNull() ?: 0.7f)
            apply()
        }

        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()

        // 检查余额
        checkBalance()
    }

    private fun testConnection() {
        val apiKey = apiKeyEdit.text.toString().trim()
        if (apiKey.isEmpty()) {
            showToast("请先填写 API Key", false)
            return
        }

        testBtn.isEnabled = false
        testBtn.text = "⏳ 测试中..."

        scope.launch {
            val client = ProviderClient(
                provider = providerManager?.availableProviders?.get(providerSpinner.selectedItemPosition) ?: LLMProvider.SiliconFlow,
                apiKey = apiKey,
                baseUrl = baseUrlEdit.text.toString().trim().ifEmpty {
                    (providerManager?.availableProviders?.get(providerSpinner.selectedItemPosition) ?: LLMProvider.SiliconFlow).defaultBaseUrl
                }
            )

            val result = withContext(Dispatchers.IO) {
                client.testConnection()
            }
            client.close()

            runOnUiThread {
                showToast(result.message, result.success)
                testBtn.isEnabled = true
                testBtn.text = "🔍 测试"
                if (result.success) checkBalance()
            }
        }
    }

    private fun checkBalance() {
        val provider = providerManager?.availableProviders?.get(providerSpinner.selectedItemPosition)
        if (provider != LLMProvider.SiliconFlow) {
            balanceText.visibility = View.GONE
            return
        }

        val apiKey = apiKeyEdit.text.toString().trim()
        if (apiKey.isEmpty()) return

        balanceText.visibility = View.VISIBLE
        balanceText.text = "⏳ 正在查询余额..."
        balanceText.setTextColor(0xFF888888.toInt())

        scope.launch {
            val client = ProviderClient(
                provider = provider,
                apiKey = apiKey,
                baseUrl = baseUrlEdit.text.toString().trim().ifEmpty { provider.defaultBaseUrl }
            )

            val balance = withContext(Dispatchers.IO) {
                try { client.getBalance() } catch (_: Exception) { null }
            }
            client.close()

            runOnUiThread {
                if (balance != null) {
                    val detail = balance.balances.firstOrNull()
                    balanceText.text = if (detail != null) {
                        "💰 余额: ¥${detail.total_balance} (赠送: ¥${detail.granted_balance}, 充值: ¥${detail.topped_up_balance})"
                    } else {
                        "💰 余额查询失败"
                    }
                    balanceText.setTextColor(if (balance.is_available) 0xFF00AA00.toInt() else 0xFFFF0000.toInt())
                } else {
                    balanceText.text = "💰 余额查询失败"
                    balanceText.setTextColor(0xFFFF0000.toInt())
                }
            }
        }
    }

    private fun fetchModels() {
        val apiKey = apiKeyEdit.text.toString().trim()
        if (apiKey.isEmpty()) {
            showToast("请先填写 API Key", false)
            return
        }

        modelsBtn.isEnabled = false
        modelsBtn.text = "⏳ 获取中..."

        scope.launch {
            val provider = providerManager?.availableProviders?.get(providerSpinner.selectedItemPosition) ?: LLMProvider.SiliconFlow
            val client = ProviderClient(
                provider = provider,
                apiKey = apiKey,
                baseUrl = baseUrlEdit.text.toString().trim().ifEmpty { provider.defaultBaseUrl }
            )

            val models = withContext(Dispatchers.IO) {
                try { client.listModels() } catch (_: Exception) { emptyList() }
            }
            client.close()

            runOnUiThread {
                modelsBtn.isEnabled = true
                modelsBtn.text = "📋 模型"

                if (models.isNotEmpty()) {
                    val modelNames = models.map { it.display_name ?: it.id }.toTypedArray()
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("可用模型 (${models.size})")
                        .setItems(modelNames) { _, which ->
                            modelEdit.setText(models[which].id)
                        }
                        .setPositiveButton("关闭", null)
                        .show()
                } else {
                    showToast("获取模型列表失败", false)
                }
            }
        }
    }

    private fun showToast(message: String, success: Boolean) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        providerManager?.close()
    }
}
