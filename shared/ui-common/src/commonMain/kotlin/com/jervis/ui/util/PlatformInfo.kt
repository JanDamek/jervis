package com.jervis.ui.util

import com.jervis.dto.ui.ChatMessage

/** Current platform voice source — used to tag voice messages with origin device. */
expect val currentVoiceSource: ChatMessage.VoiceSource
