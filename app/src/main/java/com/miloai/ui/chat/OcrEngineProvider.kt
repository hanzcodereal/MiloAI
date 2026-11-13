package com.miloai.ui.chat

import android.graphics.Bitmap

object OcrEngineProvider {

    fun get(): OcrEngine = LiteStubOcrEngine

    private object LiteStubOcrEngine : OcrEngine {
        override suspend fun recognize(bitmap: Bitmap): String {
            throw IllegalStateException("Versi lite tidak menyertakan model OCR sehingga pengenalan teks pada PDF/gambar tidak tersedia.")
        }
    }
}
