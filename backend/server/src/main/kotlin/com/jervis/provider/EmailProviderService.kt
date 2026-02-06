package com.jervis.provider

import com.jervis.common.client.IProviderService
import com.jervis.common.client.ProviderListResourcesRequest
import com.jervis.common.client.ProviderTestRequest
import com.jervis.configuration.ProviderRegistry
import com.jervis.dto.connection.AuthOption
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionTestResultDto
import com.jervis.dto.connection.FormField
import com.jervis.dto.connection.FormFieldType
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderDescriptor
import com.jervis.dto.connection.ProviderEnum
import jakarta.annotation.PostConstruct
import jakarta.mail.Authenticator
import jakarta.mail.Folder
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.Properties

@Service
class EmailProviderService(
    private val providerRegistry: ProviderRegistry,
) : IProviderService {
    private val logger = KotlinLogging.logger {}

    private val emailDescriptors = mapOf(
        ProviderEnum.GOOGLE_WORKSPACE to ProviderDescriptor(
            provider = ProviderEnum.GOOGLE_WORKSPACE,
            displayName = "Google Workspace (Gmail)",
            capabilities = setOf(ConnectionCapability.EMAIL_READ),
            protocols = setOf(ProtocolEnum.IMAP),
            authOptions = listOf(
                AuthOption(AuthTypeEnum.OAUTH2, "OAuth 2.0", fields = emptyList()),
            ),
        ),
        ProviderEnum.MICROSOFT_365 to ProviderDescriptor(
            provider = ProviderEnum.MICROSOFT_365,
            displayName = "Microsoft 365 (Outlook)",
            capabilities = setOf(ConnectionCapability.EMAIL_READ),
            protocols = setOf(ProtocolEnum.IMAP),
            authOptions = listOf(
                AuthOption(AuthTypeEnum.OAUTH2, "OAuth 2.0", fields = emptyList()),
                AuthOption(
                    AuthTypeEnum.BASIC, "App Password",
                    fields = listOf(
                        FormField(FormFieldType.HOST, "IMAP Host", placeholder = "outlook.office365.com", defaultValue = "outlook.office365.com"),
                        FormField(FormFieldType.PORT, "Port", defaultValue = "993"),
                        FormField(FormFieldType.USE_SSL, "SSL", defaultValue = "true"),
                        FormField(FormFieldType.USERNAME, "Email"),
                        FormField(FormFieldType.PASSWORD, "App Password", isSecret = true),
                        FormField(FormFieldType.FOLDER_NAME, "Složka", required = false, defaultValue = "INBOX"),
                    ),
                ),
            ),
        ),
        ProviderEnum.GENERIC_EMAIL to ProviderDescriptor(
            provider = ProviderEnum.GENERIC_EMAIL,
            displayName = "Generic Email (IMAP/POP3/SMTP)",
            capabilities = setOf(ConnectionCapability.EMAIL_READ, ConnectionCapability.EMAIL_SEND),
            protocols = setOf(ProtocolEnum.IMAP, ProtocolEnum.POP3, ProtocolEnum.SMTP),
            authOptions = listOf(
                AuthOption(
                    AuthTypeEnum.BASIC, "Username / Password",
                    fields = listOf(
                        FormField(FormFieldType.PROTOCOL, "Protokol"),
                        FormField(FormFieldType.HOST, "Host", placeholder = "imap.example.com"),
                        FormField(FormFieldType.PORT, "Port", defaultValue = "993"),
                        FormField(FormFieldType.USE_SSL, "SSL", defaultValue = "true"),
                        FormField(FormFieldType.USERNAME, "Username"),
                        FormField(FormFieldType.PASSWORD, "Password", isSecret = true),
                        FormField(FormFieldType.FOLDER_NAME, "Složka", required = false, defaultValue = "INBOX"),
                    ),
                ),
            ),
        ),
    )

    @PostConstruct
    fun register() {
        for ((provider, descriptor) in emailDescriptors) {
            providerRegistry.registerLocal(provider, this, descriptor)
        }
    }

    override suspend fun getDescriptor(): ProviderDescriptor =
        emailDescriptors[ProviderEnum.GENERIC_EMAIL]!!

    override suspend fun testConnection(request: ProviderTestRequest): ConnectionTestResultDto =
        withContext(Dispatchers.IO) {
            when (request.protocol) {
                ProtocolEnum.IMAP -> testImapConnection(request)
                ProtocolEnum.POP3 -> testPop3Connection(request)
                ProtocolEnum.SMTP -> testSmtpConnection(request)
                ProtocolEnum.HTTP -> ConnectionTestResultDto(
                    success = false,
                    message = "Email providers do not use HTTP protocol",
                )
            }
        }

    override suspend fun listResources(request: ProviderListResourcesRequest): List<ConnectionResourceDto> =
        withContext(Dispatchers.IO) {
            if (request.capability != ConnectionCapability.EMAIL_READ) return@withContext emptyList()
            listEmailFolders(request)
        }

    private fun testImapConnection(request: ProviderTestRequest): ConnectionTestResultDto {
        val properties = Properties().apply {
            setProperty("mail.store.protocol", "imap")
            setProperty("mail.imap.host", request.host ?: "")
            setProperty("mail.imap.port", (request.port ?: 993).toString())
            if (request.useSsl != false) {
                setProperty("mail.imap.ssl.enable", "true")
                setProperty("mail.imap.ssl.trust", "*")
            }
            setProperty("mail.imap.connectiontimeout", "10000")
            setProperty("mail.imap.timeout", "10000")
        }
        val session = Session.getInstance(properties)
        val store = session.getStore("imap")
        store.connect(request.host, request.port ?: 993, request.username, request.password)
        val folder = store.getFolder(request.folderName ?: "INBOX")
        folder.open(Folder.READ_ONLY)
        val messageCount = folder.messageCount
        folder.close(false)
        store.close()
        return ConnectionTestResultDto(
            success = true,
            message = "IMAP connection successful! Found $messageCount messages in ${request.folderName ?: "INBOX"}",
            details = mapOf(
                "host" to (request.host ?: ""),
                "port" to (request.port ?: 993).toString(),
                "messageCount" to messageCount.toString(),
            ),
        )
    }

    private fun testPop3Connection(request: ProviderTestRequest): ConnectionTestResultDto {
        val properties = Properties().apply {
            setProperty("mail.store.protocol", "pop3")
            setProperty("mail.pop3.host", request.host ?: "")
            setProperty("mail.pop3.port", (request.port ?: 995).toString())
            if (request.useSsl != false) {
                setProperty("mail.pop3.ssl.enable", "true")
                setProperty("mail.pop3.ssl.trust", "*")
            }
            setProperty("mail.pop3.connectiontimeout", "10000")
            setProperty("mail.pop3.timeout", "10000")
        }
        val session = Session.getInstance(properties)
        val store = session.getStore("pop3")
        store.connect(request.host, request.port ?: 995, request.username, request.password)
        val folder = store.getFolder("INBOX")
        folder.open(Folder.READ_ONLY)
        val messageCount = folder.messageCount
        folder.close(false)
        store.close()
        return ConnectionTestResultDto(
            success = true,
            message = "POP3 connection successful! Found $messageCount messages",
            details = mapOf(
                "host" to (request.host ?: ""),
                "port" to (request.port ?: 995).toString(),
                "messageCount" to messageCount.toString(),
            ),
        )
    }

    private fun testSmtpConnection(request: ProviderTestRequest): ConnectionTestResultDto {
        val properties = Properties().apply {
            setProperty("mail.smtp.host", request.host ?: "")
            setProperty("mail.smtp.port", (request.port ?: 587).toString())
            setProperty("mail.smtp.auth", "true")
            if (request.useTls == true) {
                setProperty("mail.smtp.starttls.enable", "true")
                setProperty("mail.smtp.ssl.trust", "*")
            }
            setProperty("mail.smtp.connectiontimeout", "10000")
            setProperty("mail.smtp.timeout", "10000")
        }
        val session = Session.getInstance(
            properties,
            object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(request.username, request.password)
            },
        )
        val transport = session.getTransport("smtp")
        transport.connect(request.host, request.port ?: 587, request.username, request.password)
        transport.close()
        return ConnectionTestResultDto(
            success = true,
            message = "SMTP connection successful! Server is ready to send emails",
            details = mapOf(
                "host" to (request.host ?: ""),
                "port" to (request.port ?: 587).toString(),
            ),
        )
    }

    private fun listEmailFolders(request: ProviderListResourcesRequest): List<ConnectionResourceDto> {
        val protocol = if (request.protocol == ProtocolEnum.IMAP) "imap" else "pop3"
        val properties = Properties().apply {
            setProperty("mail.store.protocol", protocol)
            setProperty("mail.$protocol.host", request.host ?: "")
            setProperty("mail.$protocol.port", (request.port ?: 993).toString())
            if (request.useSsl != false) {
                setProperty("mail.$protocol.ssl.enable", "true")
                setProperty("mail.$protocol.ssl.trust", "*")
            }
            setProperty("mail.$protocol.connectiontimeout", "10000")
            setProperty("mail.$protocol.timeout", "10000")
        }
        val session = Session.getInstance(properties)
        val store = session.getStore(protocol)
        store.connect(request.host, request.port ?: 993, request.username, request.password)
        val folders = mutableListOf<ConnectionResourceDto>()
        listFoldersRecursively(store.defaultFolder, folders)
        store.close()
        return folders
    }

    private fun listFoldersRecursively(folder: Folder, result: MutableList<ConnectionResourceDto>) {
        for (subfolder in folder.list()) {
            result.add(
                ConnectionResourceDto(
                    id = subfolder.fullName,
                    name = subfolder.name,
                    description = "Email folder: ${subfolder.fullName}",
                    capability = ConnectionCapability.EMAIL_READ,
                ),
            )
            if ((subfolder.type and Folder.HOLDS_FOLDERS) != 0) {
                listFoldersRecursively(subfolder, result)
            }
        }
    }
}
