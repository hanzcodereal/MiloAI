package com.miloai.core.provider

import com.miloai.core.model.ChatMessage
import com.miloai.core.model.ProviderConfig
import com.miloai.core.model.StreamEvent
import com.miloai.core.model.ToolSpec
import kotlinx.coroutines.flow.Flow

interface ChatProvider {
    fun chatStream(
        messages: List<ChatMessage>,
        config: ProviderConfig,
        tools: List<ToolSpec>
    ): Flow<StreamEvent>
}
