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
import kotlinx.serialization.json.*

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var providerSpinner: Spinner
    private lateinit var baseUrlEdit: EditText
    private lateinit var apiKeyEdit: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var maxTokensEdit: EditText
    private lateinit var temperatureEdit: EditText
    private lateinit var saveBtn: Button
    private lateinit var testBtn: Button
    private lateinit var fetchModelsBtn: Button
    private lateinit var balanceText: TextView
    private lateinit var balanceLayout: LinearLayout
    private lateinit var statusText: TextView
    private var providerManager: ProviderManager? = null
    private var fetchedModels: List<ModelInfo> = emptyList()
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
                arrayOf("硅基流动", "OpenRouter", "DeepSeek", "自定义"))
            setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val provider = providerManager?.availableProviders?.getOrNull(position)
                        ?: LLMProvider.SiliconFlow
                    
                    val config = ProviderManager.getProviderConfig()
                    val defaultUrl = config.getBaseUrl(provider.name)
                    
                    // 更新 hint 和文本
                    baseUrlEdit.hint = defaultUrl
                    
                    // 如果当前输入框为空或是默认值，则更新为新的默认 URL
                    val currentText = baseUrlEdit.text.toString()
                    if (currentText.isEmpty() || prefs.getString("base_url", "")?.isEmpty() != false) {
                        baseUrlEdit.setText(defaultUrl)
                    }
                    
                    // 清空已获取的模型列表
                    fetchedModels = emptyList()
                    updateModelSpinner()
                    updateFetchButton()
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
            hint = ProviderManager.getProviderConfig().getBaseUrl("SiliconFlow")
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

        // 余额显示区域
        balanceLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0x10000000)
            visibility = View.GONE
            rootLayout.addView(this)
        }

        // 余额标题
        TextView(this).apply {
            text = "账户余额"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 4)
            balanceLayout.addView(this)
        }

        // 余额详细信息
        balanceText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(4, 4, 4, 4)
            balanceLayout.addView(this)
        }

        // 余额查询按钮
        val balanceBtn = Button(this).apply {
            text = "💰 查询余额"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { queryBalance() }
            rootLayout.addView(this)
        }

        // 模型选择
        TextView(this).apply {
            text = "模型:"
            textSize = 14f
            setPadding(0, 12, 0, 4)
            rootLayout.addView(this)
        }

        modelSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rootLayout.addView(this)
        }

        // 获取模型按钮
        fetchModelsBtn = Button(this).apply {
            text = "🔄 一键获取模型列表"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { fetchModels() }
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
            text = "提示: 点击一键获取模型列表从服务器获取可用模型\n硅基流动: cloud.siliconflow.cn\nOpenRouter: openrouter.ai"
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

        btnLayout.addView(saveBtn)
        btnLayout.addView(testBtn)
        rootLayout.addView(btnLayout)

        // 余额查询按钮已添加到上方

        // 状态文本
        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 12, 0, 0)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(statusText)

        setContentView(scrollView)
    }

    private fun loadSettings() {
        val provider = providerManager?.currentProvider ?: LLMProvider.SiliconFlow
        val providerIndex = providerManager?.availableProviders?.indexOf(provider) ?: 0
        providerSpinner.setSelection(providerIndex.coerceAtMost(providerManager?.availableProviders?.size?.minus(1) ?: 0))
        
        val config = ProviderManager.getProviderConfig()
        val defaultUrl = config.getBaseUrl(provider.name)
        
        // 加载保存的 URL 或使用默认 URL
        val savedUrl = prefs.getString("base_url", "") ?: ""
        baseUrlEdit.setText(savedUrl.ifEmpty { defaultUrl })
        baseUrlEdit.hint = defaultUrl
        
        apiKeyEdit.setText(providerManager?.apiKey ?: "")

        updateModelSpinner()
        updateFetchButton()
        
        // 根据供应商显示余额按钮
        val showBalance = provider == LLMProvider.SiliconFlow || provider == LLMProvider.OpenRouter
        balanceLayout.visibility = if (showBalance) View.VISIBLE else View.GONE
    }

    private fun updateModelSpinner() {
        if (fetchedModels.isEmpty()) {
            // 显示默认模型（从 Rust lib.rs 获取）
            val defaultModel = try {
                val client = AiChatClient("", "", "", 0, 0f)
                val json = client.nativeGetDefaultConfig()
                client.close()
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
                obj["model"]?.jsonPrimitive?.contentOrNull ?: ""
            } catch (e: Exception) {
                ""
            }
            
            val items = if (defaultModel.isNotEmpty()) arrayOf(defaultModel) else arrayOf("请先获取模型列表")
            modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
            if (defaultModel.isNotEmpty()) {
                modelSpinner.setSelection(0)
            }
        } else {
            // 显示获取到的模型
            val displayNames = fetchedModels.map { m ->
                m.display_name?.takeIf { it.isNotEmpty() } ?: m.id
            }
            modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayNames.toTypedArray())

            // 尝试选中当前保存的模型
            val savedModel = providerManager?.model ?: ""
            if (savedModel.isNotEmpty()) {
                val index = fetchedModels.indexOfFirst { it.id == savedModel }
                if (index >= 0) {
                    modelSpinner.setSelection(index)
                }
            }
        }
    }

    private fun updateFetchButton() {
        val provider = providerManager?.availableProviders?.getOrNull(providerSpinner.selectedItemPosition)
        fetchModelsBtn.isEnabled = true
        fetchModelsBtn.text = "🔄 一键获取模型列表"
    }

    private fun saveSettings() {
        val providerIndex = providerSpinner.selectedItemPosition
        val provider = providerManager?.availableProviders?.getOrNull(providerIndex) ?: LLMProvider.SiliconFlow

        // 获取选中的模型
        val selectedModel = if (fetchedModels.isNotEmpty() && modelSpinner.selectedItemPosition >= 0) {
            fetchedModels.getOrNull(modelSpinner.selectedItemPosition)?.id
                ?: modelSpinner.selectedItem?.toString() ?: ""
        } else {
            modelSpinner.selectedItem?.toString() ?: ""
        }

        providerManager?.currentProvider = provider
        providerManager?.baseUrl = baseUrlEdit.text.toString().trim()
        providerManager?.apiKey = apiKeyEdit.text.toString().trim()
        providerManager?.model = selectedModel

        // 保存到聊天设置
        val chatPrefs = getSharedPreferences("ai_chat_settings", Context.MODE_PRIVATE)
        chatPrefs.edit().apply {
            putString("api_url", "${providerManager?.baseUrl}${provider.chatPath}")
            putString("api_key", providerManager?.apiKey ?: "")
            putString("model", selectedModel)
            putInt("max_tokens", maxTokensEdit.text.toString().toIntOrNull() ?: 1024)
            putFloat("temperature", temperatureEdit.text.toString().toFloatOrNull() ?: 0.7f)
            apply()
        }

        Toast.makeText(this, "配置已保存 - ${provider.displayName}", Toast.LENGTH_SHORT).show()

        // 检查余额
        if (provider == LLMProvider.SiliconFlow || provider == LLMProvider.OpenRouter) {
            queryBalance()
        }
    }

    private fun testConnection() {
        val apiKey = apiKeyEdit.text.toString().trim()
        if (apiKey.isEmpty()) {
            showStatus("请先填写 API Key", false)
            return
        }

        testBtn.isEnabled = false
        testBtn.text = "⏳ 测试中..."

        scope.launch {
            val provider = providerManager?.availableProviders?.getOrNull(providerSpinner.selectedItemPosition) ?: LLMProvider.SiliconFlow
            val client = ProviderClient(
                provider = provider,
                apiKey = apiKey,
                baseUrl = baseUrlEdit.text.toString().trim().ifEmpty { ProviderManager.getProviderConfig().getBaseUrl(provider.name) }
            )

            val result = withContext(Dispatchers.IO) {
                client.testConnection()
            }
            client.close()

            runOnUiThread {
                showStatus(result.message, result.success)
                testBtn.isEnabled = true
                testBtn.text = "🔍 测试"
                if (result.success) {
                    val provider = providerManager?.availableProviders?.getOrNull(providerSpinner.selectedItemPosition)
                    if (provider == LLMProvider.SiliconFlow || provider == LLMProvider.OpenRouter) {
                        queryBalance()
                    }
                }
            }
        }
    }

    private fun fetchModels() {
        val apiKey = apiKeyEdit.text.toString().trim()
        if (apiKey.isEmpty()) {
            showStatus("请先填写 API Key", false)
            return
        }

        val providerIndex = providerSpinner.selectedItemPosition
        val provider = providerManager?.availableProviders?.getOrNull(providerIndex) ?: LLMProvider.SiliconFlow
        val baseUrl = baseUrlEdit.text.toString().trim().ifEmpty { ProviderManager.getProviderConfig().getBaseUrl(provider.name) }

        fetchModelsBtn.isEnabled = false
        fetchModelsBtn.text = "获取中..."
        showStatus("正在从 ${provider.displayName} 获取模型列表...", true)

        scope.launch {
            var models: List<ModelInfo> = emptyList()
            var errorMsg = ""

            val client = try {
                ProviderClient(
                    provider = provider,
                    apiKey = apiKey,
                    baseUrl = baseUrl
                )
            } catch (e: Exception) {
                errorMsg = "创建客户端失败: ${e.message}"
                
                null
            }

            if (client != null) {
                try {
                    models = client.listModels()
                    
                } catch (e: Exception) {
                    errorMsg = e.message ?: "未知错误"
                    
                }
                try { client.close() } catch (_: Exception) {}
            }

            fetchedModels = models
            updateModelSpinner()

            if (models.isNotEmpty()) {
                showStatus("获取到 ${models.size} 个模型", true)
                fetchModelsBtn.text = "已获取 ${models.size} 个模型 (点击刷新)"
            } else {
                showStatus("获取失败: ${errorMsg.ifEmpty { "请检查 API Key 和网络" }}", false)
                fetchModelsBtn.text = "一键获取模型列表"
            }
            fetchModelsBtn.isEnabled = true
        }
    }

    private fun queryBalance() {
        val apiKey = apiKeyEdit.text.toString().trim()
        if (apiKey.isEmpty()) {
            showStatus("请先填写 API Key", false)
            return
        }

        val provider = providerManager?.availableProviders?.getOrNull(providerSpinner.selectedItemPosition)
        if (provider != LLMProvider.SiliconFlow && provider != LLMProvider.OpenRouter) {
            showStatus("${provider?.displayName} 不支持余额查询", false)
            return
        }

        val baseUrl = baseUrlEdit.text.toString().trim().ifEmpty { ProviderManager.getProviderConfig().getBaseUrl(provider.name) }
        
        balanceLayout.visibility = View.VISIBLE
        balanceText.text = "查询中..."
        balanceText.setTextColor(0xFF888888.toInt())

        scope.launch {
            val client = try {
                ProviderClient(
                    provider = provider,
                    apiKey = apiKey,
                    baseUrl = baseUrl
                )
            } catch (e: Exception) {
                
                withContext(Dispatchers.Main) {
                    balanceText.text = "创建客户端失败: ${e.message}"
                    balanceText.setTextColor(0xFFFF0000.toInt())
                }
                return@launch
            }

            var balanceInfo: String = ""
            var isSuccess = false

            try {
                when (provider) {
                    LLMProvider.SiliconFlow -> {
                        val balance = withContext(Dispatchers.IO) {
                            client.getBalance()
                        }
                        if (balance != null) {
                            val detail = balance.balances.firstOrNull()
                            balanceInfo = if (detail != null) {
                                "总余额: ¥${detail.total_balance}\n" +
                                "赠送余额: ¥${detail.granted_balance}\n" +
                                "充值余额: ¥${detail.topped_up_balance}\n" +
                                "状态: ${if (balance.is_available) "可用" else "不可用"}"
                            } else {
                                "未获取到余额信息"
                            }
                        } else {
                            balanceInfo = "查询失败"
                        }
                    }
                    LLMProvider.OpenRouter -> {
                        val keyInfo = withContext(Dispatchers.IO) {
                            client.getKeyInfo()
                        }
                        if (keyInfo != null) {
                            val root = try {
                                kotlinx.serialization.json.Json.parseToJsonElement(keyInfo)
                            } catch (e: Exception) { null }
                            
                            val data = root?.jsonObject?.get("data")?.jsonObject
                            if (data != null) {
                                val usage = data["usage"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                                val limit = data["limit"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                                val limitRemaining = data["limit_remaining"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                                val isFreeTier = data["is_free_tier"]?.jsonPrimitive?.booleanOrNull ?: false
                                
                                balanceInfo = "已使用: $${String.format("%.2f", usage)}\n" +
                                    "总额度: $${String.format("%.2f", limit)}\n" +
                                    "剩余额度: $${String.format("%.2f", limitRemaining)}\n" +
                                    "免费用户: ${if (isFreeTier) "是" else "否"}"
                                
                            } else {
                                balanceInfo = "未获取到密钥信息"
                            }
                        } else {
                            balanceInfo = "查询失败"
                        }
                    }
                    else -> {
                        balanceInfo = "不支持此供应商的余额查询"
                    }
                }
                isSuccess = true
            } catch (e: Exception) {
                
                balanceInfo = "查询失败: ${e.message}"
                isSuccess = false
            } finally {
                try { client.close() } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) {
                balanceText.text = balanceInfo
                balanceText.setTextColor(if (isSuccess) 0xFF00AA00.toInt() else 0xFFFF0000.toInt())
                showStatus(if (isSuccess) "余额查询成功" else "余额查询失败", isSuccess)
            }
        }
    }

    private fun checkBalance() {
        // 此方法已废弃，使用 queryBalance 替代
        // 保留此方法仅为兼容性
        Toast.makeText(this, "请使用余额查询按钮", Toast.LENGTH_SHORT).show()
    }

    private fun showStatus(message: String, success: Boolean) {
        statusText.text = message
        statusText.setTextColor(if (success) 0xFF00AA00.toInt() else 0xFFFF0000.toInt())
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        providerManager?.close()
    }
}
