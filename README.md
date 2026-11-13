# Milo AI

![Versi](https://img.shields.io/badge/versi-1.0.0-blue) ![Platform](https://img.shields.io/badge/platform-Android-green) ![Lisensi](https://img.shields.io/badge/lisensi-MIT-lightgrey)

> Asisten AI Android native yang memakai **API key milikmu sendiri**. Ngobrol dengan LLM terkemuka, cari informasi di web, dan ikuti Skill yang bisa dipakai ulang.

Berbeda dengan aplikasi AI pada umumnya, Milo AI adalah **agen yang mengutamakan data lokal**: API key dienkripsi di perangkat dan tanpa cloud.

**Pengembang:** Hanz Dev Official
**Kontak:** hanzzcodee@gmail.com
**Dukung pengembang:** [saweria.co/hanzreally](https://saweria.co/hanzreally)

---

## Versi Lite

Milo AI dirilis sebagai **versi lite** yang lebih kecil:

- Tanpa model OCR ML Kit di perangkat (mengurangi ukuran APK sekitar puluhan MB)
- Kode dan resource dikecilkan dengan R8 + shrink resources
- Hanya menyertakan resource bahasa Indonesia
- Fitur yang bergantung pada OCR (`screen_ocr`, `ocr_file`, OCR untuk PDF hasil pindai) tidak tersedia. Analisis layar tetap bisa dipakai lewat model dengan kemampuan visual.

---

## Fitur

### Obrolan & model
- **Banyak penyedia**: kompatibel OpenAI (OpenAI, DeepSeek, Moonshot, Qwen, Ollama, gateway apa pun), Anthropic Claude, Google Gemini
- **Balasan streaming** dengan render Markdown, tampilan proses berpikir (reasoning stream), dan kursor berkedip
- **Pemilih Agent**: pilih Agent per percakapan; katalog model bawaan + ambil model dari server (`/v1/models`)
- **Pelacak penggunaan konteks** di header (`117.3K (12%)`)
- **Kompresi konteks**: rangkum percakapan panjang untuk mengosongkan jendela konteks
- **Judul otomatis** oleh AI; templat prompt (terjemahkan / rangkum / poles / menulis / jelaskan kode / curah gagasan)

### Agent: pengaturan sekali ketuk, satu per percakapan
**Agent** menggabungkan konfigurasi lengkap: penyedia + API key + model + temperature / max tokens / penalaran mendalam + system prompt kustom (opsional).
- **Onboarding sekali ketuk (4 langkah)**: tempel API key → penyedia terdeteksi dari awalan (`sk-ant-` = Claude, `AIza` = Gemini), dengan preset cepat DeepSeek / Qwen / Kimi / GLM / SiliconFlow → model diambil otomatis → pilih model → atur temperature / max tokens → system prompt bawaan atau kustom
- Pilih Agent berbeda per percakapan (⋮ → Pilih Agent); kelola semua Agent di Pengaturan → Agent
- API key dienkripsi dengan Android Keystore; pengaturan lama dimigrasi otomatis ke Agent bawaan

### Mode (gaya opencode)
| Mode | Perilaku |
| --- | --- |
| `Chat` | Percakapan biasa, tanpa alat |
| `Plan` | Analisis hanya-baca, alat tulis dilarang |
| `Build` | Bawaan: setiap eksekusi alat meminta konfirmasi |
| `Max` | Panggilan alat multi-langkah secara mandiri + penalaran mendalam |

### Memori jangka panjang
- AI otomatis merangkum info penting pengguna (nama, preferensi, kesepakatan) ke **memori lokal permanen**
- Memori disisipkan ke setiap permintaan, sehingga AI mengingatmu lintas sesi
- Otomatis dirangkum setiap 10 pesan; pemicu manual lewat menu ⋮ → "Rangkum info penting"
- Kelola / hapus memori di Pengaturan → Memori

### Kelanjutan konteks yang mulus
- Saat penggunaan mencapai **85%** jendela konteks, aplikasi memberi peringatan dan **mengompres otomatis** setelah giliran berjalan
- Sebelum mengompres, 6 giliran terakhir disimpan sebagai snapshot. "Impor obrolan terbaru" memulihkannya sebagai pesan, sehingga kamu bisa lanjut tanpa kehilangan konteks terbaru

### Skill (gaya opencode)
- Impor berkas `SKILL.md` dengan frontmatter YAML (`name`, `description`, `allowed-tools`)
- **Rekam aksi menjadi skill**: setelah AI menyelesaikan tugas multi-langkah, simpan urutan alatnya sebagai Skill yang bisa dipakai ulang
- Perekam aksi bawaan, manajemen impor/hapus

### Tema
8 skema warna aksen (oranye/merah/merah muda/indigo/biru/ungu/hijau/teal), bawaan oranye sesuai ikon aplikasi, bisa diganti seketika.

### Pencarian web multi-mesin
web_search menggabungkan **Bing / Baidu / Brave / DuckDuckGo / Mojeek** dengan penghapusan duplikat URL dan cache 5 menit, sehingga hasil tetap andal meski satu mesin memblokir scraping.

### Lampiran & dokumen
- Gambar (maksimum 4, dikompres otomatis, orientasi EXIF dikoreksi) dikirim ke model visual
- **PDF** (lapisan teks), **Word .docx**, **Excel .xlsx** (semua lembar), berkas teks hingga 1MB

### Bebas tangan
- **Mode asisten suara**: AI membacakan balasan, mikrofon terbuka otomatis, jawaban lisanmu terkirim otomatis, siklus bebas tangan yang utuh
- Tombol input suara, bacakan pesan apa pun, sakelar baca otomatis

### Pengelolaan
- Manajemen banyak percakapan: sematkan, arsipkan, cari, pratinjau pesan terakhir, ganti nama, geser untuk hapus, bersihkan konteks
- Pencarian dalam obrolan: lompat antarhasil dengan penghitung langsung
- Penampil gambar dengan zoom cubit dan ketuk dua kali
- Pesan berbintang dengan halaman favorit khusus
- Ekspor percakapan sebagai Markdown (panel berbagi)
- Aksi tekan lama pada pesan: salin / bacakan / edit & kirim ulang / bintangi / hapus
- Statistik penggunaan (percakapan, pesan, token, panggilan alat)
- Bagikan-ke-obrolan (`ACTION_SEND`) dan deep link `miloai://ask?text=…`

---

## Memulai

### Unduh
Unduh APK dari halaman rilis proyek ini **lewat peramban**. Aplikasi GitHub di Android dapat merusak unduhan APK berukuran besar; verifikasi dengan SHA-256 yang dicantumkan pada tiap rilis.

### Konfigurasi
1. Pengaturan → Agent → **Agent Baru** (atau langsung dari obrolan: ⋮ → Pilih Agent → Agent Baru)
2. Langkah 1: tempel API key. Penyedia + Base URL terdeteksi otomatis (`sk-ant-` → Claude, `AIza` → Gemini), atau pilih preset (DeepSeek / Qwen / Kimi / GLM / SiliconFlow) lalu ketuk **Ambil model** untuk mengambil daftar model servermu
3. Langkah 2: pilih model · Langkah 3: temperature / max tokens / penalaran mendalam · Langkah 4: system prompt bawaan atau kustom → simpan
4. Mulai percakapan dengan Agent tersebut. Pada mode Build/Max, minta AI membantu, misalnya: *"cari berita hari ini dan rangkum"*, *"hitung (12+5)*3"*…
5. Ingin persona lain? Buat lebih banyak Agent dan ganti per percakapan lewat ⋮ → Pilih Agent

### Build dari source
```bash
# JDK 17 + Android SDK (compileSdk 36)
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# Build rilis lite (R8 + shrink resources)
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/
```

Tanpa konfigurasi tambahan, APK release ditandatangani dengan kunci debug sehingga bisa langsung dipasang. Untuk rilis resmi, isi variabel lingkungan `MILO_KEYSTORE`, `MILO_KEYSTORE_PASSWORD`, `MILO_KEY_ALIAS`, dan `MILO_KEY_PASSWORD` sebelum build. Workflow GitHub Actions `release.yml` membangun APK release dan hanya berjalan manual: buka tab Actions → Build Release APK → Run workflow.

---

## Izin

| Izin | Kegunaan |
| --- | --- |
| Notifikasi | Notifikasi aplikasi |
| Mikrofon | Input suara & mode asisten suara |

Semua izin diberikan secara manual; alat mengembalikan pesan kesalahan yang jelas bila belum diizinkan.

---

## Teknologi

Kotlin · Jetpack Compose (Material 3) · OkHttp (SSE) · kotlinx.serialization · Room · Android Keystore

```
:app         UI (percakapan, obrolan, pengaturan, statistik, berbintang)
:core        model, engine, SSE, penyimpanan, DB, penguraian skill
:providers   adaptor OpenAI-compatible / Anthropic / Gemini
:skills      alat bawaan (web, kalkulator, dll.) + registry alat + pelaksana aksi
```

### Belajar dari proyek ini

Baru di proyek ini? **[docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md)** adalah buku panduan pengembang lengkap: memulai (build/jalankan/uji/debug dengan mock server), arsitektur modul, alur pesan, streaming SSE, siklus pemanggilan alat, jembatan izin, mesin otomatisasi, internal rendering UI, serta bab khusus tentang Agent, alur pencarian web, dan memori jangka panjang, panduan perluasan (tambah alat/penyedia dalam hitungan menit), pemecahan masalah beserta batasan platform yang diketahui, dan daftar serah terima.

---

## Rencana

- Perbandingan multi-model (satu pertanyaan, beberapa model berdampingan)
- Widget layar utama
- Marketplace Agent / Agent yang bisa dibagikan

---

## Dukungan & Kontak

- Pengembang: **Hanz Dev Official**
- Email: hanzzcodee@gmail.com
- Dukung lewat Saweria: https://saweria.co/hanzreally

---

## Lisensi

[MIT](LICENSE)
