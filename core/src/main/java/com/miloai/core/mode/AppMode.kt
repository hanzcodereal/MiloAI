package com.miloai.core.mode

enum class AppMode(val displayName: String, val description: String) {
    CHAT("Chat", "Percakapan biasa, tanpa memanggil alat"),
    PLAN("Plan", "Analisis hanya-baca, alat yang mengeksekusi dilarang"),
    BUILD("Build", "Mode bawaan, setiap alat perlu konfirmasi sebelum dijalankan"),
    MAX("Max", "Menjalankan banyak panggilan alat secara mandiri, dengan penalaran mendalam")
}
