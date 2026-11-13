package com.miloai

import android.app.Application
import com.miloai.core.chat.ChatRepository
import com.miloai.core.db.AppDatabase
import com.miloai.core.engine.ChatEngine
import kotlinx.coroutines.launch
import com.miloai.core.provider.ChatProvider
import com.miloai.core.model.ProviderId
import com.miloai.core.storage.SettingsRepository
import com.miloai.core.skills.SkillRepository
import com.miloai.providers.ProviderFactory
import kotlinx.coroutines.flow.first
import com.miloai.skills.DeviceTool
import com.miloai.skills.DeviceToolRunner
import com.miloai.skills.ToolContext
import com.miloai.skills.ToolRegistry
import com.miloai.skills.tools.CalculatorTool
import com.miloai.skills.tools.FetchRssTool
import com.miloai.skills.tools.GenerateQrTool
import com.miloai.skills.tools.GetExchangeRateTool
import com.miloai.skills.tools.GetWeatherTool
import com.miloai.skills.tools.PingNetworkTool
import com.miloai.skills.tools.GetTimeTool
import com.miloai.skills.tools.LoadSkillTool
import com.miloai.skills.tools.WebReadTool
import com.miloai.skills.tools.WebSearchTool
import com.miloai.skills.tools.UnitConvertTool

class MiloAiApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityResumed(activity: android.app.Activity) { container.setAppInForeground(true) }
            override fun onActivityPaused(activity: android.app.Activity) { container.setAppInForeground(false) }
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
        })
    }
}

class AppContainer(context: Application) {

    val appContext = context.applicationContext
    val themeVersion = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun bumpTheme() {
        themeVersion.value++
    }
    @Volatile
    var appInForeground = false
        private set

    fun setAppInForeground(value: Boolean) {
        appInForeground = value
    }
    var pendingShareText: String? = null
    var pendingShareImage: android.net.Uri? = null
    val shareNavTick = kotlinx.coroutines.flow.MutableStateFlow(0)
    val db = AppDatabase.get(context)
    val settings = SettingsRepository(context)

    private suspend fun ensureDefaultAgentFromLegacySettings() {
        if (agentRepository.count() > 0) return
        val p = settings.getDefaultProvider()
        val apiKey = settings.getApiKey(p)
        if (apiKey.isBlank()) return
        val entry = com.miloai.core.catalog.ModelCatalog.entryFor(p, settings.getModel(p))
        agentRepository.save(
            com.miloai.core.db.AgentEntity(
                name = "Agent Bawaan",
                description = "Dimigrasi otomatis dari konfigurasi lama",
                provider = p.name,
                baseUrl = settings.getBaseUrl(p),
                apiKey = apiKey,
                model = settings.getModel(p),
                temperature = settings.getTemperature(p),
                maxTokens = settings.getMaxTokens(p),
                reasoning = settings.getReasoning(p),
                systemPrompt = "",
                isDefault = true,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }
    val repository = ChatRepository(db)
    val agentRepository = com.miloai.core.chat.AgentRepository(db, com.miloai.core.storage.KeyStoreCrypto(context.applicationContext))
    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { ensureDefaultAgentFromLegacySettings() }
        }
    }
    val skillRepository = SkillRepository(context.applicationContext)

    private val toolContext = ToolContext(context.applicationContext)

    val tools: List<DeviceTool> = listOf(
        WebSearchTool(),
        WebReadTool(),
        UnitConvertTool(),
        LoadSkillTool { skillRepository.loadAll() },
        GetTimeTool(),
        FetchRssTool(),
        GetWeatherTool(),
        CalculatorTool(),
        GenerateQrTool(),
        GetExchangeRateTool(),
        PingNetworkTool()
    )
    val registry = ToolRegistry(tools)
    val runner = DeviceToolRunner(registry, toolContext)
    val providerFactory: (ProviderId) -> ChatProvider = { ProviderFactory.create(it) }
    val engine = ChatEngine(
        providerFactory,
        registry,
        runner
    )
    val speechPlayer = com.miloai.tools.SpeechPlayer(context.applicationContext)
}
