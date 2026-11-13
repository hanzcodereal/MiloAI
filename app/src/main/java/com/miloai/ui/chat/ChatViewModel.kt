package com.miloai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.miloai.AppContainer
import com.miloai.core.catalog.ModelCatalog
import com.miloai.core.chat.ChatRepository
import com.miloai.core.chat.repairToolTranscript
import com.miloai.core.db.MessageEntity
import com.miloai.core.engine.ChatEngine
import com.miloai.core.engine.EngineEvent
import com.miloai.core.mode.AppMode
import com.miloai.core.model.ChatMessage
import com.miloai.core.model.ChatRole
import com.miloai.core.model.ProviderId
import com.miloai.core.model.StreamEvent
import com.miloai.core.model.ToolCall
import com.miloai.core.model.ToolCallStatus
import com.miloai.core.storage.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class PendingAttachment(
    val id: Long,
    val name: String,
    val kind: String,
    val mimeType: String,
    val uri: android.net.Uri
)

data class UiMessage(
    val id: Long,
    val role: ChatRole,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val model: String? = null,
    val mode: AppMode? = null,
    val streaming: Boolean = false,
    val usageInput: Long = 0,
    val usageOutput: Long = 0,
    val attachments: List<com.miloai.core.model.Attachment> = emptyList(),
    val thinking: String = "",
    val starred: Boolean = false,
    val createdAt: Long = 0
)

data class ConfirmRequest(val call: ToolCall, val mode: AppMode)

data class ChatUiState(
    val conversationId: Long = -1,
    val title: String = "",
    val provider: ProviderId = ProviderId.OPENAI_COMPAT,
    val model: String = "",
    val agentId: Long? = null,
    val agentName: String = "",
    val mode: AppMode = AppMode.CHAT,
    val messages: List<UiMessage> = emptyList(),
    val isRunning: Boolean = false,
    val toolCallCount: Int = 0,
    val input: String = "",
    val confirmRequest: ConfirmRequest? = null,
    val error: String? = null,
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val attachmentError: String? = null,
    val processing: Boolean = false,
    val sendTick: Int = 0,
    val notification: String? = null
)

