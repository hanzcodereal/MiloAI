package com.miloai.core.engine

import com.miloai.core.mode.AppMode
import com.miloai.core.model.ChatMessage
import com.miloai.core.model.ChatRole
import com.miloai.core.model.ProviderConfig
import com.miloai.core.model.ProviderId
import com.miloai.core.model.StreamEvent
import com.miloai.core.model.ToolCall
import com.miloai.core.model.ToolCallStatus
import com.miloai.core.model.ToolSpec
import com.miloai.core.provider.ChatProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

interface ToolCatalog {
    fun specsFor(mode: AppMode): List<ToolSpec>
    fun find(name: String): ToolSpec?
}

interface ToolRunner {
    suspend fun run(name: String, arguments: String): String
}

sealed interface EngineEvent {
    data class Delta(val text: String) : EngineEvent
    data class ThinkingDelta(val text: String) : EngineEvent
    data class ToolCallStarted(val call: ToolCall) : EngineEvent
    data class ToolCallFinished(val call: ToolCall) : EngineEvent
    data class AssistantFinished(val message: ChatMessage) : EngineEvent
    data class Usage(val promptTokens: Long, val completionTokens: Long) : EngineEvent
    data class ConfirmRequested(val call: ToolCall) : EngineEvent
    data object Completed : EngineEvent
    data class Failed(val message: String) : EngineEvent
}

