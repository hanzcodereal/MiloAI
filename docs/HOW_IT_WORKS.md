# Cara Kerja Milo AI: Penjelasan Teknis Lengkap

Dokumen ini menjelaskan bagian dalam **Milo AI** secara menyeluruh: arsitektur modul, setiap lapisan alur permintaan, protokol streaming, loop agen, sistem alat, penyimpanan, render UI, keamanan, dan pelajaran rekayasa dari bug nyata. Ditulis sebagai panduan belajar bagi programmer yang ingin memahami aplikasi agen AI Android yang nyata dan berfungsi.

> Cakupan: alat bawaan berbasis web dan utilitas (pencarian web, baca halaman, kalkulator, cuaca, kurs, dan lainnya), Skill gaya opencode, serta Agent (konfigurasi sekali ketuk per percakapan).

> Catatan: potongan kode dan nama alat dibiarkan dalam bentuk aslinya (bahasa Inggris). Komentar di dalam blok kode belum diterjemahkan.

---

## Daftar Isi

1. [Gambaran proyek & peta berkas](#1-gambaran-proyek--peta-berkas)
2. [Arsitektur modul & inversi dependensi](#2-arsitektur-modul--inversi-dependensi)
3. [Model domain](#3-model-domain)
4. [Adaptor penyedia & katalog model](#4-adaptor-penyedia--katalog-model)
5. [Perjalanan satu pesan](#5-perjalanan-satu-pesan)
6. [SSE streaming secara mendalam](#6-sse-streaming-secara-mendalam)
7. [Loop agen (ChatEngine)](#7-loop-agen-chatengine)
8. [Mode & gerbang keamanan](#8-mode--gerbang-keamanan)
9. [Alur konfirmasi](#9-alur-konfirmasi)
10. [Sistem alat](#10-sistem-alat)
11. [Skill (gaya opencode)](#11-skill-gaya-opencode)
12. [Penyimpanan & manajemen state](#12-penyimpanan--manajemen-state)
13. [Lapisan UI](#13-lapisan-ui)
14. [Desain keamanan](#14-desain-keamanan)
15. [Matriks penanganan kesalahan](#15-matriks-penanganan-kesalahan)
16. [Pelajaran rekayasa dari bug nyata](#16-pelajaran-rekayasa-dari-bug-nyata)
17. [Urutan belajar yang disarankan](#17-urutan-belajar-yang-disarankan)
18. [Memulai: build, jalankan, uji, debug](#18-memulai-build-jalankan-uji-debug)
19. [Agent: konfigurasi per percakapan](#19-agent-konfigurasi-per-percakapan)
20. [Pipeline pencarian web](#20-pipeline-pencarian-web)
21. [Memori jangka panjang](#21-memori-jangka-panjang)
22. [Memperluas aplikasi: cara menambah sesuatu](#22-memperluas-aplikasi-cara-menambah-sesuatu)
23. [Pemecahan masalah & batasan platform yang diketahui](#23-pemecahan-masalah--batasan-platform-yang-diketahui)
24. [Glosarium & daftar serah terima](#24-glosarium--daftar-serah-terima)
25. [Filosofi desain & pendekatan pengembangan](#25-filosofi-desain--pendekatan-pengembangan)

> **Panduan membaca untuk maintainer baru**: baca sekilas bab 1–3, lalu loncat ke **18 (Memulai)** untuk menjalankan aplikasi, **22 (Memperluas)** untuk perubahan pertamamu, dan **24 (Daftar serah terima)** untuk semua yang ada di luar repositori. Bab 4–17 adalah pembahasan mendalam; bacalah saat menyentuh area terkait.

---

## 1. Gambaran proyek & peta berkas

```
Milo AI/
├── settings.gradle.kts                     # include(":app", ":core", ":providers", ":skills")
├── gradle/libs.versions.toml               # version catalog (single source of truth for deps)
│
├── app/                                    # Android application (UI + system integration)
│   ├── src/main/java/com/miloai/
│   │   ├── MiloAiApp.kt              # Application class + AppContainer (DI wiring)
│   │   ├── MainActivity.kt                 # Single-activity Compose entry
│   │   ├── ui/
│   │   │   ├── navigation/AppNav.kt        # Navigation graph
│   │   │   ├── conversations/             # Conversation list screen
│   │   │   ├── chat/
│   │   │   │   ├── ChatScreen.kt           # Chat UI, input bar, dialogs, scroll logic
│   │   │   │   ├── ChatViewModel.kt        # State machine + send/stop/compress/edit
│   │   │   │   └── MessageViews.kt         # Bubble/tool-card/code-card composables
│   │   │   ├── settings/SettingsScreen.kt  # All settings sections
│   │   │   └── agents/                        # Agent onboarding wizard + picker dialogs
│   │   └── tools/
│   │       ├── SpeechInputHelper.kt        # Voice input (SpeechRecognizer)
│   │       └── SpeechPlayer.kt             # TTS
│   └── src/main/res/                       # Strings (Bahasa Indonesia), tema, ikon
│
├── core/                                  # Pure logic (no Android UI)
│   ├── src/main/java/com/miloai/core/
│   │   ├── engine/ChatEngine.kt            # The agent loop
│   │   ├── chat/ChatRepository.kt          # DB access layer
│   │   ├── db/AppDatabase.kt               # Room entities + DAOs + migrations (v11)
│   │   ├── model/ChatModels.kt             # ChatMessage, ToolCall, ProviderConfig…
│   │   ├── mode/AppMode.kt                 # Chat/Plan/Build/Max
│   │   ├── catalog/ModelCatalog.kt         # Built-in model registry per provider
│   │   ├── provider/ChatProvider.kt        # The provider interface
│   │   ├── sse/SseParser.kt                # SSE line parser
│   │   ├── skills/SkillRepository.kt       # SKILL.md parsing (YAML frontmatter)
│   │   └── storage/                        # SettingsRepository, KeyStoreCrypto
│   └── ...
│
├── providers/                             # Vendor API adapters
│   └── src/main/java/com/miloai/providers/
│       ├── ProviderFactory.kt              # providerId → ChatProvider
│       ├── openai/OpenAiProvider.kt        # OpenAI-compatible (incl. deepseek/qwen/kimi…)
│       ├── anthropic/AnthropicProvider.kt
│       └── gemini/GeminiProvider.kt
│
└── skills/                                # Built-in tools
    └── src/main/java/com/miloai/skills/
        ├── ToolModels.kt                   # DeviceTool interface, ToolContext
        ├── ToolRegistry.kt                 # Daftar alat bawaan per mode
        ├── DeviceToolRunner.kt             # name+args → DeviceTool.execute
        ├── UrlSafety.kt                    # Penyaring URL publik (web_read / web_search)
        └── tools/                          # 11 tool implementations
```

Milo AI hanya dirilis sebagai satu build **lite** (R8 + shrink resources, tanpa OCR ML Kit di perangkat). Proyek asal memiliki flavor *full* dan *lite*; di Milo AI flavor tersebut dihapus.

---

## 2. Arsitektur modul & inversi dependensi

### 2.1 Grafik dependensi

```
:app ────────▶ :core
   │           :providers
   └─────────▶ :skills ────▶ :core
```

- `:app` bergantung pada semua modul.
- `:skills` hanya bergantung pada `:core`.
- `:providers` hanya bergantung pada `:core`.
- `:core` tidak bergantung pada modul internal mana pun (hanya Android SDK + library).

### 2.2 Mengapa pelapisan ini penting

Modul `:skills` berisi alat-alat bawaan dan tidak boleh bergantung pada kelas milik aplikasi. Cara naifnya adalah mengimpor kelas dari `:app` langsung ke dalam alat. Itu menciptakan dependensi keras `:skills` → `:app`, sehingga modul tidak dapat dipisahkan dan tidak dapat diuji.

Sebagai gantinya, `:skills` mendefinisikan kontrak yang dibutuhkannya sendiri, dan `:app` hanya merakit objeknya:

```kotlin
// :skills — ToolModels.kt
data class ToolContext(val appContext: Context)

interface DeviceTool {
    val name: String
    val description: String
    val readOnly: Boolean
    val parameters: JsonObject
    suspend fun execute(context: ToolContext, arguments: JsonObject): String
}
```

`:app` membangun `ToolContext` dan menyuntikkannya ke `DeviceToolRunner` (lihat `MiloAiApp.kt`). Konsekuensinya:

- `:skills` dapat diuji unit dengan memalsukan `ToolContext` (cukup `Context` tiruan).
- Tidak ada dependensi Gradle melingkar (Gradle akan menggagalkan build bila ada siklus).

### 2.3 AppContainer: injeksi dependensi buatan sendiri

`MiloAiApp.kt` berisi `AppContainer`, kelas biasa yang membangun seluruh graf objek:

```kotlin
class AppContainer(context: Application) {
    val db = AppDatabase.get(context)
    val settings = SettingsRepository(context)
    val repository = ChatRepository(db)
    val skillRepository = SkillRepository(context.applicationContext)

    private val toolContext = ToolContext(context.applicationContext)

    val tools: List<DeviceTool> = listOf( /* 11 tools, see MiloAiApp.kt */ )
    val registry = ToolRegistry(tools)
    val runner = DeviceToolRunner(registry, toolContext)
    val engine = ChatEngine(providerFactory, registry, runner)
}
```

Komponen lain dapat mengaksesnya dari mana saja:

```kotlin
val container = (applicationContext as MiloAiApp).container
```

---

## 3. Model domain

Semua tipe inti berada di `:core/model/ChatModels.kt`. Memahaminya membuat seluruh basis kode mudah dibaca.

### 3.1 Penyedia dan peran

```kotlin
enum class ProviderId(val displayName: String) {
    OPENAI_COMPAT("Kompatibel OpenAI"), ANTHROPIC("Anthropic Claude"), GEMINI("Google Gemini")
}

enum class ChatRole(val wire: String) {
    SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool")
}
```

`ChatRole` membawa string `wire` yang dipakai dalam permintaan API. Ini menjaga domain tetap bersih sementara penyedia menerjemahkannya ke format wire.

### 3.2 ToolCall dan mesin statusnya

```kotlin
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String? = null,
    val status: ToolCallStatus = ToolCallStatus.PENDING
)

enum class ToolCallStatus { PENDING, RUNNING, DONE, FAILED, REJECTED, DENIED }
```

Sebuah `ToolCall` melewati status-status berikut:

```
PENDING ──▶ RUNNING ──▶ DONE
   │           │
   │           └──────▶ FAILED        (execution threw)
   ├──────▶ REJECTED                  (user declined or request dropped)
   └──────▶ DENIED                    (mode gate refused: e.g. Chat mode)
```

UI menampilkan setiap status sebagai lencana berwarna di `MessageViews.kt` (`StatusBadge`):

| Status | Lencana | Warna |
|---|---|---|
| PENDING | Menunggu | abu-abu |
| RUNNING | Berjalan… | primer |
| DONE | Selesai | hijau |
| FAILED | Gagal | merah |
| REJECTED | Ditolak | merah |
| DENIED | Ditolak | merah |

### 3.3 ChatMessage: pesan wire/domain

```kotlin
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,        // for TOOL messages: which call this answers
    val toolName: String? = null,
    val model: String? = null,             // display metadata
    val mode: AppMode? = null,             // display metadata
    val attachments: List<Attachment> = emptyList(),
    val thinkingText: String? = null,
    val thinkingSignature: String? = null
)
```

Dua detail penting:

- `toolCallId` mengaitkan pesan berperan `TOOL` kembali ke entri `tool_calls` milik asisten. Inilah yang diwajibkan OpenAI/Anthropic agar permintaan valid (lihat §7.4).
- `thinkingText`/`thinkingSignature` membawa konten penalaran dari model reasoning (o1/deepseek-reasoner/Claude Opus) dan dikirim balik bersama `reasoning_signature` bila diwajibkan vendor.

### 3.4 ProviderConfig

```kotlin
data class ProviderConfig(
    val provider: ProviderId,
    val baseUrl: String,
    val apiKey: String,          // stored encrypted — see KeyStoreCrypto
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    val reasoning: Boolean,     // "deep thinking" mode for reasoning models
    val systemPrompt: String = ""  // custom Agent prompt, prepended to the mode prompt in ChatEngine
)
```

API key **dienkripsi dengan Android Keystore** (`:core/storage/KeyStoreCrypto.kt`). Materi kunci tidak pernah keluar dari keystore berbasis perangkat keras; aplikasi hanya menyimpan ciphertext.

### 3.5 Attachment

```kotlin
@Serializable
data class Attachment(
    val kind: String,           // "image" | "doc"
    val name: String,
    val mimeType: String,
    val dataBase64: String = "",      // images are base64-embedded
    val textContent: String? = null   // PDF/docx/xlsx extracted text
)
```

Gambar diperkecil/dikompres dan dikirim inline (model visual menerima data URL base64). Dokumen (PDF, Word, Excel) diurai **di perangkat** dan dikirim sebagai teks hasil ekstraksi. PDF hasil pindai memerlukan OCR, yang tidak tersedia di versi lite.

---

## 4. Adaptor penyedia & katalog model

### 4.1 Antarmuka ChatProvider

```kotlin
// :core/provider/ChatProvider.kt
interface ChatProvider {
    fun chatStream(
        messages: List<ChatMessage>,
        config: ProviderConfig,
        tools: List<ToolSpec>
    ): Flow<StreamEvent>
}
```

Setiap vendor (kompatibel OpenAI, Anthropic, Gemini) mengimplementasikan satu metode ini: menerima riwayat + konfigurasi + spesifikasi alat, lalu mengembalikan `Flow<StreamEvent>` dingin. `ProviderFactory` memetakan `ProviderId` → implementasi.

`StreamEvent`:

```kotlin
sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data class ThinkingDelta(val text: String) : StreamEvent
    data class ThinkingSignature(val signature: String) : StreamEvent
    data class ToolCallsDone(val calls: List<ToolCall>) : StreamEvent
    data class Usage(val promptTokens: Long, val completionTokens: Long) : StreamEvent
    data class Error(val message: String) : StreamEvent
    data object Done : StreamEvent
}
```

Abstraksinya sengaja netral terhadap vendor: OpenAI memancarkan `delta.tool_calls`, Anthropic memancarkan `delta.content[]` dengan `input_json_delta`, Gemini memancarkan `functionCall` dalam bentuknya sendiri, tetapi ketiganya bermuara pada `Delta` + `ToolCallsDone`.

### 4.2 ModelCatalog: metadata model pilihan

```kotlin
data class ModelEntry(
    val id: String, val label: String,
    val temperature: Double = 0.7, val maxTokens: Int = 4096,
    val supportsReasoning: Boolean = false,
    val contextWindow: Int = 200_000
)
```

Katalog (sekitar 50 model dari tiga penyedia) memberi UI nilai bawaan yang masuk akal (temperature, max tokens, jendela konteks untuk pengukur penggunaan, dukungan reasoning) tanpa menuliskannya langsung di layar. `contextWindow` juga menggerakkan indikator penggunaan token (`usageInput / contextWindow %`) di bilah atas obrolan.

### 4.3 Terjemahan format wire

Body permintaan kompatibel OpenAI:

```kotlin
val body = OpenAiRequest(
    model = config.model,
    messages = messages.mapNotNull { it.toWire() },
    temperature = if (reasoning) null else config.temperature,   // reasoning models forbid temperature
    maxTokens = config.maxTokens,
    reasoningEffort = if (reasoning) "high" else null,
    streamOptions = OpenAiStreamOptions(),
    tools = tools.map { OpenAiTool(OpenAiToolFunction(it.name, it.description, it.parameters)) }
        .takeIf { it.isNotEmpty() }
)
```

Hal-hal halus:

- **Model reasoning menolak `temperature`**: nilainya `null` saat reasoning aktif.
- **Skema alat** adalah JSON Schema `DeviceTool.parameters` yang diteruskan apa adanya. LLM memakainya untuk menghasilkan argumen.
- `messages.mapNotNull { it.toWire() }` memungkinkan `toWire()` membuang pesan yang tidak dapat direpresentasikan (mis. konten asisten yang kosong).

---

## 5. Perjalanan satu pesan

### 5.1 Diagram urutan lengkap

```
 User                    ChatScreen            ChatViewModel             ChatEngine              Provider             Room
  │  tap ⏎ (send)            │                        │                       │                     │                   │
  │─────────────────────────▶│  onSend()              │                       │                     │                   │
  │                          │───────────────────────▶│  send()               │                     │                   │
  │                          │                        │  ├─ isRunning? → return
  │                          │                        │  ├─ state.isRunning = true
  │                          │                        │  └─ launch { sendWithContent() }
  │                          │                        │        ├─ insertMessage(USER) ────────────────────────────────▶ INSERT
  │                          │                        │        ├─ sendTick++ (UI scrolls to bottom)
  │                          │                        │        └─ runGeneration(cid)
  │                          │                        │              │  runJob?.isActive → return   (mutex)
  │                          │                        │              │  state.isRunning = true
  │                          │                        │              │  streaming = placeholder(id=-1)
  │                          │                        │              ├─▶ engine.run(history, config, mode)
  │                          │                        │              │        └─ provider.chatStream(...) ──▶ HTTP POST
  │                          │                        │              │            │
  │                          │ ◀── Delta ─────────────┼──── Delta ───┼────────────┼──── SSE parse
  │                          │  (100ms ticker refresh)│              │            │
  │ ◀── recompose ───────────│◀── state.messages ──────┘              │            │
  │                          │                                        │            │
  │                          │                          └── AssistantFinished ───▶ INSERT (assistant)
  │                          │                                        │
  │                          │                              tool_calls? ── yes ──▶ for each call:
  │                          │                                        │        gate(mode) → confirm dialog
  │  ◀── AlertDialog ────────│◀── confirmRequest ────────────────────┼──── tryEmit(call)
  │  ketuk Izinkan ─────────▶│  respondConfirm(true) ─────────────────┼──── deferred.complete(true)
  │                          │                                        │        ToolRunner.run()  ──▶ DeviceTool.execute()
  │                          │                                        │        insertMessage(TOOL result)
  │                          │                                        │        loop again (unlimited by default; optional cap)
  │                          │                                        └── Completed
  │ ◀── final UI ────────────│◀── refresh()
```

### 5.2 Mesin status ViewModel

`ChatUiState` adalah satu data class imutabel yang disimpan dalam `MutableStateFlow`:

```kotlin
data class ChatUiState(
    val conversationId: Long, val title: String,
    val provider: ProviderId, val model: String, val mode: AppMode,
    val messages: List<UiMessage>,        // dbMessages + streaming
    val input: String,
    val isRunning: Boolean,
    val sendTick: Int,                    // increment = "please scroll to bottom"
    val confirmRequest: ConfirmRequest?,  // pending tool confirmation
    val notification: String?,            // transient snackbar message
    val error: String?,                   // persistent error banner
    val pendingAttachments: List<PendingAttachment>,
    val processing: Boolean,
    val agentId: Long?, val agentName: String,  // Agent bound to this conversation
    val attachmentError: String?,
    // … (usage totals are derived from the last message with usage, not stored)
)
```

Disiplin kuncinya: **semua mutasi lewat `_state.update { it.copy(...) }`**, tanpa penulisan field langsung dan tanpa pembaruan parsial. Hasilnya:

- Keamanan thread (update StateFlow bersifat atomik).
- Satu sumber kebenaran untuk UI.
- Debugging mudah (catat transisi status).

### 5.3 Menggabungkan pesan DB dengan aliran langsung

```kotlin
private var dbMessages: List<UiMessage> = emptyList()
private var streaming: UiMessage? = null

private fun refresh() {
    _state.update { it.copy(messages = dbMessages + listOfNotNull(streaming)) }
}
```

- `dbMessages` selalu segar berkat Flow Room: `repository.observeMessages(id).collect { list -> dbMessages = mapToUi(list); refresh() }`. **Setiap perubahan DB (termasuk sisipan oleh AI sendiri) otomatis merender ulang obrolan.**
- `streaming` adalah pesan asisten yang sedang berjalan dengan `id = -1` (penanda yang berarti "belum ada di DB").
- Job ticker 100 ms (`streamTickerJob`) memanggil `refresh()` selama streaming sehingga delta tampil meski `state.messages` hanya berubah saat ticker berjalan. Inilah *pemisahan* laju peristiwa (banyak delta/detik) dari laju penyegaran UI (10 Hz).

### 5.4 Akumulator streaming

Setiap `Delta` ditambahkan ke pesan streaming. Jebakan performa yang halus (yang pernah kami temui dan perbaiki): menambahkan ke `String` imutabel pada setiap delta bersifat **O(n²)**: balasan 40.000 karakter yang dikirim dalam 2.000 delta menyalin sekitar 40 juta karakter di main thread. Pola perbaikannya (dipakai di engine) adalah akumulator `StringBuilder` yang di-flush berdasarkan timer atau saat selesai. ViewModel tetap memakai penambahan sederhana tetapi mengandalkan ticker 100 ms untuk membatasi rekomposisi.

---

## 6. SSE streaming secara mendalam

### 6.1 Protokol SSE dalam praktik

Server-Sent Events lewat HTTP terlihat seperti ini:

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache

data: {"choices":[{"delta":{"role":"assistant"}}]}

data: {"choices":[{"delta":{"content":"Ha"}}]}

data: {"choices":[{"delta":{"content":"lo"}}]}

data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

Aturan yang dipakai parser:

1. Baris yang diawali `data:` mengakumulasi payload.
2. **Baris kosong** mengakhiri peristiwa → kirim data yang terkumpul.
3. `[DONE]` adalah peristiwa penanda → berhenti mengonsumsi.

### 6.2 Parser, baris demi baris

```kotlin
object SseParser {
    fun parse(body: ResponseBody, onEvent: suspend (event: String, data: String) -> Boolean): Flow<Unit> =
        flow<Unit> {
            val source = body.source()
            val dataBuffer = StringBuilder()
            var eventName = ""
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isBlank() -> {
                        if (dataBuffer.isNotEmpty()) {
                            val shouldContinue = onEvent(eventName, dataBuffer.toString())
                            dataBuffer.clear()
                            eventName = ""
                            if (!shouldContinue) break          // ← cancellation via return value
                        }
                    }
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trimStart()
                        if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                        dataBuffer.append(payload)
                    }
                }
            }
            if (dataBuffer.isNotEmpty()) onEvent(eventName, dataBuffer.toString())
        }.flowOn(Dispatchers.IO)
}
```

Poin pembelajaran:

- **Bebas backpressure secara desain**: `flowOn(Dispatchers.IO)` memindahkan pembacaan yang memblokir dari main thread; kolektor berjalan di tempat ia mengoleksi.
- **Pembatalan tanpa exception**: alih-alih melempar, callback mengembalikan `false` dan loop berhenti, bersih dan tanpa penanganan `CancellationException`.
- Baris `event:` dipakai beberapa server (mis. Anthropic memakai `event: content_block_delta`); parser mempertahankan nama peristiwa agar konsumen dapat bercabang berdasarkan nama itu.

### 6.3 Dekode chunk dan akumulasi panggilan alat

Di `OpenAiProvider.chatStream()`:

```kotlin
val toolAcc = mutableMapOf<Int, ToolAcc>()          // index → accumulated tool call
SseParser.parse(body) { _, data ->
    if (data == "[DONE]") {
        emit(StreamEvent.ToolCallsDone(toolAcc.toToolCalls()))   // flush partial calls
        emit(StreamEvent.Done)
        return@parse false
    }
    val chunk = runCatching { json.decodeFromString(OpenAiChunk.serializer(), data) }
        .getOrNull() ?: return@parse true            // ignore malformed chunk, keep going
    chunk.usage?.let { /* emit Usage */ }
    val choice = chunk.choices.firstOrNull() ?: return@parse true
    choice.delta?.content?.let { emit(StreamEvent.Delta(it)) }
    choice.delta?.reasoning?.let { emit(StreamEvent.ThinkingDelta(it)) }
    choice.delta?.toolCalls?.forEach { tc ->
        // tc.index + tc.function.name + tc.function.arguments (possibly split across chunks)
        toolAcc.mergeFragment(tc)
    }
    true
}
```

Argumen panggilan alat tiba **terpecah di beberapa chunk** (sering berupa fragmen JSON seperti `{"app":` lalu `"Kalkulator"}`), sehingga penyedia harus mengakumulasi per `index` sampai `finish_reason: "tool_calls"` atau `[DONE]`. `ToolCallsDone` dipancarkan sekali di akhir dengan argumen yang sudah digabung utuh.

### 6.4 Kekhasan Anthropic

Streaming Anthropic berorientasi peristiwa:

```
event: message_start
event: content_block_start   (content_block {type:"tool_use", id, name})
event: content_block_delta   (input_json_delta: {partial_json:"{\"app\":"})
event: content_block_delta   (input_json_delta: {partial_json:"Kalkulator\"}"})
event: content_block_stop
event: message_stop
```

Adaptor (`AnthropicProvider.kt`) mendengarkan `content_block_delta` dan menggabungkan fragmen `partial_json`; delta teks tiba sebagai `text_delta`. Field `event:` pada SSE inilah alasan `SseParser` mempertahankan nama peristiwa.

### 6.5 Disiplin retry & timeout

```kotlin
private suspend fun executeWithRetry(call: okhttp3.Call): okhttp3.Response {
    val retryableCodes = setOf(429, 502, 503, 504)
    repeat(2) { attempt ->
        try {
            val response = call.clone().execute()        // ← clone! a Call can execute once
            if (response.isSuccessful || response.code !in retryableCodes) return response
            response.close()
            if (attempt == 0) delay(500)                 // backoff
        } catch (e: java.io.IOException) {
            if (attempt == 0) delay(500) else throw e
        }
    }
    return call.clone().execute()
}
```

Mengapa `clone()` penting: `OkHttp.Call` hanya sekali pakai. Menjalankannya dua kali melempar `IllegalStateException("Already Executed")`. Kode lama memakai ulang `Call` yang sama sehingga retry *tidak pernah berhasil*. Ini bug nyata yang diperbaiki di v0.20.1.

Timeout: `connectTimeout(30s)` + `readTimeout(120s)`. Read timeout adalah penjaga terhadap server yang berhenti mengirim tetapi tetap membuka koneksi; tanpanya aplikasi akan menggantung selamanya dalam keadaan di mana pengguna hanya bisa memaksa berhenti.

---

## 7. Loop agen (ChatEngine)

### 7.1 Loop-nya

```kotlin
fun run(messages, config, mode, maxToolCalls = 0): Flow<EngineEvent> = flow {
    val provider = providerFactory(config.provider)     // guarded in try
    var history = messages
    var toolCallsUsed = 0
    while (true) {
        val budgetReached = maxToolCalls > 0 && toolCallsUsed >= maxToolCalls
        var text = StringBuilder(); var thinking = StringBuilder()
        var toolCalls = emptyList<ToolCall>()
        try {
            val tools = if (budgetReached) emptyList() else toolCatalog.specsFor(mode)
            val sys = ChatMessage(role = SYSTEM, content = systemPromptFor(mode))
            provider.chatStream(listOf(sys) + history, effectiveConfig, tools).collect { ev -> … }
        } catch (e: Exception) {
            if (e is CancellationException) throw e        // user stop propagates
            emit(EngineEvent.Failed(e.message ?: e.javaClass.simpleName))
            return@flow
        }
        val assistantMsg = ChatMessage(role = ASSISTANT, content = text, toolCalls = if (budgetReached) emptyList() else toolCalls, …)
        emit(EngineEvent.AssistantFinished(assistantMsg))
        if (toolCalls.isEmpty() || budgetReached) break    // ← normal exit / final summary pass
        toolCallsUsed += toolCalls.size
        history = history + assistantMsg
        for (call in toolCalls) {
            val spec = toolCatalog.find(call.name)
            val decision = gate(mode, spec, call)
            val (status, resultText) = when (decision) {
                Denied  → DENIED dengan "Alat ditolak dijalankan: reason"
                NeedsConfirm → … confirm dialog … then execute
                Allow   → executeTool(call, spec)
            }
            emit(EngineEvent.ToolCallFinished(finished))
            history = history + ChatMessage(role = TOOL, content = resultText, toolCallId = call.id, …)
        }
    }
    emit(EngineEvent.Completed)
}.flowOn(Dispatchers.IO)
```

`maxToolCalls = 0` berarti **tanpa batas**, yang merupakan bawaan: tugas panjang tidak pernah terpotong di tengah tugas. Pengaturan → Obrolan menyediakan batas opsional (50/100/200/500) untuk kontrol biaya. Ketika batas yang dikonfigurasi tercapai, engine menambahkan dorongan sistem, menonaktifkan alat untuk satu putaran terakhir, dan meminta model merangkum apa yang sudah dikerjakan dan apa yang tersisa; engine **tidak** menggagalkan proses. Setiap hasil alat sudah tersimpan dalam percakapan, jadi melanjutkan cukup berarti memulai dari transkrip. Jika model mengabaikan dorongan itu dan mengembalikan panggilan alat lagi, engine tetap mengakhiri proses dengan rangkuman cadangan yang terlihat, bukan gelembung kosong.

**Perkiraan rencana.** Prompt BUILD/MAX meminta model menyebutkan rencana satu baris beserta perkiraan jumlah panggilan sebelum memulai tugas multi-langkah, dan subjudul obrolan menampilkan penghitung panggilan alat langsung selama proses berjalan.

### 7.2 Mengapa `Flow` dan bukan callback

Engine adalah **cold flow**: tidak ada yang berjalan sampai ada yang mengoleksi. Ini memberi ViewModel:

- Konkurensi terstruktur (`viewModelScope.launch { engine.run(...).collect { … } }`; membatalkan job membatalkan flow).
- Aliran peristiwa yang natural (delta / selesai / gagal).
- Komposabilitas (map, filter, timeout…).

### 7.3 System prompt: rekayasa prompt per mode

```kotlin
private fun systemPromptFor(mode: AppMode): String = when (mode) {
    AppMode.CHAT -> "Kamu adalah asisten AI yang ramah, jawab pertanyaan pengguna dengan bahasa yang ringkas dan akurat. Kamu tidak perlu dan tidak diizinkan memanggil alat apa pun."
    AppMode.PLAN -> "Kamu sekarang berada di mode Plan (perencanaan). Kamu hanya boleh menganalisis, menyusun rencana, dan melihat informasi hanya-baca, dan sama sekali dilarang menjalankan operasi apa pun yang mengubah sesuatu. …"
    AppMode.BUILD -> "Kamu sekarang berada di mode Build (pembangunan). Kamu dapat memanggil alat untuk membantu pengguna menyelesaikan tugas, tetapi sebelum setiap alat dijalankan sistem akan meminta konfirmasi pengguna, jadi ajukan panggilan alat dengan berani dan wajar, dan jelaskan tujuan setiap langkah."
    AppMode.MAX -> "Kamu sekarang berada di mode Max (maksimum). Kamu dapat memanggil alat secara mandiri dan berkelanjutan untuk menyelesaikan tugas pengguna, tanpa perlu meminta konfirmasi setiap kali. …"
}
```

Prompt dan gerbang (§8) adalah **dua lapisan independen**: prompt memberi tahu model apa yang sebaiknya dipilih; gerbang *menegakkannya* apa pun yang dilakukan model. Pertahanan berlapis.

### 7.4 Validitas hasil alat: bug percakapan teracuni

OpenAI mensyaratkan: setiap entri `tool_calls` di pesan asisten harus punya pesan berperan `tool` yang sesuai dengan `tool_call_id` yang sama, jika tidak API mengembalikan HTTP 400. Aturan yang sama berlaku untuk Anthropic (`tool_result`) dan Gemini (`functionResponse`).

Skenario berbahaya: pengguna menekan **berhenti** saat engine menunggu konfirmasi atau sedang mengeksekusi alat. Engine dibatalkan sebelum sempat memancarkan `ToolCallFinished`, tetapi pesan asisten (dengan panggilan alat PENDING) sudah tersimpan. Pesan berikutnya dalam percakapan itu akan terus gagal 400 selamanya: *percakapan teracuni*.

Perbaikannya (v0.20.1), di `ChatViewModel.stop()`:

```kotlin
private suspend fun failPendingToolCalls() {
    val entity = pendingAssistantEntity ?: return
    val calls = decodeToolCalls(entity.toolCallsJson)
    val pending = calls.filter { it.status == PENDING }
    if (pending.isEmpty()) return
    // 1. perbarui entitas asisten: PENDING → REJECTED, hasil = "Dibatalkan"
    repository.updateMessage(entity.copy(toolCallsJson = encode(cancelled)))
    // 2. insert a TOOL message per cancelled call so the API sees a complete pair
    pending.forEach { call ->
        repository.insertMessage(domainToMessage(
            ChatMessage(role = TOOL, content = "Dibatalkan", toolCallId = call.id, toolName = call.name),
            entity.conversationId
        ))
    }
    pendingAssistantEntity = null
}
```

Ini contoh bagus dari **invarian kebenaran** (riwayat percakapan harus berupa transkrip panggilan alat yang valid) yang ditemukan lewat analisis mode kegagalan, bukan lewat pengujian.

**Kemunculan kembali tahun 2026 (v0.27.8).** Menghentikan saat eksekusi alat masih menghasilkan panggilan menggantung dengan cara berbeda: `stop()` memperbaiki `pendingAssistantEntity` lalu memanggil `persistStreamingPartial()`, yang menganggap placeholder eksekusi alat (teks kosong, panggilan alat RUNNING) sebagai putaran baru dan menyisipkannya sebagai pesan asisten kedua, tanpa hasil alat. Permintaan berikutnya lalu gagal 400 dengan pesan *"An assistant message with 'tool_calls' must be followed by tool messages…"* (dilaporkan di DeepSeek pada mode Max).

Kini dua lapisan mencegahnya:

1. `persistStreamingPartial()` tidak pernah menyimpan pesan yang teksnya kosong; status streaming khusus-alat adalah status UI, bukan entri transkrip.
2. Sebelum setiap permintaan, `repairToolTranscript()` (`core/chat/ToolTranscriptRepair.kt`) menelusuri riwayat yang dimuat dan menambahkan hasil alat sintetis `"Dibatalkan (giliran sebelumnya terputus, dilengkapi otomatis)"` untuk setiap panggilan alat yang tidak memiliki `toolCallId` yang cocok (sekaligus menormalkan id kosong). Ini menyembuhkan percakapan lama yang teracuni dan setiap jalur pembatalan di masa depan, termasuk matinya proses di tengah alat, tanpa membuang konteks.

---

## 8. Mode & gerbang keamanan

### 8.1 Empat mode

| Mode | Alat | Konfirmasi | Kasus pemakaian |
|---|---|---|---|
| `Chat` | tidak ada | — | percakapan biasa |
| `Plan` | hanya-baca saja | tidak ada | analisis, saran, perencanaan |
| `Build` | semua | setiap alat | tugas yang diawasi pengguna |
| `Max` | semua | tidak ada | tugas multi-langkah mandiri |

### 8.2 Implementasi gerbang

```kotlin
private fun gate(mode: AppMode, spec: ToolSpec?, call: ToolCall): GateResult {
    if (mode == AppMode.CHAT) return GateResult.Denied("Mode Chat tidak menjalankan alat")
    val s = spec ?: return GateResult.Denied("Alat tidak dikenal: ${call.name}")
    if (mode == AppMode.PLAN && !s.readOnly) return GateResult.Denied("Mode Plan hanya mengizinkan alat hanya-baca")
    if (mode == AppMode.BUILD) return GateResult.NeedsConfirm
    return GateResult.Allow
}
```

Setiap alat mendeklarasikan `readOnly`; gerbang adalah satu-satunya tempat yang memercayainya. Perhatikan `spec == null` → DENIED: bahkan jika model berhalusinasi nama alat, engine menolak alih-alih crash.

### 8.3 Pemilihan alat per mode: `ToolRegistry.specsFor`

```kotlin
override fun specsFor(mode: AppMode): List<ToolSpec> = when (mode) {
    AppMode.CHAT -> emptyList()                                    // no tools advertised at all
    AppMode.PLAN -> builtinTools.filter { it.readOnly }.map { it.spec() }
    AppMode.BUILD, AppMode.MAX -> builtinTools.map { it.spec() }
}
```

Mode `Chat` **bahkan tidak mengiklankan alat** dalam permintaan, sehingga model tidak bisa memanggil apa yang tidak dilihatnya. Ini lebih murah (permintaan lebih kecil) sekaligus lebih aman (model tidak pernah mencoba).

---

## 9. Alur konfirmasi

### 9.1 Memisahkan engine dan UI

Engine berjalan di `Dispatchers.IO`; dialog hidup di Compose. Keduanya berkomunikasi lewat:

1. `MutableSharedFlow<ToolCall>` dengan `extraBufferCapacity = 8`: arah engine → UI.
2. Sebuah `CompletableDeferred<Boolean>` per panggilan: arah UI → engine.

```kotlin
// Engine side (NeedsConfirm branch)
val deferred = CompletableDeferred<Boolean>()
pendingConfirms[call.id] = deferred
val allow = if (!confirmRequestsFlow.tryEmit(call)) {       // no subscriber / buffer full?
    pendingConfirms.remove(call.id)
    false                                                   // safe fallback: REJECTED
} else {
    try { withTimeout(300_000) { deferred.await() } }
    catch (e: CancellationException) { throw e }            // user stopped the whole job
    catch (e: Exception) { false }                          // timeout → reject
    finally { pendingConfirms.remove(call.id) }
}
```

```kotlin
// ViewModel
init {
    viewModelScope.launch {
        engine.confirmRequests.collect { call ->
            if (runJob?.isActive == true) {
                _state.update { it.copy(confirmRequest = ConfirmRequest(call, it.mode)) }
            }
        }
    }
}
fun respondConfirm(allow: Boolean) {
    state.confirmRequest?.let { req ->
        engine.respond(req.call.id, allow)                  // → deferred.complete(allow)
        _state.update { it.copy(confirmRequest = null) }
    }
}
```

Dialog menawarkan **Izinkan / Tolak / Hentikan seluruh tugas**. Berhenti membatalkan `runJob`, yang membatalkan flow, yang membatalkan `deferred.await()` lewat propagasi `CancellationException` (jalur `throw e`), lalu `failPendingToolCalls()` (lihat §7.4) menjaga riwayat tetap valid.

### 9.2 Penanganan kegagalan `tryEmit`

`MutableSharedFlow.tryEmit` mengembalikan `false` bila tidak ada pelanggan atau buffer penuh (mis. 8+ konfirmasi bersamaan). Kode lama diam-diam mengabaikan hasilnya: pengguna tidak akan pernah melihat dialog dan panggilan akan habis waktu setelah 5 menit. Perbaikannya beralih ke REJECTED seketika dengan pesan eksplisit, sehingga permintaan yang terjatuh tidak pernah dapat menghentikan loop.

---

## 10. Sistem alat

### 10.1 Kontrak DeviceTool

```kotlin
interface DeviceTool {
    val name: String
    val description: String       // ← prompt material for the LLM
    val readOnly: Boolean
    val parameters: JsonObject    // ← JSON Schema for argument generation
    suspend fun execute(context: ToolContext, arguments: JsonObject): String
}
```

Karena `description` dan `parameters` adalah *satu-satunya* informasi yang dimiliki model tentang alat, menulisnya dengan baik adalah rekayasa prompt:

- Jelaskan **kapan** memakai alat dan **apa yang dikembalikan**.
- Nyatakan parameter wajib vs opsional.
- Kembalikan string yang mudah dibaca manusia, karena disisipkan ke riwayat percakapan dan dikirim ulang ke model pada putaran berikutnya.

### 10.2 Contoh alat yang representatif

```kotlin
// GetTimeTool.kt
class GetTimeTool : DeviceTool {
    override val name = "get_time"
    override val description = "Cek tanggal dan waktu saat ini: tahun/bulan/tanggal/hari/jam/menit/detik, serta zona waktu. Alat hanya-baca."
    override val readOnly = true
    override val parameters = schemaOf()

    override suspend fun execute(context: ToolContext, arguments: JsonObject): String {
        // …mengembalikan "Tanggal: Senin, 24 Agustus 2026\nWaktu: 14:05:33\nZona waktu: Asia/Jakarta (UTC+7)\nUnix timestamp: 1787544333"
    }
}
```

### 10.3 Pola penguraian argumen

```kotlin
val percent = arguments["percent"]?.jsonPrimitive?.content?.toIntOrNull()
    ?.coerceIn(0, 100) ?: return "Parameter percent tidak valid"
```

Setiap alat memvalidasi dan membersihkan argumen sebelum bertindak: argumen buruk mengembalikan string kesalahan, bukan melempar exception. Karena kesalahan diumpankan balik ke model, model dapat **mengoreksi diri** pada putaran berikutnya (perilaku emergen yang nyata: LLM membaca "Parameter percent tidak valid" lalu mencoba lagi dengan nilai yang valid).

### 10.4 Kalkulator: evaluator aman, bukan eval

```kotlin
class Parser(private val input: String) {
    fun parse(): String { /* recursive descent: expression → term → factor → number */ }
}
```

`calculator` mengimplementasikan **parser recursive-descent** (sekitar 80 baris) yang mendukung `+ - * / % ^ ( )` dan desimal. Sengaja *bukan* `eval()` atau mesin JavaScript, sehingga eksekusi kode sembarang mustahil. Ini juga contoh pengajaran yang bagus dari tata bahasa → kode.

---

## 11. Skill (gaya opencode)

### 11.1 Format SKILL.md

```markdown
---
name: plan-reminder
description: Help the user plan a reminder
allowed-tools: get_time
---

1. Ask for the reminder content and time
2. Call get_time to check the current time
3. Reply with a short summary of the plan
```

`SkillRepository` mengurai frontmatter YAML (`name`, `description`, `allowed-tools`) dan menyimpan isinya sebagai instruksi. Skill hanya berisi instruksi teks; ia tidak mendefinisikan alat sendiri.

### 11.2 Memuat skill sebagai alat

`LoadSkillTool` sendiri adalah alat: ia mendaftar skill yang dimuat (nama + deskripsi) sehingga model tahu kapan "memanggil" satu skill. Setelah dimuat, instruksi skill dikembalikan ke model, yang lalu menjalankan langkah-langkahnya dengan alat bawaan yang diizinkan (`allowed-tools`).

### 11.3 Merekam skill dari percakapan

Fitur "simpan sebagai skill" menserialisasi `tool_calls` pesan asisten menjadi SKILL.md:

```kotlin
fun saveAsSkill() {
    val history = repository.getHistory(currentConversationId)
    val toolUses = history.flatMap { it.toolCallsJson?.let { decode(it) } ?: emptyList() }
    if (toolUses.isEmpty()) { notify("Tidak ada panggilan alat di percakapan ini untuk dijadikan skill"); return }
    // membangun: name: skill_<ts>\n---\nLangkah: panggil alat dengan argumen yang direkam
    skillRepository.import("$skillName.md", md)
}
```

---

## 12. Penyimpanan & manajemen state

### 12.1 Skema Room (v11)

```
conversations (id, title, provider, model, mode, agentId, pinned, archived, createdAt, updatedAt)
messages (id, conversationId FK, role, content, toolCallsJson, toolCallId, toolName,
          model, mode, status, usageInput, usageOutput, attachmentsJson,
          thinkingText, thinkingSignature, starred, createdAt)
memories     (id, conversationId, type [memory|snapshot], content, createdAt, updatedAt)
agents       (id, name, description, provider, baseUrl, apiKey[encrypted], model,
              temperature, maxTokens, reasoning, systemPrompt, isDefault, createdAt, updatedAt)
```

### 12.2 Migrasi eksplisit: tidak pernah destruktif

```kotlin
val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN agentId INTEGER")
        db.execSQL("CREATE TABLE IF NOT EXISTS agents (…)")
    }
}
// …added to .addMigrations(MIGRATION_1_2 … MIGRATION_10_11)

val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS repeat_tasks")
        db.execSQL("DROP TABLE IF EXISTS automations")
    }
}
```

Setiap versi skema menambahkan migrasi tulisan tangan sehingga pengguna yang ada ter-upgrade di tempat. Versi 11 menghapus tabel `repeat_tasks` dan `automations` yang dulu dipakai fitur yang kini sudah dibuang. `fallbackToDestructiveMigration()` sengaja tidak ada: kehilangan data tidak dapat diterima untuk aplikasi obrolan.

### 12.3 Pengamatan berbasis Flow

```kotlin
@Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>
```

Room memancarkan ulang setiap kali tabel invalid: daftar obrolan, daftar percakapan, dan statistik semuanya memakai pola ini dengan `collectAsStateWithLifecycle(initialValue = …)`. Inilah yang membuat perilaku "AI menyisipkan pesan → UI memperbarui diri" bekerja tanpa panggilan penyegaran manual.

### 12.4 Pemetaan entitas ↔ domain

`ChatRepository.messageToDomain()` dan `domainToMessage()` menerjemahkan antara entitas Room dan `ChatMessage`. Field JSON (`toolCallsJson`, `attachmentsJson`) didekode dengan `runCatching{…}.getOrDefault(emptyList())`: field yang rusak turun menjadi kosong alih-alih membuat percakapan crash.

### 12.5 Pengaturan & kripto

`SettingsRepository` membungkus `SharedPreferences` dengan getter/setter bertipe. API key disimpan terenkripsi:

```kotlin
// KeyStoreCrypto: AES/GCM key inside AndroidKeyStore
val encrypted = encrypt(apiKey)      // random IV + ciphertext
// decrypt on read; wrong keystore (e.g. after backup restore) → key cleared, user re-enters
```

---

## 13. Lapisan UI

### 13.1 Struktur layar

```
AppNav
 ├── ConversationListScreen        (search, pinned, list via Room Flow)
 └── ChatScreen
      ├── TopAppBar (title, agent + usage subtitle, mode selector, more menu → Select Agent)
      ├── LazyColumn (messages)
      │     └── MessageItem
      │           ├── UserBubble      (right-aligned, primaryContainer)
      │           └── AiBlock
      │                 ├── meta row (model · mode · time)
      │                 ├── markdown bubble (+ blinking cursor while streaming)
      │                 ├── ToolCallCard per call (compact row + Details expander)
      │                 ├── progres "Langkah selesai: n / m" saat alat sedang di-streaming
      │                 ├── HighlightedCodeCard per extracted code block
      │                 ├── link cards
      │                 └── ThinkingCard (collapsible reasoning)
      ├── ConfirmDialog (Izinkan/Tolak/Hentikan seluruh tugas)
      ├── WelcomePanel (mode-aware example chips)
      └── InputBar (attach, mic, OutlinedTextField, send/stop)
```

### 13.2 Detail render pesan

- **Perataan pengguna vs AI**: gelembung pengguna rata kanan (`Arrangement.End`), blok AI di kiri dengan baris avatar.
- **Kartu alat**: ringkas secara bawaan, satu baris berisi nama alat, `StatusBadge` berwarna dan tombol Detail; saat dibuka menampilkan argumen monospace dan hasil (elipsis 8 baris dengan buka-semua).
- **Blok kode**: diekstrak dari markdown dan dirender terpisah dengan latar gelap dan tombol salin. Ini mencegah renderer markdown tersendat pada kode panjang/berpagar dan memberi pengalaman terasa native.
- **Berpikir**: `ThinkingCard` melipat teks penalaran panjang dengan baris buka/ciutkan.
- **Normalisasi markdown** (`core/chat/MarkdownNormalizer.kt`): model menghasilkan markdown yang menolak diurai CommonMark. Sebelum dirender kami memperbaikinya: pipa lebar penuh `｜` -> `|`, tabel tanpa pipa luar tetap diurai komponen tabel kami, spasi yang hilang setelah `#`/`-`/`1.`/`>` disisipkan, bintang lebar penuh dikonversi, dan penekanan di dalam kata CJK (`中文**加粗**中文`, yang ditolak CommonMark) diberi hair space agar dapat dirender. Setiap perbaikan memiliki unit test.

### 13.3 Scroll mengikuti bawah yang digerakkan layout

Dua pendekatan sebelumnya gagal: `animateScrollToItem` pada setiap delta bergetar (tinggi item streaming berubah di setiap frame), dan loop "paksa-scroll tiap 80 ms" berebut dengan layout asinkron Markdown sehingga terlihat bergetar. Implementasi sekarang (v0.27.3) **digerakkan layout**: setelah setiap pass layout, jika bagian bawah item terakhir melebihi viewport dan mode mengikuti aktif, scroll tepat sekali. Tanpa loop, tanpa delay, tanpa persaingan dengan sistem layout.

```kotlin
// wasAtBottom flips false ONLY on real user drags. Using isScrollInProgress
// here was a trap: programmatic scrolls also set it, which silently
// disabled the follow.
var wasAtBottom by remember { mutableStateOf(true) }
var forceFollow by remember { mutableStateOf(false) }
LaunchedEffect(listState) {
    listState.interactionSource.interactions.collect { interaction ->
        if (interaction is DragInteraction.Start) {
            wasAtBottom = false
            forceFollow = false
        }
    }
}
LaunchedEffect(listState) {
    snapshotFlow { listState.isScrollInProgress }
        .collect { scrolling ->
            if (!scrolling) {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 }
                val pinned = last != null && last.offset >= 0 &&
                    last.offset + last.size <= info.viewportEndOffset + 1
                if (pinned) wasAtBottom = true
            }
        }
}

// The follower: fires after every layout; scrolls once only when the last
// item actually overflows the viewport (content grew), then stays quiet
// until the next growth.
LaunchedEffect(listState) {
    snapshotFlow { listState.layoutInfo }
        .collect { info ->
            val total = info.totalItemsCount
            if (total == 0) return@collect
            if (!forceFollow && !wasAtBottom) return@collect
            if (listState.isScrollInProgress) return@collect
            val last = info.visibleItemsInfo.lastOrNull { it.index == total - 1 }
            if (last == null || last.offset + last.size > info.viewportEndOffset + 2) {
                runCatching { listState.scrollToItem(total - 1, Int.MAX_VALUE) }
            }
        }
}
```

Wawasannya: **jangan polling, bereaksilah**. `snapshotFlow { layoutInfo }` menyala tepat saat layout berubah (yaitu saat teks streaming bertambah). Memeriksa "apakah item terakhir meluap?" membuat setiap pertumbuhan menjadi paling banyak satu scroll. Scroll terprogram tidak memicu ulang jalur mengikuti, dan seretan pengguna dideteksi lewat `DragInteraction` alih-alih `isScrollInProgress` yang ambigu.

### 13.4 Kursor streaming

```kotlin
val blinkAlpha = rememberInfiniteTransition(label = "cursor").animateFloat(
    initialValue = 0.7f, targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse)
).value
// content + if (streaming) "▋" rendered with Modifier.alpha(blinkAlpha)
```

`▋` berkedip yang ditambahkan ke teks langsung: penanda "mengetik" termurah yang mungkin.

### 13.5 Pengukur penggunaan

`totalPromptTokens` (dari pesan terakhir yang memuat penggunaan) dibandingkan dengan `ModelCatalog.entryFor(provider, model).contextWindow` dirender sebagai `· 1.2K token (3%)` di bilah atas, memberi pengguna gambaran langsung sisa konteks.

### 13.6 Input suara & loop bebas tangan

- `SpeechInputHelper` (SpeechRecognizer) → hasil parsial mengalir ke kotak input.
- `SpeechPlayer` (TextToSpeech) membacakan balasan.
- **Mode asisten suara**: setelah AI selesai, ia membacakan balasan, mikrofon terbuka otomatis, dan jawaban lisan pengguna terkirim otomatis. Loop bebas tangan utuh yang diimplementasikan sebagai mesin status kecil di ViewModel.

---

## 14. Desain keamanan

| Ancaman | Mitigasi |
|---|---|
| Prompt injection yang mencoba menjalankan perintah berbahaya | Tidak ada alat yang menjalankan shell sembarang; setiap alat dibatasi semantiknya sendiri, dan mode Build meminta konfirmasi sebelum eksekusi |
| Injeksi argumen alat | Setiap alat memvalidasi: `calculator` memakai parsernya sendiri, jalur/URL divalidasi per alat |
| URL berbahaya | `web_read`/`web_search` hanya menerima URL publik (`isPublicHttpUrl` menolak localhost dan jaringan privat); `web_read`/`fetch_rss` membatasi ukuran respons lewat batas bawaan jsoup |
| Pencurian API key | Kunci dienkripsi dengan Android Keystore; tidak pernah dicatat di log; hanya disimpan sebagai ciphertext |
| Penyalahgunaan di latar belakang | Aplikasi tidak menjalankan alat di latar belakang: alat hanya berjalan saat pengguna sedang dalam percakapan, dan izin yang dideklarasikan sangat sedikit (internet, notifikasi, mikrofon) |
| DoS lewat konten panjang | Keluaran streaming dibatasi read timeout + timeout alat + batas panggilan alat opsional yang diatur pengguna (tanpa batas secara bawaan) |
| Balapan eksekusi bersamaan | Mutex engine (`runJob?.isActive`) |

---

## 15. Matriks penanganan kesalahan

| Lapisan | Kegagalan | Hasil |
|---|---|---|
| Jaringan | 401/403/404/429/timeout/connect | Dipetakan ke pesan ramah berbahasa Indonesia (`smartError`) |
| Streaming | Chunk rusak | Chunk dilewati, stream berlanjut |
| Streaming | Server macet | Read timeout 120s → `Failed` → kesalahan di UI + bagian parsial disimpan |
| Alat | Argumen buruk | Alat mengembalikan string kesalahan → diumpankan ke model → mengoreksi diri |
| Alat | Eksekusi melempar exception | `ToolCallStatus.FAILED` dengan pesan, tampil sebagai lencana merah |
| Alat | Menggantung | Timeout 60s di engine |
| Konfirmasi | Dialog terjatuh | Cadangan REJECTED (pemeriksaan tryEmit) |
| Konfirmasi | Timeout pengguna | Timeout 300s → REJECTED |
| Berhenti | Di tengah putaran alat | Panggilan PENDING → REJECTED + pesan alat `Dibatalkan` (riwayat tetap valid) |
| DB | Kolom JSON rusak | `runCatching` → bawaan kosong, percakapan berlanjut |
| Jaringan alat | Situs tidak dapat dijangkau | Alat mengembalikan `ERROR:…` yang dapat dibaca model |

---

## 16. Pelajaran rekayasa dari bug nyata

Semua ini adalah bug yang ditemukan lewat review kode dan diperbaiki di v0.20.1, masing-masing dengan pelajaran yang bisa dibawa ke proyek lain:

1. **Retry yang tidak pernah mengulang.** `Call` OkHttp sekali pakai; mengulang instance yang sama selalu melempar exception. Perbaikan: `call.clone()`. *Pelajaran: pastikan jalur kegagalan benar-benar dijalankan. Logika retry yang mati lebih buruk daripada tanpa retry (karena menyembunyikan kesalahan).*

2. **Percakapan teracuni.** Berhenti di tengah putaran alat meninggalkan `tool_calls` asisten tanpa respons `tool` yang sesuai → setiap permintaan berikutnya gagal 400. Perbaikan: saat berhenti, tandai panggilan PENDING sebagai REJECTED dan sisipkan hasil `Dibatalkan`. *Pelajaran: jaga invarian protokol eksternal bahkan di jalur pembatalan.*

3. **Korupsi state akibat konkurensi.** `isRunning` diatur tidak konsisten di berbagai titik masuk (kirim/edit), sehingga dua pipeline agen bisa berebut state mutable bersama. Perbaikan: satu mutex di titik masuk engine. *Pelajaran: tegakkan invarian konkurensi di satu tempat, bukan di setiap pemanggil.*

4. **Menggantung tanpa akhir.** `readTimeout(0)` + tanpa timeout alat berarti server macet atau alat yang menggantung dapat memblokir selamanya. Perbaikan: read timeout 120s dan timeout alat 60s. *Pelajaran: setiap titik suspensi butuh timeout. "Seharusnya merespons" bukan timeout.*

5. **Injeksi shell lewat argumen alat.** Perbaikan: validasi whitelist. *Pelajaran: string apa pun yang berakhir di shell/URL/jalur adalah permukaan injeksi; validasi di batas.*

6. **Kebocoran resource menumpuk.** Bitmap, stream, recognizer, koneksi. Perbaikan: `try/finally`, `use`, `recycle()` eksplisit. *Pelajaran: pembersihan harus struktural (finally), bukan upaya terbaik.*

7. **Pesan hantu.** Cabang mati di `persistStreamingPartial` membuat pesan asisten kosong saat berhenti. Perbaikan: tulis ulang logika penggabungan dan uji jalur berhenti. *Pelajaran: kode mati di jalur panas adalah bug yang menunggu pemicu yang tepat.*

8. **Akumulasi string O(n²)** di main thread selama streaming panjang. *Pelajaran: menambahkan string imutabel dalam loop bersifat kuadratik. Akumulasikan dulu, baru publikasikan.*

9. **Formulir yang tidak bisa di-scroll.** `Modifier.verticalScroll(...).height(440.dp)` membuat kontainer scroll mengukur anak yang persis 440dp, sehingga viewport punya rentang scroll nol: kolom Base URL dan tombol "Ambil model" terpotong dan tidak terjangkau, dan pengguna melaporkan "tidak ada pilihan model". Perbaikan: letakkan batasan tinggi **di luar** scroll (`heightIn(max).verticalScroll`). *Pelajaran: urutan modifier adalah semantik layout; kontainer scroll harus menerima viewport terbatas, bukan anak yang sudah tetap. Uji formulir panjang di layar kecil sungguhan.*

10. **"Edit" yang membuat salinan.** Wizard Agent melayani buat dan edit, tetapi jalur simpan selalu membangun `AgentEntity` tanpa `id`, sehingga mengedit menyisipkan duplikat; ia juga mengumpankan ciphertext tersimpan (`enc:…`) ke probe model dan akan menghapus `isDefault` saat update. Perbaikan: bawa `editingId`, pertahankan flag bawaan, dan dekripsi lewat `toConfig` saat memakai ulang kunci tersimpan. *Pelajaran: ketika satu formulir melayani buat dan edit, identitas adalah bagian dari state formulir. Uji jalur tulis, bukan hanya judulnya.*

11. **Panggilan alat menggantung setelah Berhenti.** Berhenti saat eksekusi alat menyimpan panggilan alat asisten dua kali: `failPendingToolCalls()` memperbaiki baris pending, lalu `persistStreamingPartial()` menyisipkan placeholder streaming khusus-alat sebagai pesan asisten kedua tanpa hasil. Permintaan berikutnya gagal 400 (*"An assistant message with 'tool_calls' must be followed by tool messages…"*). Perbaikan: jangan pernah menyimpan pesan streaming khusus-alat tanpa konten, dan jalankan `repairToolTranscript()` sebelum setiap permintaan untuk menambah hasil pembatalan bagi setiap panggilan alat yang tak berpasangan. *Pelajaran: jalur pembatalan harus meninggalkan protokol eksternal dalam keadaan valid. Verifikasi transkrip setelah perbaikan, bukan sekadar bahwa perbaikan berjalan.*

---

---

## 17. Urutan belajar yang disarankan

Jika kamu ingin benar-benar belajar dari basis kode ini:

1. **Coroutine & Flow Kotlin**: `ChatEngine.run(): Flow<EngineEvent>`, `snapshotFlow` di logika scroll, dan `withTimeout`.
2. **Inversi dependensi**: kontrak `DeviceTool`/`ToolContext` di `:skills` yang dirakit oleh `:app`, sehingga modul tidak saling bergantung secara melingkar.
3. **UI reaktif**: satu `StateFlow` imutabel, `collectAsStateWithLifecycle`, `derivedStateOf`, ticker 10 Hz yang memisahkan laju peristiwa dari laju render.
4. **Room**: entitas, DAO, pengamatan Flow, migrasi eksplisit v1→v11.
5. **Jaringan**: OkHttp + parser SSE buatan sendiri, retry `call.clone()`, disiplin read-timeout, terjemahan wire per vendor (OpenAI/Anthropic/Gemini).
6. **Integrasi sistem Android**: Android Keystore untuk API key, `SpeechRecognizer`, TTS, dan izin runtime (notifikasi, mikrofon).
7. **Desain agen**: gerbang berbasis mode (Chat/Plan/Build/Max), pertahanan berlapis prompt+penegakan, loop konfirmasi lewat `SharedFlow` + `CompletableDeferred`, dan invarian transkrip alat (§7.4).
8. **Keamanan**: izin minimal dengan UI status langsung, validasi input di batas, evaluator tersandbox, pemotongan keluaran, mutex konkurensi.
9. **Rekayasa UX**: scroll terpaku yang digerakkan layout (bereaksi pada layout, jangan polling), kursor berkedip, kartu alat ringkas dengan perluasan detail, pencarian dalam obrolan, ekstraksi blok kode, panel sambutan yang sadar mode.

Setiap topik ini terpetakan ke berkas nyata yang berfungsi di repo. Mulailah dari `ChatViewModel.send()` dan telusuri melalui `ChatEngine.run()` hingga `OpenAiProvider.chatStream()`, lalu bercabang ke alat bawaan.

---

*Proyek: Milo AI, agen AI Android native dengan alat bawaan berbasis web dan utilitas, Skill gaya opencode, dan Agent per percakapan. Dikembangkan oleh Hanz Dev Official (hanzzcodee@gmail.com). Dibangun dengan Kotlin, Jetpack Compose, Room, OkHttp, dan kotlinx.serialization.*

---

## 18. Memulai: build, jalankan, uji, debug

### 18.1 Persyaratan

| Alat | Versi | Catatan |
| --- | --- | --- |
| JDK | 17 | Toolchain Gradle menargetkan JVM 17 |
| Android SDK | platform 36 + build-tools 36.0.0 | `compileSdk = 36`, `minSdk = 26` |
| Gradle | wrapper (8.14.3) | selalu pakai `./gradlew` |
| Perangkat | Android 8.0+ (emulator boleh) | Android 14/15 disarankan |

Tidak perlu API key untuk membangun. Untuk benar-benar mengobrol kamu butuh key dari endpoint kompatibel OpenAI (DeepSeek, OpenAI, gateway lokal, …) yang dikonfigurasi lewat wizard Agent di aplikasi.

### 18.2 Varian build

Aplikasi hanya punya satu varian: **lite**. Tidak ada lagi product flavor `full`/`lite`.

| Build | Isi | Keluaran |
| --- | --- | --- |
| `debug` | build debug, tanpa pengecilan kode | `app/build/outputs/apk/debug/app-debug.apk` |
| `release` | lite: R8 + shrink resources, hanya resource bahasa Indonesia, tanpa model OCR | `app/build/outputs/apk/release/` |

```bash
./gradlew assembleFullDebug          # the usual dev build
./gradlew assembleLiteDebug          # lite variant
./gradlew assembleFullRelease        # release (unsigned; signing is manual, see ch. 26)
```

OCR dokumen/gambar adalah satu antarmuka (`OcrEngine`) dengan implementasi stub: `app/src/main/java/.../ui/chat/OcrEngineProvider.kt` mengembalikan pesan bahwa versi lite tidak mendukung OCR. Bila ingin mengaktifkannya kembali, ganti stub dengan implementasi ML Kit dan tambahkan dependensinya.

### 18.3 Pasang & jalankan

```bash
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
adb shell am start -n com.miloai/.MainActivity
```

Alur pertama kali: Pengaturan → Agent → Agent Baru → tempel API key → wizard mendeteksi penyedia dari awalan key (`sk-ant-` → Claude, `AIza` → Gemini, selain itu kompatibel OpenAI), mengambil daftar model dan menyimpan. Tidak punya key? Arahkan Base URL ke mock lokal mana pun (bagian berikutnya).

### 18.4 Tes

```bash
./gradlew :core:testDebugUnitTest :skills:testDebugUnitTest :providers:testDebugUnitTest
```

Cakupan saat ini berfokus pada logika murni yang pernah rusak: parsing SSE, normalisasi markdown (termasuk tebal CJK dan tabel tanpa pipa), kalkulator (termasuk fungsi ilmiah), penggabungan/parsing argumen alat, konversi satuan, inferensi key agent. UI dan kode framework Android diverifikasi di perangkat; basis kode sengaja menjauhkan logika dari composable agar tetap dapat diuji.

### 18.5 Build release di GitHub

Workflow `.github/workflows/release.yml` hanya berjalan manual (Actions → Build Release APK → Run workflow) dan menghasilkan `MiloAI-1.0.0.apk` beserta checksum SHA-256 sebagai artifact. Tidak ada workflow otomatis pada push atau pull request, jadi jalankan tes secara lokal (`./gradlew :core:testDebugUnitTest :skills:testDebugUnitTest :providers:testDebugUnitTest`) sebelum rilis.

### 18.6 Debugging dengan mock server lokal

Cara tercepat menguji seluruh pipeline (streaming, panggilan alat, kasus tepi markdown) tanpa menghabiskan kuota API sungguhan adalah server SSE kecil di host. Versi minimal:

```python
#!/usr/bin/env python3
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

class H(BaseHTTPRequestHandler):
    def do_POST(self):
        req = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or '{}')
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        def ev(d):
            self.wfile.write(b'data: ' + json.dumps(d).encode() + b'\n\n'); self.wfile.flush()
        # scripted behavior keyed on the model name
        if 'tool' in req.get('model', ''):
            ev({"choices": [{"delta": {"tool_calls": [
                {"index": 0, "id": "c1", "function": {"name": "get_time", "arguments": "{}"}}]}}]})
        else:
            ev({"choices": [{"delta": {"content": "hello **world**"}}]})
        ev({"choices": [{"delta": {}, "finish_reason": "stop"}]})
        self.wfile.write(b'data: [DONE]\n\n'); self.wfile.flush()

HTTPServer(('0.0.0.0', 9999), H).serve_forever()
```

Lalu buat Agent dengan Base URL `http://10.0.2.2:9999/v1` (emulator → loopback host), API key apa saja, dan nama model yang mengandung `tool` untuk memicu panggilan alat berskrip. Mengganti nama model berarti mengganti skenario; begitulah semua fitur di aplikasi ini diverifikasi.

### 18.7 Tips pengujian di emulator

- `adb shell input text` tidak dapat mengetik CJK; pakai string uji bahasa Inggris atau tempel lewat papan klip.
- `uiautomator dump` sesekali mengembalikan **snapshot basi** saat scroll/animasi. Jika ragu, ambil tangkapan layar (`adb shell screencap -p /sdcard/x.png`) dan lihat langsung; penulis repo ini lebih dari sekali tertipu dump basi.
- Berikan izin notifikasi lewat UI (Pengaturan → Izin); izin mikrofon diminta saat memakai input suara.

---

## 19. Agent: konfigurasi per percakapan

### 19.1 Modelnya

**Agent** adalah konfigurasi AI lengkap yang disimpan di tabel `agents`:

```
AgentEntity(id, name, description, provider, baseUrl, apiKey, model,
            temperature, maxTokens, reasoning, systemPrompt, isDefault,
            createdAt, updatedAt)
```

- `apiKey` disimpan **terenkripsi** (KeyStoreCrypto). Repository menulis `"enc:" + ciphertext` dan mendekripsi saat dibaca (`AgentRepository.encryptIfPlain/decrypt`), sehingga DB tidak pernah menyimpan key plaintext.
- `systemPrompt` adalah prompt kustom opsional; kosong berarti "pakai prompt mode saja".
- Tepat satu agent berstatus `isDefault` (yang pertama dibuat, atau yang ditandai eksplisit). Menghapus yang default menaikkan agent tertua yang tersisa.

### 19.2 Bagaimana percakapan menentukan konfigurasinya

`ChatViewModel.resolveConfig(state)`:

1. `state.agentId` (terikat ke percakapan) → muat agent itu → `AgentRepository.toConfig()`;
2. jika belum diatur/dihapus → agent bawaan;
3. jika sama sekali tidak ada agent → `SettingsRepository.configFor(provider)` lama.

Percakapan menyimpan `agentId`; pemilih model di menu obrolan memanggil `updateAgent(id)` yang menyimpan ikatan lewat `ChatRepository.updateMeta`. Kompresi, perangkuman memori, dan pembuatan judul semuanya memakai `resolveConfig` yang sama, sehingga percakapan berperilaku konsisten di mana-mana.

### 19.3 Wizard onboarding

`ui/agents/AgentOnboarding.kt`, empat langkah:

1. **Key & penyedia**: tempel key; `inferProviderFromKey()` menebak penyedia dari awalan dan `ModelProbe` (`GET {baseUrl}/models`) dipanggil otomatis setelah debounce singkat. Preset cepat (DeepSeek / Qwen / Kimi / GLM / SiliconFlow) mengisi Base URL dan model bawaan; penyedia dan Base URL juga dapat diatur manual. Baris status melaporkan "Terhubung, N model ditemukan" atau kegagalan yang tepat.
2. **Model**: model dari penyedia (diambil otomatis, dapat difilter bila banyak), katalog pilihan, atau id manual. Model pertama yang terdeteksi dipilih lebih dulu; **Lanjut tetap nonaktif sampai model dipilih**, sehingga Agent tidak pernah bisa disimpan dengan model kosong.
3. **Parameter**: temperature, max tokens, sakelar penalaran mendalam.
4. **Prompt**: prompt mode bawaan atau system prompt kustom → simpan.

### 19.4 Ke mana prompt kustom pergi

`ChatEngine.run()` menempatkannya sebelum prompt mode:

```kotlin
val sysContent = if (custom.isBlank()) systemPromptFor(mode)
                 else "$custom\n\n${systemPromptFor(mode)}"
```

Prompt mode menyandikan perilaku keamanan (mis. "konfirmasi sebelum bertindak") dan tidak pernah diganti; prompt kustom hanya menambah konteks persona/domain.

### 19.5 Migrasi dari konfigurasi lama

`MiloAiApp.ensureDefaultAgentFromLegacySettings()` (berjalan sekali di scope latar belakang saat aplikasi mulai): jika tabel agents kosong dan pengaturan lama memuat API key, ia mengubah pengaturan provider/baseUrl/model/temperature lama menjadi "Agent Bawaan" sehingga pengguna lama tidak pernah melihat pemilih yang kosong.

---

## 20. Pipeline pencarian web

`web_search` sengaja lebih dari sekadar "panggil satu mesin". Seluruh rantainya ada di `skills/tools/WebSearchTool.kt`.

### 20.1 Fan-out dan penggabungan

- Enam mesin berjalan **bersamaan** (`async` masing-masing): Bing, Baidu, Brave, DuckDuckGo, Mojeek, 360.
- Aturan penggabungan: penghapusan duplikat URL ternormalisasi, **maksimum 2 hasil per domain** (keragaman), **penghapusan duplikat kemiripan judul** (artikel sama, tautan berbeda).
- **Peringkat relevansi**: hasil diberi skor berdasarkan kecocokan istilah kueri (judul ×3, ringkasan ×1) lalu diurutkan. Urutan mesin saja memunculkan halaman tak relevan (kueri tentang "tanggal rilis Android 16" dulu mengembalikan beranda alat pengembang lebih dulu).
- URL dibersihkan dari parameter pelacak (`utm_*`, `spm`, `from`, …).

### 20.2 Isi dalam satu panggilan (`read_top`)

`read_top = 1` bawaan melampirkan isi hasil pertama yang *berhasil diambil*:

- ekstraksi tingkat elemen (`p, h1-h6, li, pre, blockquote` di dalam `article/main`), bukan `body.text()`; inilah yang membuang bilah penulis;
- `NOISE_RE` menyaring sekitar 20 pola boilerplate (jumlah dilihat/favorit/kode QR/amplop merah… pada situs berbahasa Mandarin);
- paragraf duplikat yang bersebelahan dibuang (halaman sumber sering mengulang isi daftar di dalam `<p>`);
- **lewati-saat-gagal**: situs yang diblokir (mis. baike.baidu.com → 403) dilewati dan hasil berikutnya dicoba, hingga 8 percobaan, sampai isi sebanyak `read_top` terkumpul;
- anggaran per isi = 5000/read_top karakter, dan anggaran pemotongan engine (6000) menjaga isi tetap utuh dalam perjalanan ke model.

### 20.3 Multi-kueri, cache, refresh

- `query` menerima **array** (≤3): pertanyaan perbandingan selesai dalam satu putaran; hasil digabung lintas kueri.
- Cache memori 5 menit dengan kunci `query|max_results|read_top`; `refresh=true` melewatinya untuk pertanyaan lanjutan yang sensitif waktu.
- Diagnostik mesin: catatan gagal-semua dan gagal-sebagian memberi tahu model apakah mengulang itu berguna.

### 20.4 Ekonomi kegagalan (mengapa pencarian dulu menghabiskan semua putaran)

- `DeviceToolRunner.healArgs` **menyembuhkan argumen sendiri** sebelum eksekusi: nilai bertipe salah dikonversi, nama parameter salah eja dikoreksi (levenshtein ≤2), array dipertahankan; hasilnya diberi catatan apa yang diperbaiki.
- `parseToolArguments` menoleransi string yang di-encode ganda dan JSON yang dibungkus prosa (beberapa gateway meng-encode ganda).
- `ChatEngine.runWithCircuitBreaker` menolak alat setelah 3 kegagalan berturut-turut dengan panduan eksplisit.
- Panggilan alat **tanpa batas secara bawaan** di semua mode. Pengaturan → Obrolan dapat menetapkan batas opsional (50/100/200/500); saat tercapai, engine membuat satu putaran terakhir tanpa alat yang merangkum progres alih-alih gagal, dan transkrip menyimpan setiap hasil untuk dilanjutkan.

---

## 21. Memori jangka panjang

### 21.1 Penyimpanan

Tabel `memories` (v9) menyimpan dua jenis baris, dibedakan oleh `type`:

- `memory`: fakta yang dirangkum ("Nama pengguna adalah …"), disisipkan ke setiap permintaan;
- `snapshot`: 6 giliran terakhir yang disimpan sebelum kompresi konteks, dapat dipulihkan lewat "Impor obrolan terbaru".

### 21.2 Perangkuman

- **Otomatis**: setiap 10 giliran selesai (`autoDistillCount` di `ChatViewModel`), perangkuman latar belakang berjalan diam-diam.
- **Manual**: menu ⋮ → Rangkum info penting.
- Prompt perangkuman meminta model hanya poin *baru* dengan memperhatikan memori yang ada; hasilnya disaring (panjang 3–200, tanpa gema prompt) lalu disisipkan.

### 21.3 Injeksi

`injectMemory()` menempatkan pesan sistem berisi daftar memori di depan riwayat pada setiap generasi; inilah alasan asisten "mengingatmu" lintas sesi tanpa state di sisi server.

### 21.4 Interaksi dengan kompresi

Saat penggunaan melewati 85% jendela konteks model, aplikasi memberi peringatan dan mengompres otomatis setelah giliran: 6 giliran terakhir di-snapshot, riwayat lebih lama diganti pesan rangkuman, dan snapshot tetap dapat diimpor.

---

## 22. Memperluas aplikasi: cara menambah sesuatu

### 22.1 Menambah alat (tugas paling umum)

1. Buat kelas di `skills/src/main/java/com/miloai/skills/tools/`:

```kotlin
class MyTool : DeviceTool {
    override val name = "my_tool"                       // snake_case, unique
    override val description = "One sentence the MODEL reads to decide when to use it. State the argument formats."
    override val readOnly = false                       // true => allowed in Plan mode
    override val parameters = schemaOf(
        "text" to stringProp("what this argument means"),
        "count" to intProp("integer argument, default 3"),
        required = listOf("text")
    )
    override suspend fun execute(context: ToolContext, arguments: JsonObject): String {
        val text = arguments["text"]?.jsonPrimitive?.content ?: return "Parameter text tidak valid"
        // ... do the work ...
        return "result the model will read"
    }
}
```

2. Daftarkan di daftar alat `MiloAiApp.kt` (`val tools: List<DeviceTool> = listOf(...)`).
3. Selesai: registry mengeksposnya per mode (alat `readOnly` di Plan; semua alat di Build/Max), engine menjaganya lewat gerbang, dan argumen disembuhkan otomatis.
4. Kembalikan `"ERROR:…"` untuk kegagalan dengan teks yang dapat ditindaklanjuti; model membacanya. `DeviceToolRunner` juga memperlakukan string berawalan `Parameter alat`/`Eksekusi alat` sebagai kegagalan untuk circuit breaker.
5. Tambahkan unit test jika alat punya logika non-trivial (`skills/src/test/...`). Alat yang hanya membungkus API sistem tidak membutuhkannya.

### 22.2 Menambah penyedia

1. Implementasikan `ChatProvider` (`core/provider/ChatProvider.kt`) di `:providers`; kamu harus memancarkan `StreamEvent`: `Delta`, `ThinkingDelta`, `ToolCallsDone`, `Usage`, `Done`, `Error`.
2. Tambahkan id ke `ProviderId`, daftarkan di `ProviderFactory`, dan tambahkan base URL + model pilihan di `ModelCatalog`.
3. Jika format wire mirip OpenAI, pakai ulang pola `OpenAiProvider` (parsing SSE, akumulasi panggilan alat) alih-alih mulai dari nol.

### 22.3 String UI & disiplin i18n

- Semua string yang terlihat pengguna ada di `app/src/main/res/values/strings.xml` (Bahasa Indonesia, satu-satunya bahasa di versi lite). Untuk menambah bahasa, buat folder `values-<kode>/strings.xml` dan sesuaikan `localeFilters` di `app/build.gradle.kts`.
- Di composable pakai `stringResource(R.string.x)`; di ViewModel/service pakai `appContext.getString(R.string.x)`. Jangan pernah menulis teks yang terlihat pengguna secara langsung di kode.
- Konten yang *dikirim ke model* (system prompt, deskripsi alat, instruksi perangkuman) kini juga berbahasa Indonesia, tetapi bukan bagian UI sehingga tetap berada di kode.

### 22.4 Perubahan database

- Naikkan `version` di `AppDatabase.kt`, tambahkan `MIGRATION_N_N+1` eksplisit (tidak pernah destruktif), daftarkan di `.addMigrations(...)`, dan perbarui daftar skema di bab 12.
- Kolom baru pada tabel yang ada memerlukan nilai bawaan (`ALTER TABLE … ADD COLUMN … NOT NULL DEFAULT …`) atau harus nullable.

### 22.5 Pengaturan baru

`SettingsRepository` untuk preferensi sederhana (SharedPreferences) atau tabel Room bila berupa daftar (seperti agents). UI pengaturan ada di `SettingsScreen.kt`; ikuti pola bagian yang ada (tiap bagian adalah composable yang menerima `container/scope/snackbar`).

---

## 23. Pemecahan masalah & batasan platform yang diketahui

| Gejala | Penyebab | Yang perlu dilakukan |
| --- | --- | --- |
| Kotak input tertimpa bilah navigasi (navigasi 3 tombol / ColorOS 16 / Android 16) | Bilah bawah obrolan hanya menerapkan `imePadding`, sehingga inset bilah navigasi diabaikan saat keyboard tertutup | Sudah diperbaiki: bilah bawah kini memberi padding `navigationBars ∪ ime` (yang terbesar dari keduanya), diverifikasi pada navigasi 3 tombol dan gestur |
| `An assistant message with 'tool_calls' must be followed by tool messages…` (HTTP 400) | Proses sebelumnya dihentikan di tengah alat; build lama dapat menyimpan pesan panggilan alat asisten kedua tanpa hasil | Diperbaiki di v0.27.8: berhenti tidak lagi menyimpan pesan khusus-alat tanpa konten, dan `repairToolTranscript()` menambah hasil pembatalan sebelum setiap permintaan. Percakapan lama pulih pada pengiriman berikutnya |
| Panggilan alat gagal dengan "JsonLiteral is not a JsonObject" | Gateway meng-encode ganda argumen | Diperbaiki di v0.26.4 (`parseToolArguments`); jika muncul format baru, perluas fungsi itu dan tambahkan tes |
| AI berputar pada panggilan alat yang rusak | Model terus mengirim ulang argumen tidak valid | Circuit breaker menolak setelah 3 kegagalan berturut-turut; periksa `description` alat untuk dokumentasi argumen yang tidak jelas |
| Markdown tampil sebagai teks biasa | Model mengeluarkan format yang ditolak CommonMark (`###judul`, tabel tanpa pipa) | `MarkdownNormalizer` memperbaiki kasus yang dikenal; tambahkan perbaikan baru di sana beserta tes |
| Pencarian tidak mengembalikan apa pun | Jaringan diblokir / mesin membatasi laju | Alat melaporkan diagnostik mesin; verifikasi dengan kueri nyata di perangkat |
| Build GitHub gagal tetapi lokal berhasil | SDK/JDK berbeda | Samakan JDK 17 dan SDK 36 dengan berkas workflow, lalu jalankan perintah yang sama di lokal |
| Keluaran `assembleRelease` tidak bertanda tangan | Penandatanganan manual secara desain (keystore tidak ada di repo) | Lihat bab 24 |

**Batasan desain yang disengaja** (jangan "perbaiki" tanpa memahaminya):

- Mode CHAT **tidak mengiklankan alat sama sekali**: permintaan lebih kecil, tidak ada percobaan alat yang tidak disengaja.
- Mode Plan hanya mengekspos alat `readOnly`; gerbang memeriksa ulang di sisi server (model tidak pernah dipercaya).
- Alat hanya mengakses jaringan (web, RSS, cuaca, kurs); tidak ada alat yang membaca, menulis, atau mengendalikan perangkat.

---

## 24. Glosarium & daftar serah terima

### 24.1 Glosarium

| Istilah | Arti |
| --- | --- |
| **Agent** | Konfigurasi AI tersimpan (penyedia+key+model+parameter+prompt); percakapan terikat pada satu agent |
| **Mode** | Chat / Plan / Build / Max: mengatur paparan alat dan perilaku konfirmasi |
| **Gerbang (Gate)** | Pemeriksaan izin di sisi server sebelum alat apa pun dijalankan (`ChatEngine.gate`) |
| **Skill** | Prosedur berbasis markdown (SKILL.md) yang dapat dimuat sebagai alat lewat `load_skill` |
| **Rangkum (Distill)** | Mengekstrak fakta pengguna yang tahan lama ke tabel `memories` |
| **Snapshot** | Cadangan 6 giliran terakhir yang disimpan sebelum kompresi |
| **Circuit breaker** | Menolak alat setelah 3 kegagalan berturut-turut dalam satu proses |
| **Self-healing** | Perbaikan argumen sebelum eksekusi di `DeviceToolRunner.healArgs` |
| **Pinned** | "Item terakhir terlihat penuh", kondisi yang melanjutkan auto-follow |
| **Lite** | Satu-satunya varian build Milo AI (tanpa OCR, R8 + shrink resources) |

### 24.2 Daftar serah terima (semua yang ada di luar repo)

- **Keystore**: `miloai-release.keystore` + `keystore_pass.txt` berada **di luar git** (di-gitignore). Rilis ditandatangani manual:
  `zipalign -f 4 … && apksigner sign --ks … --ks-pass pass:$(cat keystore_pass.txt)`. Kehilangan keystore berarti pengguna tidak dapat memperbarui di tempat; cadangkan.
- **local.properties** dengan `sdk.dir` spesifik mesin dan di-gitignore.
- **GitHub**: rilis dibuat dari tag `vX.Y.Z`; lampirkan APK lite dan sertakan checksum SHA-256 dalam catatan rilis berbahasa Indonesia. Ingat `gh release create … --notes-file`; opsi bawaan `--generate-notes` menghasilkan teks bahasa Inggris saja.
- **Versi**: `versionCode` naik 1 setiap rilis; `versionName` mengikuti pola semver, dengan MINOR = fitur dan PATCH = perbaikan.
- **Build rilis**: dijalankan manual lewat workflow `release.yml`; tidak ada CI otomatis, jadi jalankan tes lokal sebelum rilis.
- **Urutan publikasi**: naikkan versi → `assembleRelease` → zipalign + tanda tangan → `git tag vX.Y.Z && git push --tags` → `gh release create` dengan catatan + APK.
- **Pin pihak ketiga yang diketahui**: renderer markdown di-pin ke 0.41.0 (versi lebih baru memerlukan compileSdk 37); render tabel dibuat sendiri (`MarkdownTable`) justru untuk menghindari upgrade itu.

---

## 25. Filosofi desain & pendekatan pengembangan

Bab 1–24 menjelaskan *apa* yang dilakukan kode. Bab ini menjelaskan *mengapa bentuknya demikian*: prinsip, keputusan beserta trade-off-nya, dan metode kerja yang menghasilkan basis kode ini. Jika kamu akan membuat perubahan signifikan, baca ini dulu; sebagian besar "perbaikan yang tampak jelas" sudah dipertimbangkan dan ditolak dengan alasan.

### 25.1 Prinsip desain

Inilah aturan yang benar-benar diikuti basis kode. Layak dipertahankan di kode baru.

1. **Lokal dulu, tanpa cloud.** Aplikasi berbicara langsung ke penyedia model yang dikonfigurasi pengguna. Tidak ada backend, telemetri, atau akun. API key dienkripsi dengan Android Keystore dan tidak pernah keluar dari perangkat kecuali ke penyedia itu sendiri. Ini keputusan produk, dan membatasi arsitektur: semuanya (memori, snapshot, agent) harus tinggal di Room/SharedPreferences lokal.

2. **Model tidak pernah dipercaya.** Keluaran model adalah *usulan*. Otoritas ada di sisi server (di aplikasi): gerbang mode menentukan alat mana yang bahkan terlihat, `gate()` memeriksa ulang setiap panggilan, alat hanya-baca ditegakkan oleh registry, dan argumen divalidasi lagi saat eksekusi. Ini pertahanan berlapis; system prompt juga memberi tahu model aturannya, tetapi tidak ada yang bergantung pada kepatuhannya.

3. **Setiap hasil alat adalah teks dengan anggaran.** Hasil alat adalah apa yang "dilihat" model, sehingga ditulis untuk pembaca dengan perhatian terbatas: panjang dibatasi (anggaran pemotongan), noise disaring (bilah penulis, boilerplate), duplikat dibuang, dan diurutkan menurut relevansi. Ekonomi token adalah perhatian desain kelas satu; lihat anggaran `read_top` dan batas engine 6000 karakter.

4. **Kegagalan harus dapat ditindaklanjuti, untuk model.** String kesalahan bukan hanya untuk manusia. `"ERROR:Parameter query tidak boleh kosong. Isi kata kunci pencarian lalu coba lagi"` memberi tahu model apa yang dilakukan berikutnya. Kegagalan tanpa langkah berikutnya menyebabkan loop retry; kegagalan dengan langkah itu mengakhirinya.

5. **Tolak secara bawaan.** Mode CHAT tidak mengiklankan alat sama sekali. Alat tak dikenal ditolak. Mode Plan hanya mengekspos alat `readOnly`. Argumen yang tidak valid menghasilkan pesan jelas, bukan no-op diam-diam. Saat ragu, basis kode memilih bawaan yang lebih aman dan meminta pengguna ikut serta.

6. **Bereaksi, jangan polling.** Scroll-ikuti streaming mendengarkan perubahan layout dan bereaksi; pengamat data memakai flow; parser SSE digerakkan push. Satu polling yang bertahan (ticker streaming) ada karena delta tiba lebih cepat daripada seharusnya rekomposisi berjalan, dan ia dibatasi lajunya. Setiap "cek tiap N ms" lainnya dibuang sebagai sumber bug.

7. **Sambungan kecil yang dapat diuji.** Logika berada di luar composable dan kelas framework Android bila memungkinkan: `ChatEngine` menerima antarmuka `ToolRunner`, OCR adalah antarmuka `OcrEngine`, penyedia mengimplementasikan `ChatProvider`. Inilah yang memungkinkan 60+ unit test tanpa emulator, dan yang membuat build lite dapat membuang ML Kit dengan perubahan yang sangat kecil.

8. **Satu sumber kebenaran per urusan.** Agent memiliki konfigurasi model; `SettingsRepository` memiliki preferensi tingkat perangkat; tabel percakapan memiliki state obrolan; `resolveConfig()` adalah satu-satunya rantai resolusi. Ketika dua tempat bisa menjawab pertanyaan yang sama, basis kode memilih satu pemilik dan mengarahkan semuanya lewat sana.

### 25.2 Keputusan kunci dan trade-off-nya

| Keputusan | Mengapa | Biayanya |
| --- | --- | --- |
| **`AppContainer` buatan sendiri alih-alih Hilt** | Tanpa annotation processor (build cepat), kendali penuh urutan inisialisasi (lihat bug urutan migrasi agent lama), permukaan kecil | Perkabelan manual; dependensi baru harus dihubungkan dengan tangan |
| **Alat bawaan, bukan MCP** | Alat bawaan hanya membungkus panggilan jaringan dan utilitas sederhana; eksekusi dalam proses tidak punya latensi IPC dan tidak perlu server terpisah | Belum dapat memakai ulang server MCP pihak ketiga; adaptor `ToolRegistry` dapat menambahkannya tanpa menyentuh engine |
| **Parser SSE tulisan tangan** | Kendali penuh atas peristiwa parsial, retry, payload ter-encode ganda, dan keunikan vendor; tanpa dependensi berat; mudah diuji unit | Harus dirawat mengikuti perubahan penyedia; karena itu ada tes parser |
| **`Flow<EngineEvent>` untuk loop agen** | Pembatalan terstruktur (tombol berhenti = `job.cancel()`), loop dapat diuji tanpa UI, dan ViewModel bebas menyaring/mengubah peristiwa | Sedikit lebih berseremoni daripada callback; perlu disiplin soal keamanan thread (lihat penjaga `runToken`) |
| **Room dengan migrasi eksplisit, tidak pernah destruktif** | Riwayat obrolan adalah data pengguna; kehilangan diam-diam tidak dapat diterima untuk aplikasi obrolan | Setiap perubahan skema butuh migrasi tulisan tangan dan pembaruan bab 12 |
| **Render tabel kustom + pustaka markdown yang di-pin** | Versi baru pustaka markdown upstream memerlukan compileSdk 37; tabel adalah konstruksi yang paling sering rusak dalam keluaran model, jadi renderer kustom sepadan | Kita memiliki kasus tepi render tabel (tabel tanpa pipa luar, escape) dan tesnya |
| **Penyembuhan argumen *dan* circuit breaker** | Model nyata terus-menerus menghasilkan argumen cacat (tipe salah, kunci salah eja, JSON ter-encode ganda). Penyembuhan mengubah sebagian besar kegagalan menjadi keberhasilan; breaker menghentikan sisanya agar tidak menghabiskan putaran | Dua lapisan untuk dirawat; penyembuhan tidak boleh *menyembunyikan* masalah nyata, perbaikan selalu dicatat dalam hasil |
| **Scroll digerakkan layout (iterasi ketiga)** | Poll-lalu-scroll berebut dengan layout markdown asinkron; bereaksi pada perubahan layout adalah satu-satunya pendekatan yang benar sekaligus bebas getar | Perlu memahami semantik `snapshotFlow(layoutInfo)` dan `DragInteraction`; didokumentasikan di bab 13.3 agar tak ada yang "menyederhanakannya" kembali menjadi loop |
| **Penandatanganan rilis manual** | Keystore tidak boleh ada di repo atau secret CI/CD untuk proyek skala hobi | Rilis manual; daftar di bab 24 ada karena hal ini |

### 25.3 Bagaimana fitur dibangun di sini

Alur kerja yang menghasilkan setiap fitur di aplikasi ini, berurutan:

1. **Tentukan perilaku yang teramati**: apa yang dilihat pengguna, apa yang dilihat model, seperti apa kegagalan. Bukan implementasinya.
2. **Bangun skenario mock lebih dulu.** Server SSE lokal (bab 18.6) diskrip berdasarkan *nama model*: `newtools`, `mtr`… Setiap skenario menyandikan satu perilaku: urutan panggilan alat, kasus tepi markdown, argumen ter-encode ganda. Ini membuat pipeline dapat direproduksi dalam hitungan detik tanpa kuota API.
3. **Implementasikan terhadap mock**, memverifikasi seluruh jalur: pembuatan permintaan → streaming → eksekusi alat → persistensi → UI.
4. **Verifikasi di perangkat/emulator nyata**: keberhasilan mock perlu tetapi tidak cukup (perilaku platform berbeda: izin notifikasi, input suara, inset layar).
5. **Tulis unit test untuk logika yang rusak atau bisa rusak**: kasus tepi parser, normalizer, kalkulator, penyembuhan argumen. Bukan demi cakupan; setiap tes di repo ini terlacak ke kegagalan nyata atau kasus tepi nyata.
6. **Jalankan audit secara berkala.** Basis kode ini diperkuat oleh tiga audit paralel (inti obrolan / alat / penyimpanan+UI) yang menghasilkan 45+ perbaikan. Audit bekerja terbaik sebagai putaran independen dengan mata segar, diikuti perbaikan massal.
7. **Jalankan tes lokal sebelum rilis, commit dalam langkah kecil.** Tidak ada CI otomatis; tes lokal adalah gerbangnya, dan riwayat yang linear mudah di-bisect.

### 25.4 Anti-pola yang dihindari basis kode ini (dan alasannya)

- **IO atau dekode bitmap di composable**: menyebabkan jank main thread dan risiko ANR; semua dekode kini `withContext(Dispatchers.Default)`.
- **Matematika gaya `eval()`**: kalkulator adalah parser recursive-descent tulisan tangan; eksekusi kode sembarang tidak pernah menjadi opsi, bahkan untuk "sekadar matematika".
- **String yang terlihat pengguna ditulis langsung di kode**: disiplin i18n lewat berkas string.
- **`fallbackToDestructiveMigration()`**: tidak pernah. Kehilangan data bukan strategi migrasi.
- **Memercayai `isScrollInProgress` sebagai niat pengguna**: scroll terprogram juga menyetelnya; logika ikuti diam-diam dinonaktifkan oleh scroll-nya sendiri sampai `DragInteraction` dipakai.
- **`runCatching` di sekitar pembatalan**: menelan `CancellationException` membocorkan resource (recognizer suara) dan merusak konkurensi terstruktur; alat yang memegang resource melempar ulang secara eksplisit.
- **Loop alat tanpa batas**: model yang terjebak pada panggilan rusak dulu menghabiskan anggaran putaran; kini: sembuhkan sendiri → retry bercatatan → circuit breaker → penolakan yang dapat ditindaklanjuti.

### 25.5 Jika kamu mengambil alih: semangat proyek

Milo AI ada untuk membuktikan bahwa satu pengembang dapat merilis agen *sungguhan*, bukan sekadar pembungkus obrolan, di ponsel: lokal dulu, menghormati privasi, dengan alat web dan utilitas yang nyata. Kecenderungan rekayasa sepanjang proyek adalah **mempermudah pekerjaan model** (alat yang jelas, hasil yang bersih, kesalahan yang dapat ditindaklanjuti) dan **menjaga manusia tetap memegang kendali** (mode dan konfirmasi). Saat memilih antara implementasi yang pintar dan yang mudah di-debug, basis kode ini selalu memilih yang mudah di-debug; itulah mengapa mock server, putaran audit, dan dokumen ini ada.
