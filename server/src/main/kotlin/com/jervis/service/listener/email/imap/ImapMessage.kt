package com.jervis.service.listener.email.imap

import java.time.Instant

data class ImapMessageId(
    val messageId: String,
    val subject: String?,
    val from: String?,
    val receivedAt: Instant?,
)

data class ImapMessage(
    val messageId: String,
    val subject: String,
    val from: String,
    val to: String,
    val receivedAt: Instant,
    val content: String,
    val attachments: List<ImapAttachment> = emptyList(),
)

data class ImapAttachment(
    val fileName: String,
    val contentType: String,
    val size: Long,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImapAttachment

        if (fileName != other.fileName) return false
        if (contentType != other.contentType) return false
        if (size != other.size) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