class ChatEngine(
    private val providerFactory: (ProviderId) -> ChatProvider,
    private val toolCatalog: ToolCatalog,
    private val toolRunner: ToolRunner
) {

    private val pendingConfirms = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val confirmRequestsFlow = MutableSharedFlow<ToolCall>(extraBufferCapacity = 8)
    val confirmRequests: SharedFlow<ToolCall> = confirmRequestsFlow

    private val confirmTimeoutMs = 300_000L

    fun respond(callId: String, allow: Boolean) {
        pendingConfirms.remove(callId)?.complete(allow)
    }

    fun run(
        messages: List<ChatMessage>,
        config: ProviderConfig,
        mode: AppMode,
        maxToolCalls: Int = 0,
        budgetSummary: String = ""
    ): Flow<EngineEvent> = flow {
        val provider = try {
            providerFactory(config.provider)
        } catch (e: Exception) {
            emit(EngineEvent.Failed("Inisialisasi layanan gagal: ${e.message}"))
            return@flow
        }
        val effectiveConfig = config.copy(reasoning = config.reasoning && mode == AppMode.MAX)
        var history = messages.map { m ->
            if (m.role == ChatRole.TOOL) {
                m.copy(content = truncateToolResult(m.content))
            } else m
        }
        var toolCallsUsed = 0
        val toolFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()
        while (true) {
            val budgetReached = maxToolCalls > 0 && toolCallsUsed >= maxToolCalls
            var text = StringBuilder()
            var thinking = StringBuilder()
            var thinkingSignature: String? = null
            var toolCalls = emptyList<ToolCall>()
            try {
                val tools = if (budgetReached) emptyList() else toolCatalog.specsFor(mode)
                val custom = config.systemPrompt.trim()
                val basePrompt = if (custom.isBlank()) {
                    systemPromptFor(mode)
                } else {
                    "$custom\n\n${systemPromptFor(mode)}"
                }
                val sysContent = if (maxToolCalls > 0 && !budgetReached) {
                    "$basePrompt\n\nPada proses ini maksimum $maxToolCalls panggilan alat diizinkan, rencanakan lebih awal, gabungkan langkah, dan hindari pengulangan."
                } else {
                    basePrompt
                }
                val sys = ChatMessage(role = ChatRole.SYSTEM, content = sysContent)
                val requestHistory = if (budgetReached) {
                    history + ChatMessage(
                        role = ChatRole.USER,
                        content = "(Pesan sistem) Batas panggilan alat pada proses ini telah tercapai ($maxToolCalls panggilan). Segera jawab pengguna berdasarkan langkah yang sudah selesai dan informasi yang sudah didapat: jelaskan apa yang sudah dikerjakan, apa yang tersisa, dan bagaimana pengguna dapat melanjutkan; jangan memanggil alat lagi."
                    )
                } else {
                    history
                }
                provider.chatStream(listOf(sys) + requestHistory, effectiveConfig, tools).collect { ev ->
                    when (ev) {
                        is StreamEvent.Delta -> {
                            text.append(ev.text)
                            emit(EngineEvent.Delta(ev.text))
                        }
                        is StreamEvent.ThinkingDelta -> {
                            thinking.append(ev.text)
                            emit(EngineEvent.ThinkingDelta(ev.text))
                        }
                        is StreamEvent.ToolCallsDone -> toolCalls = ev.calls
                        is StreamEvent.ThinkingSignature -> thinkingSignature = ev.signature
                        is StreamEvent.Usage -> emit(EngineEvent.Usage(ev.promptTokens, ev.completionTokens))
                        is StreamEvent.Error -> throw IllegalStateException(ev.message)
                        StreamEvent.Done -> Unit
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emit(EngineEvent.Failed(e.message ?: e.javaClass.simpleName))
                return@flow
            }
            if (budgetReached && text.isBlank()) {
                val fallback = budgetSummary.ifBlank {
                    "Batas panggilan alat untuk proses ini telah tercapai ($maxToolCalls panggilan). Semua hasil yang selesai tetap tersimpan, kamu dapat mengirim pesan lagi untuk melanjutkan."
                }
                text.append(fallback)
                emit(EngineEvent.Delta(fallback))
            }
            val assistantMsg = ChatMessage(
                role = ChatRole.ASSISTANT,
                content = text.toString(),
                toolCalls = if (budgetReached) emptyList() else toolCalls,
                model = config.model,
                mode = mode,
                thinkingText = thinking.toString().ifBlank { null },
                thinkingSignature = thinkingSignature
            )
            emit(EngineEvent.AssistantFinished(assistantMsg))
            if (toolCalls.isEmpty() || budgetReached) break
            toolCallsUsed += toolCalls.size
            val finishedCalls = mutableListOf<ToolCall>()
            history = history + assistantMsg.copy(
                toolCalls = toolCalls.map { it.copy(status = ToolCallStatus.PENDING) }
            )
            if (mode == AppMode.MAX && toolCalls.size > 1) {
                val decided = toolCalls.map { call ->
                    val spec = toolCatalog.find(call.name)
                    Triple(call, spec, gate(mode, spec, call))
                }
                val executed = coroutineScope {
                    decided.map { (call, spec, decision) ->
                        async {
                            when (decision) {
                                is GateResult.Denied ->
                                    Triple(call, ToolCallStatus.DENIED, "Alat ditolak dijalankan: ${decision.reason}")
                                is GateResult.NeedsConfirm ->
                                    Triple(call, ToolCallStatus.REJECTED, "Saat dijalankan paralel tidak dapat dikonfirmasi satu per satu, dilewati (silakan ajukan ulang secara terpisah)")
                                is GateResult.Allow -> {
                                    val (status, result) = runWithCircuitBreaker(call, spec, toolFailures) { }
                                    Triple(call, status, result)
                                }
                            }
                        }
                    }.map { it.await() }
                }
                executed.forEach { (call, status, resultText) ->
                    val finished = call.copy(result = resultText, status = status)
                    emit(EngineEvent.ToolCallFinished(finished))
                    history = history + ChatMessage(
                        role = ChatRole.TOOL,
                        content = truncateToolResult(resultText),
                        toolCallId = call.id,
                        toolName = call.name
                    )
                }
            } else {
            for (call in toolCalls) {
                val spec = toolCatalog.find(call.name)
                val decision = gate(mode, spec, call)
                val status: ToolCallStatus
                val resultText: String
                when (decision) {
                    is GateResult.Denied -> {
                        status = ToolCallStatus.DENIED
                        resultText = "Alat ditolak dijalankan: ${decision.reason}"
                    }
                    is GateResult.NeedsConfirm -> {
                        val deferred = CompletableDeferred<Boolean>()
                        pendingConfirms[call.id] = deferred
                        var emitFailed = false
                        var timedOut = false
                        val allow = if (!confirmRequestsFlow.tryEmit(call)) {
                            emitFailed = true
                            pendingConfirms.remove(call.id)
                            false
                        } else {
                            try {
                                withTimeout(confirmTimeoutMs) { deferred.await() }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                timedOut = true
                                false
                            } finally {
                                pendingConfirms.remove(call.id)
                            }
                        }
                        if (!allow) {
                            status = ToolCallStatus.REJECTED
                            resultText = when {
                                emitFailed -> "Permintaan konfirmasi tidak sampai, panggilan alat ini ditolak"
                                timedOut -> "Pengguna tidak mengonfirmasi dalam 5 menit, panggilan alat ini dilewati otomatis"
                                else -> "Pengguna menolak panggilan alat ini"
                            }
                        } else {
                            val (s, r) = runWithCircuitBreaker(call, spec, toolFailures) { emit(it) }
                            status = s
                            resultText = r
                        }
                    }
                    is GateResult.Allow -> {
                        val (s, r) = runWithCircuitBreaker(call, spec, toolFailures) { emit(it) }
                        status = s
                        resultText = r
                    }
                }
                val finished = call.copy(result = resultText, status = status)
                finishedCalls.add(finished)
                emit(EngineEvent.ToolCallFinished(finished))
                history = history + ChatMessage(
                    role = ChatRole.TOOL,
                    content = truncateToolResult(resultText),
                    toolCallId = call.id,
                    toolName = call.name
                )
            }
            }
        }
        emit(EngineEvent.Completed)
    }.flowOn(Dispatchers.IO)

    private suspend fun runWithCircuitBreaker(
        call: ToolCall,
        spec: ToolSpec?,
        failures: MutableMap<String, Int>,
        emitEvent: suspend (EngineEvent) -> Unit
    ): Pair<ToolCallStatus, String> {
        if ((failures[call.name] ?: 0) >= 3) {
            return ToolCallStatus.DENIED to
                "${call.name} gagal 3 kali berturut-turut, jangan coba lagi: baca ulang deskripsi parameter alat ini lalu perbaiki dan coba paling banyak sekali lagi; " +
                "bila masih gagal, ganti ke alat lain atau langsung jawab pengguna berdasarkan informasi yang ada."
        }
        val (status, result) = executeTool(call, spec, emitEvent)
        val runnerReportedFailure = result.startsWith("ERROR") ||
            result.startsWith("Parameter alat") || result.startsWith("Eksekusi alat")
        when {
            status == ToolCallStatus.DONE && !runnerReportedFailure -> failures[call.name] = 0
            status == ToolCallStatus.FAILED || runnerReportedFailure ->
                failures[call.name] = (failures[call.name] ?: 0) + 1
            else -> Unit
        }
        return status to result
    }

    private suspend fun executeTool(
        call: ToolCall,
        spec: ToolSpec?,
        emitEvent: suspend (EngineEvent) -> Unit
    ): Pair<ToolCallStatus, String> {
        emitEvent(EngineEvent.ToolCallStarted(call))
        return try {
            val result = withTimeout(60_000) { toolRunner.run(call.name, call.arguments) }
            val failed = result.startsWith("ERROR") ||
                result.startsWith("Parameter alat") ||
                result.startsWith("Eksekusi alat")
            (if (failed) ToolCallStatus.FAILED else ToolCallStatus.DONE) to result
        } catch (e: CancellationException) {
            throw e
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ToolCallStatus.FAILED to "Eksekusi alat melewati batas waktu (60 detik)"
        } catch (e: Exception) {
            ToolCallStatus.FAILED to (e.message ?: "Eksekusi alat gagal")
        }
    }

    private fun truncateToolResult(text: String): String {
        if (text.length <= 6000) return text
        return text.take(6000) + "\n… (hasil alat terlalu panjang dan dipotong, total ${text.length} karakter)"
    }

    private fun gate(mode: AppMode, spec: ToolSpec?, call: ToolCall): GateResult {
        if (mode == AppMode.CHAT) return GateResult.Denied("Mode Chat tidak menjalankan alat")
        val s = spec ?: return GateResult.Denied("Alat tidak dikenal: ${call.name}")
        if (mode == AppMode.PLAN && !s.readOnly) return GateResult.Denied("Mode Plan hanya mengizinkan alat hanya-baca")
        if (mode == AppMode.BUILD) return GateResult.NeedsConfirm
        return GateResult.Allow
    }

    private fun systemPromptFor(mode: AppMode): String = when (mode) {
        AppMode.CHAT -> "Kamu adalah asisten AI yang ramah, jawab pertanyaan pengguna dengan bahasa yang ringkas dan akurat. Kamu tidak perlu dan tidak diizinkan memanggil alat apa pun."
        AppMode.PLAN -> "Kamu sekarang berada di mode Plan (perencanaan). Kamu hanya boleh menganalisis, menyusun rencana, dan melihat informasi hanya-baca, dan sama sekali dilarang menjalankan operasi apa pun yang mengubah sesuatu. Kamu memiliki beberapa alat hanya-baca (seperti pencarian web dan kalkulator). Jika pengguna meminta menjalankan suatu operasi, berikan rencana eksekusi yang rinci dan sarankan beralih ke mode Build atau Max."
        AppMode.BUILD -> "Kamu sekarang berada di mode Build (pembangunan). Kamu dapat memanggil alat untuk membantu pengguna menyelesaikan tugas, tetapi sebelum setiap alat dijalankan sistem akan meminta konfirmasi pengguna, jadi ajukan panggilan alat dengan berani dan wajar, dan jelaskan tujuan setiap langkah." +
            " Sebelum memulai tugas multi-langkah, jelaskan dulu rencana dan perkiraan jumlah panggilan alat dalam satu kalimat (mis. \"perkiraan 6-10 panggilan alat\"), lalu mulai menjalankan; bila kenyataannya jauh melebihi, jelaskan alasannya secara singkat." +
            " Strategi pencarian: query harus spesifik, pertanyaan multi-sudut pakai array query untuk sekali cari; bila perlu detail halaman web, gunakan read_top≥1 untuk langsung mengambil isi, hindari web_read satu per satu yang memboroskan putaran."
        AppMode.MAX -> "Kamu sekarang berada di mode Max (maksimum). Kamu dapat memanggil alat secara mandiri dan berkelanjutan untuk menyelesaikan tugas pengguna, tanpa perlu meminta konfirmasi setiap kali. Rencanakan urutan operasi lebih awal, selesaikan tugas dengan langkah sesedikit mungkin, dan rangkum hasilnya setelah selesai." +
            " Sebelum memulai tugas multi-langkah, jelaskan dulu rencana dan perkiraan jumlah panggilan alat dalam satu kalimat (mis. \"perkiraan 6-10 panggilan alat\"), lalu mulai menjalankan; bila kenyataannya jauh melebihi, jelaskan alasannya secara singkat." +
            " Strategi pencarian: saat memanggil web_search, query harus spesifik (sertakan batas waktu/lokasi); bila perlu detail halaman web, langsung gunakan read_top≥1 untuk mengambil isi sekaligus, hindari web_read satu per satu yang memboroskan putaran; bila hasil kurang, ganti rumusan paling banyak 1-2 kali, lalu jawab berdasarkan informasi yang sudah didapat."
    }

    private sealed interface GateResult {
        data object Allow : GateResult
        data object NeedsConfirm : GateResult
        data class Denied(val reason: String) : GateResult
    }
}
