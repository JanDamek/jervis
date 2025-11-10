package com.jervis.service.listener.email.imap

import java.time.Instant

data class ImapMessage(
    val messageId: String,
    val subject: String,
    val from: String,
    val to: String,
    val receivedAt: Instant,
    val content: String,
    val attachments: List<ImapAttachment> = emptyList(),
)
