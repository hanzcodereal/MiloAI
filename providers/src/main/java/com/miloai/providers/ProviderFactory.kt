package com.miloai.providers

import com.miloai.core.model.ProviderId
import com.miloai.core.provider.ChatProvider
import com.miloai.providers.anthropic.AnthropicProvider
import com.miloai.providers.gemini.GeminiProvider
import com.miloai.providers.openai.OpenAiProvider

object ProviderFactory {
    fun create(id: ProviderId): ChatProvider = when (id) {
        ProviderId.OPENAI_COMPAT -> OpenAiProvider()
        ProviderId.ANTHROPIC -> AnthropicProvider()
        ProviderId.GEMINI -> GeminiProvider()
    }
}
