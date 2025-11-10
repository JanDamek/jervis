package com.jervis.service.listener.email.imap

import java.time.Instant

data class ImapMessageId(
    val messageId: String,
    val uid: Long, // IMAP UID - reliable numeric identifier
    val subject: String?,
    val from: String?,
    val receivedAt: Instant?,
)