class ChatViewModel(
    private val conversationId: Long,
    private val repository: ChatRepository,
    private val settings: SettingsRepository,
    private val agentRepo: com.miloai.core.chat.AgentRepository,
    private val engine: ChatEngine,
    private val providerFactory: (ProviderId) -> com.miloai.core.provider.ChatProvider,
    private val appContext: android.content.Context
) : ViewModel() {

    private val db: com.miloai.core.db.AppDatabase
        get() = (appContext.applicationContext as com.miloai.MiloAiApp).container.db

    private val speechPlayer: com.miloai.tools.SpeechPlayer
        get() = (appContext.applicationContext as com.miloai.MiloAiApp).container.speechPlayer

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
    }

    private val _state = MutableStateFlow(ChatUiState())
    val state = _state.asStateFlow()

    private var dbMessages: List<UiMessage> = emptyList()
    @Volatile private var streaming: UiMessage? = null
    private var currentConversationId: Long = conversationId
    private var runJob: Job? = null
    private var streamTickerJob: Job? = null
    private var pendingAssistantEntity: MessageEntity? = null
    private var roundCounter = 0
    private var streamingRound = -1
    private var pendingRound = -1
    private var sendCancelled = false
    private var titleGenerated = false
    private var voiceInputHelper: com.miloai.tools.SpeechInputHelper? = null
    private var voiceListening = false
    private var toolCallRoundsThisRun = 0
    private var runToken = 0

    private suspend fun resolveConfig(s: ChatUiState): com.miloai.core.model.ProviderConfig {
        val agent = s.agentId?.let { runCatching { agentRepo.getById(it) }.getOrNull() }
            ?: runCatching { agentRepo.getDefault() }.getOrNull()
        return if (agent != null) {
            agentRepo.toConfig(agent)
        } else {
            settings.configFor(s.provider)
        }
    }

    private fun appInForeground(): Boolean =
        (appContext.applicationContext as com.miloai.MiloAiApp).container.appInForeground

    private fun sendTaskDoneNotification() {
        val container = (appContext.applicationContext as com.miloai.MiloAiApp).container
        val nm = appContext.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                "miloai_task", appContext.getString(com.miloai.R.string.vm_task_channel), android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val notification = android.app.Notification.Builder(appContext, "miloai_task")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Milo AI")
            .setContentText(appContext.getString(com.miloai.R.string.vm_task_done, _state.value.title))
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    init {
        viewModelScope.launch {
            engine.confirmRequests.collect { call ->
                if (runJob?.isActive == true) {
                    _state.update { it.copy(confirmRequest = ConfirmRequest(call, it.mode)) }
                }
            }
        }
        if (conversationId > 0) {
            loadConversation(conversationId)
        } else {
            viewModelScope.launch {
                val def = runCatching { agentRepo.getDefault() }.getOrNull()
                val provider = settings.getDefaultProvider()
                _state.update {
                    it.copy(
                        provider = def?.let { runCatching { ProviderId.valueOf(it.provider) }.getOrDefault(provider) } ?: provider,
                        model = def?.model ?: settings.getModel(provider),
                        agentId = def?.id,
                        agentName = def?.name ?: "",
                        mode = settings.getDefaultMode()
                    )
                }
                consumePendingShare()
            }
        }
    }

    private fun consumePendingShare() {
        val container = (appContext.applicationContext as com.miloai.MiloAiApp).container
        val text = container.pendingShareText
        val image = container.pendingShareImage
        if (text == null && image == null) return
        container.pendingShareText = null
        container.pendingShareImage = null
        _state.update {
            it.copy(
                input = text ?: "",
                pendingAttachments = it.pendingAttachments + if (image != null) {
                    listOf(
                        PendingAttachment(
                            id = ++attachmentCounter,
                            name = AttachmentProcessor.queryNameFromResolver(appContext.contentResolver, image),
                            kind = "image",
                            mimeType = appContext.contentResolver.getType(image) ?: "image/*",
                            uri = image
                        )
                    )
                } else emptyList()
            )
        }
    }

    private fun loadConversation(id: Long) {
        viewModelScope.launch {
            val c = repository.getConversation(id)
            if (c == null) {
                currentConversationId = -1
                _state.update {
                    it.copy(
                        conversationId = -1,
                        title = appContext.getString(com.miloai.R.string.chat_new_chat),
                        provider = settings.getDefaultProvider()
                    )
                }
                val provider = settings.getDefaultProvider()
                _state.update {
                    it.copy(
                        model = settings.getModel(provider),
                        mode = settings.getDefaultMode()
                    )
                }
                consumePendingShare()
                return@launch
            }
            val provider = runCatching { ProviderId.valueOf(c.provider) }.getOrDefault(ProviderId.OPENAI_COMPAT)
            val mode = runCatching { AppMode.valueOf(c.mode) }.getOrDefault(AppMode.CHAT)
            var agentId = c.agentId
            var agentName = ""
            if (agentId != null) {
                val agent = runCatching { agentRepo.getById(agentId) }.getOrNull()
                if (agent == null) agentId = null
                else agentName = agent.name
            }
            _state.update {
                it.copy(
                    conversationId = id,
                    title = c.title,
                    provider = provider,
                    model = c.model,
                    agentId = agentId,
                    agentName = agentName,
                    mode = mode
                )
            }
            startObserving(id)
        }
    }

    private fun startObserving(id: Long) {
        viewModelScope.launch {
            repository.observeMessages(id).collect { list ->
                dbMessages = list.map { e ->
                    UiMessage(
                        id = e.id,
                        role = runCatching { ChatRole.valueOf(e.role) }.getOrDefault(ChatRole.USER),
                        content = e.content,
                        toolCalls = e.toolCallsJson?.let {
                            runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrDefault(emptyList())
                        } ?: emptyList(),
                        model = e.model,
                        mode = e.mode?.let { runCatching { AppMode.valueOf(it) }.getOrNull() },
                        streaming = false,
                        usageInput = e.usageInput,
                        usageOutput = e.usageOutput,
                        thinking = e.thinkingText ?: "",
                        attachments = e.attachmentsJson?.let {
                            runCatching { json.decodeFromString<List<com.miloai.core.model.Attachment>>(it) }
                                .getOrDefault(emptyList())
                        } ?: emptyList(),
                        starred = e.starred,
                        createdAt = e.createdAt
                    )
                }
                refresh()
            }
        }
    }

    private fun refresh() {
        _state.update { it.copy(messages = dbMessages + listOfNotNull(streaming)) }
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text) }
    }

    private var attachmentCounter = 0L

    fun addPendingImages(uris: List<android.net.Uri>, resolver: android.content.ContentResolver) {
        val items = uris.map { uri ->
            val name = AttachmentProcessor.queryNameFromResolver(resolver, uri)
            attachmentCounter++
            PendingAttachment(attachmentCounter, name, "image", resolver.getType(uri) ?: "image/*", uri)
        }
        _state.update { it.copy(pendingAttachments = it.pendingAttachments + items, attachmentError = null) }
    }

    fun addPendingFile(uri: android.net.Uri, resolver: android.content.ContentResolver) {
        val name = AttachmentProcessor.queryNameFromResolver(resolver, uri)
        attachmentCounter++
        _state.update {
            it.copy(
                pendingAttachments = it.pendingAttachments + PendingAttachment(
                    attachmentCounter, name, "file", resolver.getType(uri) ?: "*/*", uri
                ),
                attachmentError = null
            )
        }
    }

    fun removePendingAttachment(id: Long) {
        _state.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { a -> a.id == id }) }
    }

    private fun speakAndListen(text: String) {
        stopVoiceInput()
        speechPlayer.speakWithCallback(text) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { startVoiceInput() }
        }
    }

    private fun startVoiceInput() {
        if (!settings.getVoiceAssistant() || voiceListening || _state.value.isRunning) return
        voiceListening = true
        val helper = voiceInputHelper ?: com.miloai.tools.SpeechInputHelper(appContext).also {
            voiceInputHelper = it
        }
        _state.update { it.copy(notification = appContext.getString(com.miloai.R.string.input_listening)) }
        helper.start(
            onPartial = { _state.update { st -> st.copy(input = it) } },
            onFinal = { text ->
                voiceListening = false
                _state.update { it.copy(input = text) }
                if (text.isNotBlank()) {
                    send()
                }
            },
            onError = {
                voiceListening = false
                _state.update { it.copy(notification = appContext.getString(com.miloai.R.string.vm_speech_failed, it)) }
            }
        )
    }

    private fun stopVoiceInput() {
        if (voiceListening) {
            voiceInputHelper?.stop()
            voiceListening = false
        }
    }

    override fun onCleared() {
        runCatching { voiceInputHelper?.destroy() }
        voiceInputHelper = null
        super.onCleared()
    }

    fun send() {
        val text = _state.value.input.trim()
        val pending = _state.value.pendingAttachments
        stopVoiceInput()
        if ((text.isEmpty() && pending.isEmpty()) || _state.value.isRunning) return
        sendCancelled = false
        _state.update { it.copy(isRunning = true) }
        viewModelScope.launch {
            val attachments = processPending(pending)
            if (sendCancelled) {
                sendCancelled = false
                _state.update { it.copy(isRunning = false, pendingAttachments = emptyList()) }
                return@launch
            }
            if (attachments.isEmpty() && text.isEmpty()) {
                _state.update { it.copy(isRunning = false, pendingAttachments = emptyList()) }
                return@launch
            }
            if (attachments.isNotEmpty() && text.isEmpty()) {
                _state.update { it.copy(attachmentError = null) }
            }
            sendWithContent(text, attachments)
        }
    }

    private suspend fun processPending(pending: List<PendingAttachment>): List<com.miloai.core.model.Attachment> {
        if (pending.isEmpty()) return emptyList()
        val context = appContext ?: return emptyList()
        _state.update { it.copy(processing = true) }
        try {
            val result = mutableListOf<com.miloai.core.model.Attachment>()
            for (p in pending) {
                val r = if (p.kind == "image") {
                    AttachmentProcessor.imageFromUri(context, p.uri, p.name)
                } else {
                    AttachmentProcessor.docFromUri(context, p.uri, p.name)
                }
                r.onSuccess { result.add(it) }
                    .onFailure { _state.update { st -> st.copy(attachmentError = it.message) } }
            }
            return result
        } finally {
            _state.update { it.copy(processing = false) }
        }
    }

    private fun newStreamingMessage(config: com.miloai.core.model.ProviderConfig, s: ChatUiState): UiMessage {
        revealBuffer.setLength(0)
        streamingRound = roundCounter
        return UiMessage(
            id = -1, role = ChatRole.ASSISTANT, content = "",
            model = config.model, mode = s.mode, streaming = true
        )
    }

    private var autoDistillCount = 0
    private var needAutoCompress = false

    // Efek mengetik: teks dari AI ditampung dulu, lalu ditampilkan sedikit demi sedikit
    private val revealBuffer = StringBuilder()

    private fun revealStep() {
        if (revealBuffer.isEmpty()) return
        val backlog = revealBuffer.length
        // Kecepatan stabil seperti mengetik; hanya sedikit mempercepat jika antrean sangat panjang
        val n = minOf(backlog, 2 + (backlog / 80).coerceAtMost(10))
        val chunk = revealBuffer.substring(0, n)
        revealBuffer.delete(0, n)
        streaming = streaming?.copy(content = (streaming?.content ?: "") + chunk)
    }

    private fun flushReveal() {
        if (revealBuffer.isEmpty()) return
        val rest = revealBuffer.toString()
        revealBuffer.setLength(0)
        streaming = streaming?.copy(content = (streaming?.content ?: "") + rest)
    }

    private suspend fun drainReveal(token: Int) {
        while (revealBuffer.isNotEmpty() && token == runToken) delay(20)
    }

    private fun runGeneration(cid: Long) {
        if (runJob?.isActive == true) return
        _state.update { it.copy(isRunning = true, toolCallCount = 0) }
        toolCallRoundsThisRun = 0
        val myToken = ++runToken
        var currentJob: Job? = null
        currentJob = viewModelScope.launch {
            val s = _state.value
            val config = resolveConfig(s)
            if (config.apiKey.isBlank() && !isLocalEndpoint(config.baseUrl)) {
                _state.update {
                    it.copy(error = appContext.getString(com.miloai.R.string.err_no_key), isRunning = false)
                }
                return@launch
            }
            streaming = newStreamingMessage(config, s)
            refresh()
            var myTicker: Job? = null
            try {
                myTicker = viewModelScope.launch {
                    while (true) {
                        delay(30)
                        if (streaming != null) {
                            revealStep()
                            refresh()
                        }
                    }
                }
                streamTickerJob = myTicker
                val history = repairToolTranscript(repository.getHistory(cid).map { repository.messageToDomain(it) })
                val historyWithMemory = injectMemory(history, cid)
                val toolLimit = settings.getToolCallLimit()
                engine.run(
                    historyWithMemory,
                    config,
                    s.mode,
                    toolLimit,
                    appContext.getString(com.miloai.R.string.tool_budget_summary, toolLimit)
                ).collect { ev ->
                    if (myToken != runToken) return@collect
                    when (ev) {
                        is EngineEvent.Delta -> {
                            if (streaming == null) {
                                streaming = newStreamingMessage(config, s)
                            }
                            revealBuffer.append(ev.text)
                        }
                        is EngineEvent.ThinkingDelta -> {
                            if (streaming == null) {
                                streaming = newStreamingMessage(config, s)
                            }
                            streaming = streaming?.copy(thinking = (streaming?.thinking ?: "") + ev.text)
                        }
                        is EngineEvent.ToolCallStarted -> {
                            if (streaming == null) {
                                streaming = newStreamingMessage(config, s)
                            }
                            streaming = streaming?.copy(
                                toolCalls = upsertCall(
                                    streaming?.toolCalls ?: emptyList(),
                                    ev.call.copy(status = ToolCallStatus.RUNNING)
                                )
                            )
                        }
                        is EngineEvent.ToolCallFinished -> {
                            if (streaming == null) {
                                streaming = newStreamingMessage(config, s)
                            }
                            toolCallRoundsThisRun++
                            _state.update { it.copy(toolCallCount = it.toolCallCount + 1) }
                            streaming = streaming?.copy(
                                toolCalls = upsertCall(streaming?.toolCalls ?: emptyList(), ev.call)
                            )
                            persistToolResult(ev.call, cid)
                            refresh()
                        }
                        is EngineEvent.Usage -> {
                            if (streaming == null) {
                                streaming = newStreamingMessage(config, s)
                            }
                            streaming = streaming?.copy(usageInput = ev.promptTokens, usageOutput = ev.completionTokens)
                            val ctx = ModelCatalog.entryFor(config.provider, config.model).contextWindow
                            if (ctx > 0 && ev.promptTokens * 100 / ctx >= 85) {
                                needAutoCompress = true
                                _state.update {
                                    it.copy(notification = appContext.getString(com.miloai.R.string.vm_ctx_usage, (ev.promptTokens * 100 / ctx).toInt()))
                                }
                            }
                        }
                        is EngineEvent.AssistantFinished -> {
                            drainReveal(myToken)
                            if (myToken != runToken) return@collect
                            val msg = ev.message
                            val entity = repository.domainToMessage(
                                ChatMessage(
                                    role = ChatRole.ASSISTANT,
                                    content = msg.content,
                                    toolCalls = msg.toolCalls.map { it.copy(status = ToolCallStatus.PENDING) },
                                    model = config.model,
                                    mode = s.mode,
                                    thinkingText = msg.thinkingText,
                                    thinkingSignature = msg.thinkingSignature
                                ),
                                cid
                            ).copy(
                                usageInput = streaming?.usageInput ?: 0,
                                usageOutput = streaming?.usageOutput ?: 0
                            )
                            val id = repository.insertMessage(entity)
                            pendingAssistantEntity = entity.copy(id = id)
                            pendingRound = roundCounter
                            roundCounter++
                            streaming = null
                            refresh()
                            if (settings.getAutoSpeak() && msg.content.isNotBlank()) {
                                speechPlayer.speak(msg.content)
                            }
                            if (settings.getVoiceAssistant() && msg.content.isNotBlank()) {
                                speakAndListen(msg.content)
                            }
                        }
                        is EngineEvent.Failed -> {
                            _state.update { it.copy(error = smartError(ev.message)) }
                            flushReveal()
                            persistStreamingPartial(cid)
                            streaming = null
                            refresh()
                        }
                        is EngineEvent.ConfirmRequested -> Unit
                        EngineEvent.Completed -> {
                            if (toolCallRoundsThisRun > 0 && !appInForeground()) {
                                sendTaskDoneNotification()
                            }
                            if (!titleGenerated && cid == currentConversationId &&
                                repository.getHistory(cid).size >= 3
                            ) {
                                titleGenerated = true
                                launch { generateTitle(cid) }
                            }
                            autoDistillCount++
                            if (autoDistillCount >= 10) {
                                autoDistillCount = 0
                                launch { distillMemoryInternal(cid, silent = true) }
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                revealBuffer.setLength(0)
                streaming = null
                _state.update { it.copy(error = smartError(e.message ?: appContext.getString(com.miloai.R.string.vm_gen_failed))) }
                refresh()
            } finally {
                if (streamTickerJob === myTicker) {
                    streamTickerJob?.cancel()
                    streamTickerJob = null
                }
                refresh()
                if (runJob === currentJob) {
                    _state.update { it.copy(isRunning = false) }
                    if (needAutoCompress) {
                        needAutoCompress = false
                        launch { compressContext() }
                    }
                }
            }
        }
        runJob = currentJob
    }

    private fun isLocalEndpoint(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("localhost") || u.contains("127.0.0.1") || u.contains("10.0.2.2") ||
            u.contains("//192.168.") || u.contains("//10.") || u.contains("//172.")
    }

    private fun smartError(raw: String): String {
        val str = { id: Int -> appContext.getString(id) }
        return when {
            raw.contains("api key not valid", ignoreCase = true) ||
                raw.contains("api_key_invalid", ignoreCase = true) ||
                raw.contains("invalid_api_key", ignoreCase = true) ||
                raw.contains("incorrect api key", ignoreCase = true) ||
                raw.contains("invalid api key", ignoreCase = true) ||
                raw.contains("invalid x-api-key", ignoreCase = true) ||
                raw.contains("unauthorized", ignoreCase = true) ->
                str(com.miloai.R.string.err_401)
            raw.contains("insufficient_quota", ignoreCase = true) ||
                raw.contains("quota", ignoreCase = true) && !raw.contains("429") ->
                str(com.miloai.R.string.err_429)
            raw.contains("image", ignoreCase = true) &&
                (raw.contains("not support", ignoreCase = true) ||
                    raw.contains("unsupported", ignoreCase = true) ||
                    raw.contains("does not", ignoreCase = true) ||
                    raw.contains("cannot process", ignoreCase = true)) ->
                str(com.miloai.R.string.err_image_unsupported)
            raw.contains("401") -> str(com.miloai.R.string.err_401)
            raw.contains("403") -> str(com.miloai.R.string.err_403)
            raw.contains("404") -> str(com.miloai.R.string.err_404)
            raw.contains("400") && raw.contains("reasoning_effort", ignoreCase = true) ->
                str(com.miloai.R.string.err_400_reasoning)
            raw.contains("429") -> str(com.miloai.R.string.err_429)
            raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
                str(com.miloai.R.string.err_timeout)
            raw.contains("failed to connect", ignoreCase = true) || raw.contains("connect timed out", ignoreCase = true) ->
                str(com.miloai.R.string.err_connect)
            raw.contains("cancelled", ignoreCase = true) -> str(com.miloai.R.string.err_cancelled)
            else -> raw
        }
    }

    private fun upsertCall(calls: List<ToolCall>, updated: ToolCall): List<ToolCall> {
        val idx = calls.indexOfFirst { it.id == updated.id }
        return if (idx >= 0) calls.toMutableList().apply { this[idx] = updated } else calls + updated
    }

    private suspend fun persistToolResult(call: ToolCall, cid: Long) {
        val stored = call.copy(result = call.result?.take(6000))
        repository.insertMessage(
            repository.domainToMessage(
                ChatMessage(
                    role = ChatRole.TOOL,
                    content = stored.result ?: "",
                    toolCallId = stored.id,
                    toolName = stored.name
                ),
                cid
            )
        )
        pendingAssistantEntity = pendingAssistantEntity?.copy(
            toolCallsJson = runCatching {
                json.encodeToString(
                    ListSerializer(ToolCall.serializer()),
                    (pendingAssistantEntity?.toolCallsJson?.let {
                        runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrDefault(emptyList())
                    } ?: emptyList())
                        .map { c -> if (c.id == stored.id) stored else c }
                )
            }.getOrNull()
        )
        pendingAssistantEntity?.let { repository.updateMessage(it) }
    }

    private suspend fun persistStreamingPartial(cid: Long) {
        val st = streaming ?: return
        if (st.content.isBlank()) return
        val sameRound = pendingAssistantEntity != null &&
            pendingAssistantEntity?.conversationId == cid &&
            pendingRound == streamingRound
        if (sameRound) {
            val entity = pendingAssistantEntity!!
            val mergedCalls = runCatching {
                json.decodeFromString<List<ToolCall>>(entity.toolCallsJson ?: "[]")
            }.getOrDefault(emptyList())
            repository.updateMessage(
                entity.copy(
                    content = st.content,
                    toolCallsJson = runCatching {
                        json.encodeToString(ListSerializer(ToolCall.serializer()), mergedCalls)
                    }.getOrNull()
                )
            )
        } else {
            repository.insertMessage(
                repository.domainToMessage(
                    ChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = st.content,
                        toolCalls = st.toolCalls,
                        model = st.model,
                        mode = st.mode,
                        thinkingText = st.thinking.ifBlank { null }
                    ),
                    cid
                )
            )
        }
    }

    fun stop() {
        sendCancelled = true
        runToken++
        runJob?.cancel()
        runJob = null
        streamTickerJob?.cancel()
        streamTickerJob = null
        _state.update { it.copy(confirmRequest = null, isRunning = false) }
        flushReveal()
        viewModelScope.launch {
            failPendingToolCalls()
            persistStreamingPartial(currentConversationId)
            streaming = null
            refresh()
        }
    }

    private suspend fun failPendingToolCalls() {
        val entity = pendingAssistantEntity ?: return
        val calls = entity.toolCallsJson?.let {
            runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()
        val pending = calls.filter { it.status == ToolCallStatus.PENDING }
        if (pending.isEmpty()) return
        val cancelled = calls.map { c ->
            if (c.status == ToolCallStatus.PENDING) {
                c.copy(status = ToolCallStatus.REJECTED, result = "Dibatalkan")
            } else c
        }
        repository.updateMessage(
            entity.copy(toolCallsJson = runCatching {
                json.encodeToString(ListSerializer(ToolCall.serializer()), cancelled)
            }.getOrNull())
        )
        pending.forEach { call ->
            repository.insertMessage(
                repository.domainToMessage(
                    ChatMessage(
                        role = ChatRole.TOOL,
                        content = "Dibatalkan",
                        toolCallId = call.id,
                        toolName = call.name
                    ),
                    entity.conversationId
                )
            )
        }
        pendingAssistantEntity = null
    }

    fun clearContext() {
        if (currentConversationId <= 0) return
        viewModelScope.launch {
            runToken++
            runJob?.cancel()
            runJob = null
            streamTickerJob?.cancel()
            streamTickerJob = null
            streaming = null
            pendingAssistantEntity = null
            _state.update { it.copy(confirmRequest = null) }
            repository.clearMessages(currentConversationId)
            _state.update { it.copy(isRunning = false, error = null) }
            refresh()
        }
    }

    fun deleteMessage(messageId: Long) {
        if (messageId <= 0) return
        viewModelScope.launch {
            val cid = currentConversationId
            if (pendingAssistantEntity?.id == messageId) {
                pendingAssistantEntity = null
                pendingRound = -1
            }
            val target = dbMessages.firstOrNull { it.id == messageId }
            if (target != null && target.role == ChatRole.ASSISTANT && target.toolCalls.isNotEmpty()) {
                repository.deleteToolMessages(cid, target.toolCalls.map { it.id })
            }
            repository.deleteMessage(messageId)
            refresh()
        }
    }

    private suspend fun injectMemory(
        history: List<com.miloai.core.model.ChatMessage>,
        cid: Long
    ): List<com.miloai.core.model.ChatMessage> {
        val memories = runCatching {
            db.memoryDao().getMemories(cid)
        }.getOrDefault(emptyList())
        if (memories.isEmpty()) return history
        val text = memories.joinToString("\n") { "- ${it.content}" }
        return listOf(
            com.miloai.core.model.ChatMessage(
                role = ChatRole.SYSTEM,
                content = "Berikut informasi penting yang disimpan pengguna dalam jangka panjang di percakapan ini, rujuk saat menjawab:\n$text"
            )
        ) + history
    }

    fun distillMemory() {
        if (currentConversationId <= 0 || _state.value.isRunning) return
        viewModelScope.launch {
            distillMemoryInternal(currentConversationId, silent = false)
        }
    }

    private suspend fun distillMemoryInternal(cid: Long, silent: Boolean) {
        if (cid <= 0) return
        runCatching {
            val history = repository.getHistory(cid).takeLast(30)
            if (history.isEmpty()) return@runCatching
            if (!silent) _state.update { it.copy(processing = true) }
            try {
                val config = resolveConfig(_state.value)
                val provider = providerFactory(config.provider)
                val existing = runCatching { db.memoryDao().getMemories(cid) }
                    .getOrDefault(emptyList())
                    .joinToString("\n") { "- ${it.content}" }
                val sys = ChatMessage(
                    role = ChatRole.SYSTEM,
                    content = "Kamu adalah asisten perapi memori. Ekstrak informasi penting pengguna dari percakapan (nama, identitas, preferensi, kebiasaan, kesepakatan penting, fakta kunci), keluarkan sebagai daftar poin ringkas, satu baris satu poin, tanpa basa-basi. Memori yang sudah ada:\n${existing.ifBlank { "(tidak ada)" }}\nHanya keluarkan poin baru, jangan ulangi isi yang sudah ada."
                )
                val sb = StringBuilder()
                provider.chatStream(
                    listOf(sys) + history.map { repository.messageToDomain(it) },
                    config,
                    emptyList()
                ).flowOn(kotlinx.coroutines.Dispatchers.IO).collect { ev ->
                    when (ev) {
                        is StreamEvent.Delta -> sb.append(ev.text)
                        is StreamEvent.Error -> throw IllegalStateException(ev.message)
                        else -> Unit
                    }
                }
                val lines = sb.toString().lines()
                    .map { it.trim().removePrefix("-").removePrefix("*").trim() }
                    .filter { it.length >= 3 && it.length <= 200 && !it.startsWith("Memori yang sudah ada") && !it.startsWith("(tidak ada)") && !it.startsWith("Hanya keluarkan") }
                if (lines.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    lines.forEach { line ->
                        db.memoryDao().insert(
                            com.miloai.core.db.MemoryEntity(
                                conversationId = cid,
                                type = "memory",
                                content = line,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    }
                    if (!silent) {
                        _state.update { it.copy(notification = appContext.getString(com.miloai.R.string.vm_memory_distilled, lines.size)) }
                    }
                } else if (!silent) {
                    _state.update { it.copy(notification = appContext.getString(com.miloai.R.string.vm_memory_none)) }
                }
            } finally {
                if (!silent) _state.update { it.copy(processing = false) }
            }
        }.onFailure { e ->
            if (!silent) {
                _state.update { it.copy(notification = appContext.getString(com.miloai.R.string.vm_memory_failed, e.message ?: appContext.getString(com.miloai.R.string.unknown))) }
            }
        }
    }

    fun importRecentConversation() {
        if (currentConversationId <= 0) return
        viewModelScope.launch {
            val cid = currentConversationId
            val snap = runCatching { db.memoryDao().getLatestSnapshot(cid) }.getOrNull()
                ?: run {
                    _state.update { it.copy(notification = appContext.getString(com.miloai.R.string.vm_no_snapshot)) }
                    return@launch
                }
            val s = _state.value
            repository.insertMessage(
                repository.domainToMessage(
                    ChatMessage(
                        role = ChatRole.USER,
                        content = "Berikut catatan percakapan sebelumnya (konteks pernah dikompres karena terlalu panjang), lanjutkan berdasarkan isi ini, dan ekstrak informasi pentingnya:\n\n${snap.content}",
                        model = s.model,
                        mode = s.mode
                    ),
                    cid
                )
            )
            db.memoryDao().delete(snap.id)
            refresh()
            _state.update { it.copy(notification = appContext.getString(com.miloai.R.string.vm_snapshot_imported)) }
        }
    }

    fun compressContext(level: Int = 2) {
        if (currentConversationId <= 0 || _state.value.isRunning) return
        viewModelScope.launch {
            val cid = currentConversationId
            val history = repository.getHistory(cid)
            val minKeep = if (level == 1) 6 else 0
            if (history.size < minKeep + 2) {
                _state.update { it.copy(error = appContext.getString(com.miloai.R.string.vm_compress_too_few)) }
                return@launch
            }
            _state.update { it.copy(processing = true) }
            try {
                val keepCount = when (level) {
                    1 -> 6
                    3 -> 0
                    else -> 2
                }
                val toSummarize = history.dropLast(keepCount)
                if (toSummarize.isEmpty()) {
                    _state.update { it.copy(processing = false, error = appContext.getString(com.miloai.R.string.vm_compress_too_few)) }
                    return@launch
                }
                val snapshotText = history.takeLast(6)
                    .map { m ->
                        val role = if (m.role == "USER") "Pengguna" else "AI"
                        "$role: ${m.content.take(400)}"
                    }
                    .joinToString("\n\n")
                if (snapshotText.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    db.memoryDao().insert(
                        com.miloai.core.db.MemoryEntity(
                            conversationId = cid,
                            type = "snapshot",
                            content = snapshotText,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
                val config = resolveConfig(_state.value)
                val summary = summarize(toSummarize, config)
                if (summary.isBlank()) {
                    _state.update { it.copy(processing = false, error = appContext.getString(com.miloai.R.string.vm_compress_no_summary)) }
                    return@launch
                }
                repository.deleteMessagesRange(
                    cid, toSummarize.first().id, toSummarize.last().id
                )
                repository.insertMessage(
                    repository.domainToMessage(
                        ChatMessage(
                            role = ChatRole.USER,
                            content = "Berikut ringkasan percakapan sebelumnya, lanjutkan percakapan berdasarkan ini:\n\n$summary",
                            model = _state.value.model,
                            mode = _state.value.mode
                        ),
                        cid
                    )
                )
                _state.update {
                    it.copy(
                        processing = false,
                        error = null,
                        notification = appContext.getString(com.miloai.R.string.vm_compress_done)
                    )
                }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(processing = false, error = appContext.getString(com.miloai.R.string.vm_compress_failed, e.message ?: "")) }
            }
        }
    }

    private suspend fun summarize(
        history: List<MessageEntity>,
        config: com.miloai.core.model.ProviderConfig
    ): String {
        val provider = providerFactory(config.provider)
        val sys = ChatMessage(
            role = ChatRole.SYSTEM,
            content = "Rangkum isi penting percakapan berikut dengan bahasa Indonesia yang ringkas (pertahankan fakta penting, kebutuhan pengguna, dan kesimpulan), maksimal 100 kata, tanpa basa-basi."
        )
        val sb = StringBuilder()
        val messages = listOf(sys) + history.map { repository.messageToDomain(it) }
        provider.chatStream(messages, config, emptyList()).collect { ev ->
            when (ev) {
                is StreamEvent.Delta -> sb.append(ev.text)
                is StreamEvent.Error -> throw IllegalStateException(ev.message)
                else -> Unit
            }
        }
        return sb.toString().trim()
    }

    fun editAndResend(messageId: Long, newText: String) {
        val text = newText.trim()
        if (text.isEmpty() || currentConversationId <= 0 || _state.value.isRunning) return
        viewModelScope.launch {
            val cid = currentConversationId
            runToken++
            runJob?.cancel()
            runJob = null
            streamTickerJob?.cancel()
            streamTickerJob = null
            pendingAssistantEntity = null
            _state.update { it.copy(confirmRequest = null) }
            repository.deleteMessagesFrom(cid, messageId)
            repository.insertMessage(
                repository.domainToMessage(
                    ChatMessage(role = ChatRole.USER, content = text, model = _state.value.model, mode = _state.value.mode),
                    cid
                )
            )
            _state.update {
                it.copy(input = "", error = null, sendTick = it.sendTick + 1)
            }
            runGeneration(cid)
        }
    }

    fun buildExportText(): String {
        val sb = StringBuilder()
        sb.appendLine("# ${_state.value.title}")
        sb.appendLine()
        sb.appendLine("> Diekspor dari Milo AI · dapat diimpor ke alat obrolan AI mana pun untuk dilanjutkan")
        sb.appendLine()
        val timeFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        dbMessages.forEach { msg ->
            val time = if (msg.createdAt > 0) timeFmt.format(java.util.Date(msg.createdAt)) else ""
            when (msg.role) {
                ChatRole.USER -> {
                    sb.appendLine("## Pengguna")
                    if (time.isNotBlank()) sb.appendLine("> $time")
                    if (msg.attachments.isNotEmpty()) {
                        sb.appendLine("> Lampiran: ${msg.attachments.joinToString { a -> a.name }}")
                    }
                    sb.appendLine()
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                ChatRole.ASSISTANT -> {
                    sb.appendLine("## AI")
                    if (time.isNotBlank()) sb.appendLine("> $time")
                    sb.appendLine()
                    sb.appendLine(msg.content)
                    msg.toolCalls.forEach { call ->
                        sb.appendLine()
                        sb.appendLine("> Memanggil alat: **${call.name}**")
                        sb.appendLine("> Parameter: `${call.arguments.take(200)}`")
                        val callResult = call.result
                        if (!callResult.isNullOrBlank()) {
                            sb.appendLine("> Hasil: ${callResult.take(500)}")
                        }
                    }
                    sb.appendLine()
                }
                else -> Unit
            }
        }
        sb.appendLine("---")
        sb.appendLine("> Waktu ekspor: ${timeFmt.format(java.util.Date())}")
        return sb.toString()
    }

    fun toggleStarred(messageId: Long) {
        if (messageId <= 0) return
        viewModelScope.launch {
            val target = dbMessages.firstOrNull { it.id == messageId } ?: return@launch
            repository.setStarred(messageId, !target.starred)
        }
    }

    private suspend fun generateTitle(cid: Long) {
        try {
            val config = resolveConfig(_state.value)
            val history = repository.getHistory(cid).takeLast(6)
            if (history.isEmpty()) return
            val provider = providerFactory(config.provider)
            val sys = ChatMessage(
                role = ChatRole.SYSTEM,
                content = "Buatkan judul singkat 3 sampai 6 kata untuk percakapan ini, keluarkan hanya judulnya saja, tanpa tanda kutip, tanda baca, atau penjelasan."
            )
            val sb = StringBuilder()
            provider.chatStream(
                listOf(sys) + history.map { repository.messageToDomain(it) },
                config,
                emptyList()
            ).collect { ev ->
                when (ev) {
                    is StreamEvent.Delta -> sb.append(ev.text)
                    is StreamEvent.Error -> throw IllegalStateException(ev.message)
                    else -> Unit
                }
            }
            val title = sb.toString().trim().trim('"', '“', '”').take(8)
            if (title.isNotBlank() && currentConversationId == cid) {
                repository.updateTitle(cid, title)
                _state.update { it.copy(title = title) }
            }
        } catch (e: Exception) {
            titleGenerated = false
        }
    }

    fun retryLast() {
        if (currentConversationId <= 0 || _state.value.isRunning) return
        viewModelScope.launch {
            val cid = currentConversationId
            val history = repository.getHistory(cid)
            if (history.none { it.role == "USER" }) {
                _state.update { it.copy(notification = appContext.getString(com.miloai.R.string.vm_no_retry)) }
                return@launch
            }
            var deleteFrom = -1L
            for (i in history.indices.reversed()) {
                val msg = history[i]
                if (msg.role == "ASSISTANT" && msg.toolCallsJson.isNullOrBlank()) {
                    deleteFrom = msg.id
                } else {
                    break
                }
            }
            if (deleteFrom > 0) {
                repository.deleteMessagesRange(cid, deleteFrom, history.last().id)
            }
            _state.update { it.copy(error = null) }
            runGeneration(cid)
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissNotification() {
        _state.update { it.copy(notification = null) }
    }

    private fun sendWithContent(text: String, attachments: List<com.miloai.core.model.Attachment>) {
        viewModelScope.launch {
            if (currentConversationId <= 0) {
                val s = _state.value
                val cid = repository.createConversation(
                    s.provider, s.model, s.mode,
                    defaultTitle = appContext.getString(com.miloai.R.string.chat_new_chat),
                    agentId = s.agentId
                )
                currentConversationId = cid
                repository.updateTitle(cid, text.take(30).ifBlank { appContext.getString(com.miloai.R.string.chat_new_chat) })
                _state.update {
                    it.copy(
                        conversationId = cid,
                        title = text.take(30).ifBlank { appContext.getString(com.miloai.R.string.chat_new_chat) }
                    )
                }
                startObserving(cid)
            }
            val cid = currentConversationId
            val s = _state.value
            repository.insertMessage(
                repository.domainToMessage(
                    ChatMessage(
                        role = ChatRole.USER,
                        content = text,
                        model = s.model,
                        mode = s.mode,
                        attachments = attachments
                    ),
                    cid
                )
            )
            _state.update {
                it.copy(input = "", error = null, pendingAttachments = emptyList(), sendTick = it.sendTick + 1)
            }
            runGeneration(cid)
        }
    }

    fun saveAsSkill() {
        if (currentConversationId <= 0) return
        val container = (appContext.applicationContext as com.miloai.MiloAiApp).container
        viewModelScope.launch {
            val history = repository.getHistory(currentConversationId)
            val toolUses = history.mapNotNull { m ->
                m.toolCallsJson?.let {
                    runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrDefault(emptyList())
                }
            }.flatten()
            if (toolUses.isEmpty()) {
                _state.update { it.copy(notification = appContext.getString(com.miloai.R.string.vm_skill_no_tools)) }
                return@launch
            }
            val names = toolUses.map { it.name }.distinct()
            val skillName = "skill_${System.currentTimeMillis() % 100000}"
            val description = "Skill hasil rekam otomatis: memakai ${names.joinToString(", ")} untuk menyelesaikan operasi multi-langkah"
            val md = buildString {
                appendLine("---")
                appendLine("name: $skillName")
                appendLine("description: $description")
                appendLine("allowed-tools:")
                names.forEach { appendLine("  - $it") }
                appendLine("---")
                appendLine("1. Konfirmasi tujuan tugas kepada pengguna terlebih dahulu.")
                appendLine("2. Jalankan langkah-langkah berikut secara berurutan (parameter dapat disesuaikan bila perlu):")
                toolUses.take(10).forEachIndexed { i, call ->
                    appendLine("   ${i + 1}. Panggil ${call.name} (contoh parameter: ${call.arguments.take(80)})")
                }
                appendLine("3. Setelah selesai, rangkum hasil eksekusi untuk pengguna.")
            }
            val result = container.skillRepository.import("$skillName.md", md)
            result.onSuccess {
                _state.update { it.copy(notification = appContext.getString(com.miloai.R.string.vm_skill_saved, skillName)) }
            }.onFailure { e ->
                _state.update { it.copy(notification = appContext.getString(com.miloai.R.string.vm_skill_failed, e.message ?: "")) }
            }
        }
    }

    fun respondConfirm(allow: Boolean) {
        val req = _state.value.confirmRequest ?: return
        engine.respond(req.call.id, allow)
        _state.update { it.copy(confirmRequest = null) }
    }

    fun updateAgent(agentId: Long) {
        viewModelScope.launch {
            val agent = runCatching { agentRepo.getById(agentId) }.getOrNull() ?: return@launch
            val provider = runCatching { ProviderId.valueOf(agent.provider) }.getOrDefault(ProviderId.OPENAI_COMPAT)
            _state.update {
                it.copy(
                    agentId = agent.id,
                    agentName = agent.name,
                    provider = provider,
                    model = agent.model
                )
            }
            persistMeta()
        }
    }

    fun updateModel(model: String) {
        _state.update { it.copy(model = model) }
        persistMeta()
    }

    fun updateMode(mode: AppMode) {
        _state.update { it.copy(mode = mode) }
        persistMeta()
    }

    private fun persistMeta() {
        if (currentConversationId <= 0) return
        val s = _state.value
        viewModelScope.launch {
            repository.updateMeta(currentConversationId, s.model, s.mode)
        }
    }
}

class ChatViewModelFactory(
    private val conversationId: Long,
    private val container: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ChatViewModel(
            conversationId,
            container.repository,
            container.settings,
            container.agentRepository,
            container.engine,
            container.providerFactory,
            container.appContext
        ) as T
}
