# Berkontribusi ke Milo AI

Terima kasih sudah tertarik! Proyek ini dikembangkan oleh satu pengembang (Hanz Dev Official) secara bertahap, dan bantuan apa pun sangat diterima.

## Cara berkontribusi

- **Laporan bug & ide fitur**: buat issue dengan menyertakan model perangkat, versi Android, dan langkah untuk mereproduksi, atau hubungi langsung di hanzzcodee@gmail.com
- **Terjemahan**: UI memakai resource string di `app/src/main/res/values/` (Bahasa Indonesia). Memperbaiki terjemahan atau menambah bahasa baru sangat berharga
- **Alat baru**: implementasikan `DeviceTool` di `:skills` dan daftarkan di `MiloAiApp.kt`
- **Penyedia baru**: tambahkan adaptor di `:providers` yang mengimplementasikan `ChatProvider`
- **Review kode**: basis kodenya ringkas (sekitar 150 berkas sumber); komentar dan saran diterima

## Menyiapkan lingkungan pengembangan

```bash
# JDK 17, Android SDK dengan compileSdk 36
./gradlew assembleDebug
```

Jalankan build debug dan unit test sebelum membuka PR:

```bash
./gradlew :core:testDebugUnitTest :skills:testDebugUnitTest :providers:testDebugUnitTest assembleDebug
```

## Gaya kode

- Kotlin, tanpa komentar kecuali perlu untuk kejelasan
- Jaga batas modul: `core` (tanpa UI), `providers` (hanya jaringan), `skills` (alat bawaan), `app` (UI/ViewModel)
- Ikuti pola yang ada (mis. antarmuka `DeviceTool` untuk alat, `SettingsRepository` untuk preferensi)

## Rilis

Rilis dipublikasikan lewat GitHub Releases dengan APK bertanda tangan (varian lite). Catatan rilis ditulis dalam bahasa Indonesia.

## Dukung pengembang

Kalau proyek ini bermanfaat, kamu bisa mendukung lewat https://saweria.co/hanzreally
