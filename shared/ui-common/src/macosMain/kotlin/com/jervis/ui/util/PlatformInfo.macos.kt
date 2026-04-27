package com.jervis.ui.util

import com.jervis.dto.ui.ChatMessage

actual val currentVoiceSource: ChatMessage.VoiceSource
    get() = ChatMessage.VoiceSource.DESKTOP
