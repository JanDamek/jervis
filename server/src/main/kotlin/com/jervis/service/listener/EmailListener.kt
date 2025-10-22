package com.jervis.service.listener

import com.jervis.domain.authentication.ServiceTypeEnum
import com.jervis.entity.mongo.EmailAccountDocument
import com.jervis.service.listener.domain.ServiceMessage
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Instant
import java.util.Properties

private val logger = KotlinLogging.logger {}

@Service
class EmailListener(
    builder: WebClient.Builder,
) {
    private val webClient: WebClient = builder.build()

    suspend fun pollEmailAccount(
        account: EmailAccountDocument,
        lastCheckTime: Instant?,
    ): List<ServiceMessage> =
        withContext(Dispatchers.IO) {
            try {
                when {
                    account.accessToken != null -> pollWithOAuth(account, lastCheckTime)
                    account.password != null -> pollWithPassword(account, lastCheckTime)
                    else -> {
                        logger.warn { "No credentials configured for email account ${account.id}" }
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error polling email for account ${account.id}" }
                emptyList()
            }
        }

    private suspend fun pollWithOAuth(
        account: EmailAccountDocument,
        lastCheckTime: Instant?,
    ): List<ServiceMessage> {
        logger.info { "Polling with OAuth for account ${account.id} (${account.provider})" }

        return when {
            account.provider.name.contains("MICROSOFT") -> pollMicrosoftGraph(account, lastCheckTime)
            account.provider.name.contains("GMAIL") -> pollGmailApi(account, lastCheckTime)
            else -> {
                logger.warn { "OAuth not supported for provider ${account.provider}" }
                emptyList()
            }
        }
    }

    private suspend fun pollWithPassword(
        account: EmailAccountDocument,
        lastCheckTime: Instant?,
    ): List<ServiceMessage> {
        logger.info { "Polling with password for account ${account.id} (IMAP)" }
        return pollImap(account, lastCheckTime)
    }

    private suspend fun pollImap(
        account: EmailAccountDocument,
        lastCheckTime: Instant?,
    ): List<ServiceMessage> {
        if (account.serverHost == null || account.serverPort == null || account.password == null) {
            logger.warn { "IMAP credentials incomplete for account ${account.id}" }
            return emptyList()
        }

        return try {
            val props = Properties()
            props["mail.store.protocol"] = "imap"
            props["mail.imap.host"] = account.serverHost
            props["mail.imap.port"] = account.serverPort.toString()
            props["mail.imap.ssl.enable"] = account.useSsl.toString()
            props["mail.imap.starttls.enable"] = "true"

            val session = Session.getInstance(props)
            val store: Store = session.getStore("imap")
            val username = account.username ?: account.email

            store.connect(account.serverHost, username, account.password)

            val folderNames =
                listOf(
                    "INBOX",
                    "[Gmail]/Trash",
                    "[Gmail]/Bin",
                    "Trash",
                    "Deleted",
                    "Deleted Items",
                    "Bin",
                )

            val allMessages = mutableListOf<Message>()

            folderNames.forEach { folderName ->
                try {
                    val folder = store.getFolder(folderName)
                    if (folder.exists()) {
                        folder.open(Folder.READ_ONLY)
                        allMessages.addAll(folder.messages.toList())
                        folder.close(false)
                        logger.debug { "Fetched ${folder.messages.size} messages from folder: $folderName" }
                    }
                } catch (e: Exception) {
                    logger.debug { "Could not access folder $folderName: ${e.message}" }
                }
            }

            val serviceMessages =
                allMessages.map { message ->
                    parseImapMessage(message, account)
                }

            store.close()

            logger.info { "Polled ${serviceMessages.size} total emails from IMAP for account ${account.id}" }
            serviceMessages
        } catch (e: Exception) {
            logger.error(e) { "Failed to poll IMAP for account ${account.id}" }
            emptyList()
        }
    }

    private fun parseImapMessage(
        message: Message,
        account: EmailAccountDocument,
    ): ServiceMessage {
        val messageId = message.getHeader("Message-ID")?.firstOrNull() ?: "unknown"
        val from = message.from?.joinToString(", ") { it.toString() } ?: "unknown"
        val subject = message.subject ?: "(no subject)"
        val sentDate = message.sentDate?.toInstant() ?: Instant.now()
        val content = extractTextContent(message)

        return ServiceMessage(
            id = messageId,
            serviceTypeEnum = ServiceTypeEnum.EMAIL,
            clientId = account.clientId,
            projectId = account.projectId,
            content = content,
            author = from,
            timestamp = sentDate,
            metadata =
                mapOf(
                    "subject" to subject,
                    "from" to from,
                    "to" to (message.allRecipients?.joinToString(", ") { it.toString() } ?: ""),
                ),
            threadId = message.getHeader("Thread-Topic")?.firstOrNull() ?: messageId,
        )
    }

    private fun extractTextContent(message: Message): String =
        when (val content = message.content) {
            is String -> content
            is MimeMultipart -> {
                buildString {
                    for (i in 0 until content.count) {
                        val bodyPart = content.getBodyPart(i)
                        when {
                            bodyPart.isMimeType("text/plain") -> append(bodyPart.content.toString())
                            bodyPart.isMimeType("text/html") ->
                                append(
                                    bodyPart.content.toString().replace(Regex("<[^>]*>"), " "),
                                )
                        }
                    }
                }
            }

            else -> ""
        }

    private suspend fun pollGmailApi(
        account: EmailAccountDocument,
        lastCheckTime: Instant?,
    ): List<ServiceMessage> {
        logger.info { "Polling Gmail API for account ${account.id}" }

        return try {
            val response =
                webClient
                    .get()
                    .uri("https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=500&includeSpamTrash=true")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}")
                    .retrieve()
                    .awaitBody<GmailMessagesResponse>()

            val serviceMessages = mutableListOf<ServiceMessage>()

            for (msgSummary in response.messages.orEmpty()) {
                val messageDetail =
                    webClient
                        .get()
                        .uri("https://gmail.googleapis.com/gmail/v1/users/me/messages/${msgSummary.id}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}")
                        .retrieve()
                        .awaitBody<GmailMessage>()

                val serviceMessage = parseGmailMessage(messageDetail, account)
                serviceMessages.add(serviceMessage)
            }

            logger.info { "Polled ${serviceMessages.size} new emails from Gmail for account ${account.id}" }
            serviceMessages
        } catch (e: Exception) {
            logger.error(e) { "Failed to poll Gmail for account ${account.id}" }
            emptyList()
        }
    }

    private fun parseGmailMessage(
        message: GmailMessage,
        account: EmailAccountDocument,
    ): ServiceMessage {
        val headers = message.payload.headers
        val subject = headers.find { it.name == "Subject" }?.value ?: "(no subject)"
        val from = headers.find { it.name == "From" }?.value ?: "unknown"
        val to = headers.find { it.name == "To" }?.value ?: ""
        val timestamp = Instant.ofEpochMilli(message.internalDate.toLong())

        val content = extractGmailContent(message.payload)

        return ServiceMessage(
            id = message.id,
            serviceTypeEnum = ServiceTypeEnum.EMAIL,
            clientId = account.clientId,
            projectId = account.projectId,
            content = content,
            author = from,
            timestamp = timestamp,
            metadata =
                mapOf(
                    "subject" to subject,
                    "from" to from,
                    "to" to to,
                ),
            threadId = message.threadId,
        )
    }

    private fun extractGmailContent(payload: GmailPayload): String {
        val parts = payload.parts.orEmpty()

        return parts
            .filter { it.mimeType == "text/plain" || it.mimeType == "text/html" }
            .mapNotNull { it.body.data }
            .joinToString("\n") { decodeBase64(it) }
            .ifEmpty { decodeBase64(payload.body.data ?: "") }
    }

    private fun decodeBase64(data: String): String =
        try {
            String(
                java.util.Base64
                    .getUrlDecoder()
                    .decode(data),
            )
        } catch (e: Exception) {
            ""
        }

    private suspend fun pollMicrosoftGraph(
        account: EmailAccountDocument,
        lastCheckTime: Instant?,
    ): List<ServiceMessage> {
        logger.info { "Polling Microsoft Graph for account ${account.id}" }

        return try {
            val response =
                webClient
                    .get()
                    .uri("https://graph.microsoft.com/v1.0/me/messages?\$top=500")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}")
                    .retrieve()
                    .awaitBody<GraphMessagesResponse>()

            val serviceMessages =
                response.value.map { graphMessage ->
                    ServiceMessage(
                        id = graphMessage.id,
                        serviceTypeEnum = ServiceTypeEnum.EMAIL,
                        clientId = account.clientId,
                        projectId = account.projectId,
                        content = graphMessage.body.content,
                        author = graphMessage.from.emailAddress.address,
                        timestamp = Instant.parse(graphMessage.receivedDateTime),
                        metadata =
                            mapOf(
                                "subject" to graphMessage.subject,
                                "from" to graphMessage.from.emailAddress.address,
                                "to" to graphMessage.toRecipients.joinToString(", ") { it.emailAddress.address },
                            ),
                        threadId = graphMessage.conversationId ?: graphMessage.id,
                    )
                }

            logger.info { "Polled ${serviceMessages.size} new emails from Microsoft Graph for account ${account.id}" }
            serviceMessages
        } catch (e: Exception) {
            logger.error(e) { "Failed to poll Microsoft Graph for account ${account.id}" }
            emptyList()
        }
    }
}

private data class GmailMessagesResponse(
    val messages: List<GmailMessageSummary>?,
    val resultSizeEstimate: Int,
)

private data class GmailMessageSummary(
    val id: String,
    val threadId: String,
)

private data class GmailMessage(
    val id: String,
    val threadId: String,
    val internalDate: String,
    val payload: GmailPayload,
)

private data class GmailPayload(
    val headers: List<GmailHeader>,
    val body: GmailBody,
    val parts: List<GmailPart>?,
)

private data class GmailHeader(
    val name: String,
    val value: String,
)

private data class GmailBody(
    val data: String?,
)

private data class GmailPart(
    val mimeType: String,
    val body: GmailBody,
)

private data class GraphMessagesResponse(
    val value: List<GraphMessage>,
)

private data class GraphMessage(
    val id: String,
    val conversationId: String?,
    val subject: String,
    val body: GraphMessageBody,
    val from: GraphRecipient,
    val toRecipients: List<GraphRecipient>,
    val receivedDateTime: String,
)

private data class GraphMessageBody(
    val content: String,
    val contentType: String,
)

private data class GraphRecipient(
    val emailAddress: GraphEmailAddress,
)

private data class GraphEmailAddress(
    val name: String?,
    val address: String,
)
