package com.miloai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.miloai.ui.navigation.AppNavHost
import com.miloai.ui.theme.MiloAiTheme

class MainActivity : ComponentActivity() {

    private fun currentLocale(): java.util.Locale? {
        val lang = (application as MiloAiApp).container.settings.getLanguage()
        return when (lang) {
            com.miloai.core.storage.AppLanguage.ID -> java.util.Locale("in")
            else -> null
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        val locale = runCatching { (newBase.applicationContext as MiloAiApp).container.settings.getLanguage() }
            .getOrDefault(com.miloai.core.storage.AppLanguage.SYSTEM)
        val loc = when (locale) {
            com.miloai.core.storage.AppLanguage.ID -> java.util.Locale("in")
            else -> null
        }
        val base = if (loc != null && android.os.Build.VERSION.SDK_INT < 33) {
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(loc)
            newBase.createConfigurationContext(config)
        } else newBase
        super.attachBaseContext(base)
    }

    private fun applyLanguage() {
        val locale = currentLocale() ?: return
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching {
                val lm = getSystemService(android.app.LocaleManager::class.java)
                lm.applicationLocales = android.os.LocaleList(locale)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLanguage()
        enableEdgeToEdge()
        handleShareIntent(intent)
        setContent {
            MiloAiTheme {
                AppNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val container = (application as MiloAiApp).container
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "miloai") {
            val text = intent.data?.getQueryParameter("text")?.trim()?.take(8000)
            if (!text.isNullOrBlank()) {
                container.pendingShareText = text
                container.shareNavTick.value = container.shareNavTick.value + 1
            }
            intent.action = Intent.ACTION_MAIN
            return
        }
        if (intent.action != Intent.ACTION_SEND) return
        when (intent.type) {
            "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                if (!text.isNullOrBlank()) container.pendingShareText = text
            }
            else -> {
                if (intent.type?.startsWith("image/") == true) {
                    val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    if (uri != null) container.pendingShareImage = uri
                }
            }
        }
        container.shareNavTick.value = container.shareNavTick.value + 1
        intent.action = Intent.ACTION_MAIN
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
