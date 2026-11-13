package com.miloai.tools

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class SpeechInputHelper(context: Context) {

    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    @Volatile private var isListening = false

    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isListening) {
            onError("Pengenalan suara sedang dipakai, mohon tunggu")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                isListening = false
                onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Tidak terdengar jelas, coba lagi"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin merekam belum diberikan"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Kesalahan jaringan"
                        else -> "Pengenalan suara gagal ($error)"
                    }
                )
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                onFinal(text.orEmpty())
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            recognizer.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            onError("Pengenalan suara tidak tersedia: ${e.message}")
        }
    }

    fun stop() {
        isListening = false
        runCatching { recognizer.stopListening() }
    }

    fun cancel() {
        isListening = false
        runCatching { recognizer.cancel() }
    }

    fun destroy() {
        recognizer.destroy()
    }

    fun isActive(): Boolean = isListening
}
