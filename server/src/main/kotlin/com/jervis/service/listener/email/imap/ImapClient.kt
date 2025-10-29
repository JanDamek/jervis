package com.jervis.service.listener.email.imap

import com.jervis.entity.EmailAccountDocument
import jakarta.mail.BodyPart
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Session
import jakarta.mail.Store
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Properties

private val logger = KotlinLogging.logger {}

private const val MESSAGE_ID = "Message-ID"

@Component
class ImapClient {
    fun fetchMessageIds(account: EmailAccountDocument): Flow<ImapMessageId> =
        flow {
            runCatching {
                logger.info { "Fetching message IDs for account ${account.id} (${account.email})" }
                val startTime = System.currentTimeMillis()

                createSession(account).use { store ->
                    openInbox(store).use { inbox ->
                        val messages = inbox.messages
                        logger.info { "Found ${messages.size} messages in INBOX for account ${account.id}" }

                        if (messages.isEmpty()) {
                            logger.info { "No messages to fetch for account ${account.id}" }
                            return@flow
                        }

                        val fetchProfile =
                            FetchProfile().apply {
                                add(FetchProfile.Item.ENVELOPE)
                                add(MESSAGE_ID)
                            }

                        inbox.fetch(messages, fetchProfile)
                        logger.info { "Fetched headers for ${messages.size} messages in ${System.currentTimeMillis() - startTime}ms" }

                        messages.forEach { message ->
                            extractMessageId(message)?.let { emit(it) }
                        }

                        logger.info {
                            "Completed message ID extraction for account ${account.id} in ${System.currentTimeMillis() - startTime}ms"
                        }
                    }
                }
            }.getOrElse { e ->
                logger.error(e) { "Failed to fetch message IDs for account ${account.id}" }
            }
        }.buffer(50) // Buffer maxes 50 message IDs to prevent overwhelming downstream processing

    suspend fun fetchMessage(
        account: EmailAccountDocument,
        uid: Long,
    ): ImapMessage? =
        runCatching {
            logger.debug { "Fetching message by UID $uid for account ${account.id}" }
            val startTime = System.currentTimeMillis()

            createSession(account).use { store ->
                openInbox(store).use { inbox ->
                    val uidFolder =
                        inbox as? jakarta.mail.UIDFolder
                            ?: throw IllegalStateException("Folder doesn't support UID")

                    val message = uidFolder.getMessageByUID(uid)

                    val elapsed = System.currentTimeMillis() - startTime
                    logger.debug { "IMAP UID fetch completed in ${elapsed}ms, found=${message != null}" }

                    message?.let { parseMessage(it) }
                }
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch message by UID $uid for account ${account.id}" }
            null
        }

    private suspend fun extractMessageId(message: Message): ImapMessageId? =
        try {
            val uid = (message.folder as? jakarta.mail.UIDFolder)?.getUID(message) ?: return null
            message.getHeader(MESSAGE_ID)?.firstOrNull()?.let { id ->
                ImapMessageId(
                    messageId = id,
                    uid = uid,
                    subject = message.subject,
                    from = message.from?.firstOrNull()?.toString(),
                    receivedAt = message.receivedDate?.toInstant(),
                )
            }
        } catch (e: Exception) {
            logger.warn { "Failed to extract messageId. Error:${e.message}" }
            // ignore a message that is not box or is disconnected
            null
        }

    private fun createSession(account: EmailAccountDocument): Store {
        val props =
            Properties().apply {
                this["mail.store.protocol"] = "imap"
                this["mail.imap.ssl.enable"] = account.useSsl.toString()
            }

        val session = Session.getInstance(props)
        val store: Store = session.getStore("imap")
        val username = account.username ?: account.email

        store.connect(account.serverHost, username, account.password)
        return store
    }

    private fun openInbox(store: Store): Folder =
        store.getFolder("INBOX").apply {
            open(Folder.READ_ONLY)
        }

    private fun parseMessage(message: Message): ImapMessage {
        val messageId = message.getHeader(MESSAGE_ID)?.firstOrNull() ?: message.messageNumber.toString()
        val subject = message.subject ?: ""
        val from = message.from?.firstOrNull()?.toString() ?: ""
        val to = message.allRecipients?.joinToString(", ") { it.toString() } ?: ""
        val receivedAt = message.receivedDate?.toInstant() ?: Instant.now()

        val (content, attachments) =
            try {
                extractContentAndAttachments(message)
            } catch (e: jakarta.mail.MessagingException) {
                logger.warn { "Failed to extract content/attachments for $messageId: ${e.message} - using metadata only" }
                "" to emptyList()
            }

        return ImapMessage(
            messageId = messageId,
            subject = subject,
            from = from,
            to = to,
            receivedAt = receivedAt,
            content = content,
            attachments = attachments,
        )
    }

    private fun extractContentAndAttachments(message: Message): Pair<String, List<ImapAttachment>> {
        val content = StringBuilder()
        val attachments = mutableListOf<ImapAttachment>()

        when (val messageContent = message.content) {
            is String -> content.append(decodeText(messageContent, message.contentType))
            is Multipart -> extractFromMultipart(messageContent, content, attachments)
        }

        return content.toString() to attachments
    }

    private fun decodeText(
        text: String,
        contentType: String?,
    ): String =
        try {
            // Try to detect charset from Content-Type
            val charset =
                contentType
                    ?.split(";")
                    ?.find { it.trim().startsWith("charset=", ignoreCase = true) }
                    ?.substringAfter("=")
                    ?.trim()
                    ?.trim('"')

            // If charset found, re-encode to UTF-8
            if (charset != null && !charset.equals("UTF-8", ignoreCase = true)) {
                val bytes = text.toByteArray(Charsets.ISO_8859_1) // Get original bytes
                String(bytes, charset(charset)) // Decode with the correct charset
            } else {
                // Try MimeUtility decode for encoded-word format (=?charset?encoding?text?=)
                jakarta.mail.internet.MimeUtility
                    .decodeText(text)
            }
        } catch (_: Exception) {
            // Fallback: return original text
            text
        }

    private fun extractFromMultipart(
        multipart: Multipart,
        content: StringBuilder,
        attachments: MutableList<ImapAttachment>,
    ) {
        (0 until multipart.count).forEach { i ->
            val bodyPart = multipart.getBodyPart(i)

            bodyPart
                .takeIf { isAttachment(it) }
                ?.let { createAttachment(it, i) }
                ?.let { attachments.add(it) }
                ?: processBodyPartContent(bodyPart, content, attachments)
        }
    }

    private fun isAttachment(bodyPart: BodyPart): Boolean = bodyPart.disposition?.equals("attachment", ignoreCase = true) == true

    private fun createAttachment(
        bodyPart: BodyPart,
        index: Int,
    ): ImapAttachment =
        ImapAttachment(
            fileName = bodyPart.fileName ?: "attachment-$index",
            contentType = bodyPart.contentType,
            size = bodyPart.size.toLong(),
            data = bodyPart.inputStream.readBytes(),
        )

    private fun processBodyPartContent(
        bodyPart: BodyPart,
        content: StringBuilder,
        attachments: MutableList<ImapAttachment>,
    ) {
        when (val partContent = bodyPart.content) {
            is String -> content.append(decodeText(partContent, bodyPart.contentType))
            is Multipart -> extractFromMultipart(partContent, content, attachments)
        }
    }
}
