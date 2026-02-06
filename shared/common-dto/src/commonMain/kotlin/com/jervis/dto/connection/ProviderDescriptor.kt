package com.jervis.dto.connection

import kotlinx.serialization.Serializable

@Serializable
data class ProviderDescriptor(
    val provider: ProviderEnum,
    val displayName: String,
    val capabilities: Set<ConnectionCapability>,
    val protocols: Set<ProtocolEnum>,
    val authOptions: List<AuthOption>,
    val defaultCloudBaseUrl: String? = null,
    val supportsCloud: Boolean = true,
    val supportsSelfHosted: Boolean = false,
    val oauth2AuthorizationUrl: String? = null,
    val oauth2TokenUrl: String? = null,
    val oauth2Scopes: String = "",
    val defaultPollingIntervalSeconds: Int = 300,
) {
    /** Convenience: list of supported auth types derived from authOptions. */
    val authTypes: List<AuthTypeEnum> get() = authOptions.map { it.authType }

    fun authOption(authType: AuthTypeEnum): AuthOption? = authOptions.firstOrNull { it.authType == authType }

    companion object {
        val defaults: List<ProviderDescriptor> = listOf(
            ProviderDescriptor(
                provider = ProviderEnum.GITHUB,
                displayName = "GitHub",
                capabilities = setOf(ConnectionCapability.REPOSITORY, ConnectionCapability.BUGTRACKER),
                protocols = setOf(ProtocolEnum.HTTP),
                defaultCloudBaseUrl = "https://github.com",
                supportsCloud = true,
                supportsSelfHosted = true,
                authOptions = listOf(
                    AuthOption(
                        authType = AuthTypeEnum.OAUTH2,
                        displayName = "OAuth 2.0",
                        fields = listOf(
                            FormField(FormFieldType.CLOUD_TOGGLE, "Cloud (veřejná instance)", defaultValue = "true"),
                            FormField(FormFieldType.BASE_URL, "Base URL", placeholder = "https://github.example.com", required = false),
                        ),
                    ),
                    AuthOption(
                        authType = AuthTypeEnum.BEARER,
                        displayName = "Personal Access Token",
                        fields = listOf(
                            FormField(FormFieldType.BASE_URL, "Base URL", placeholder = "https://github.example.com"),
                            FormField(FormFieldType.BEARER_TOKEN, "Personal Access Token", isSecret = true),
                        ),
                    ),
                ),
            ),
            ProviderDescriptor(
                provider = ProviderEnum.GITLAB,
                displayName = "GitLab",
                capabilities = setOf(ConnectionCapability.REPOSITORY, ConnectionCapability.BUGTRACKER, ConnectionCapability.WIKI),
                protocols = setOf(ProtocolEnum.HTTP),
                defaultCloudBaseUrl = "https://gitlab.com",
                supportsCloud = true,
                supportsSelfHosted = true,
                authOptions = listOf(
                    AuthOption(
                        authType = AuthTypeEnum.OAUTH2,
                        displayName = "OAuth 2.0",
                        fields = listOf(
                            FormField(FormFieldType.CLOUD_TOGGLE, "Cloud (veřejná instance)", defaultValue = "true"),
                            FormField(FormFieldType.BASE_URL, "Base URL", placeholder = "https://gitlab.example.com", required = false),
                        ),
                    ),
                    AuthOption(
                        authType = AuthTypeEnum.BEARER,
                        displayName = "Personal Access Token",
                        fields = listOf(
                            FormField(FormFieldType.BASE_URL, "Base URL", placeholder = "https://gitlab.example.com"),
                            FormField(FormFieldType.BEARER_TOKEN, "Personal Access Token", isSecret = true),
                        ),
                    ),
                ),
            ),
            ProviderDescriptor(
                provider = ProviderEnum.ATLASSIAN,
                displayName = "Atlassian (Jira + Confluence)",
                capabilities = setOf(ConnectionCapability.REPOSITORY, ConnectionCapability.BUGTRACKER, ConnectionCapability.WIKI),
                protocols = setOf(ProtocolEnum.HTTP),
                supportsCloud = true,
                supportsSelfHosted = true,
                authOptions = listOf(
                    AuthOption(
                        authType = AuthTypeEnum.OAUTH2,
                        displayName = "OAuth 2.0",
                        fields = listOf(
                            FormField(FormFieldType.BASE_URL, "Atlassian URL", placeholder = "https://yourcompany.atlassian.net"),
                        ),
                    ),
                    AuthOption(
                        authType = AuthTypeEnum.BASIC,
                        displayName = "API Token",
                        fields = listOf(
                            FormField(FormFieldType.BASE_URL, "Atlassian URL", placeholder = "https://yourcompany.atlassian.net"),
                            FormField(FormFieldType.USERNAME, "Email"),
                            FormField(FormFieldType.PASSWORD, "API Token", isSecret = true),
                        ),
                    ),
                ),
            ),
            ProviderDescriptor(
                provider = ProviderEnum.GOOGLE_WORKSPACE,
                displayName = "Google Workspace (Gmail)",
                capabilities = setOf(ConnectionCapability.EMAIL_READ),
                protocols = setOf(ProtocolEnum.IMAP),
                authOptions = listOf(
                    AuthOption(
                        authType = AuthTypeEnum.OAUTH2,
                        displayName = "OAuth 2.0",
                        fields = emptyList(),
                    ),
                ),
            ),
            ProviderDescriptor(
                provider = ProviderEnum.MICROSOFT_365,
                displayName = "Microsoft 365 (Outlook)",
                capabilities = setOf(ConnectionCapability.EMAIL_READ),
                protocols = setOf(ProtocolEnum.IMAP),
                authOptions = listOf(
                    AuthOption(
                        authType = AuthTypeEnum.OAUTH2,
                        displayName = "OAuth 2.0",
                        fields = emptyList(),
                    ),
                    AuthOption(
                        authType = AuthTypeEnum.BASIC,
                        displayName = "App Password",
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
            ProviderDescriptor(
                provider = ProviderEnum.GENERIC_EMAIL,
                displayName = "Generic Email (IMAP/POP3/SMTP)",
                capabilities = setOf(ConnectionCapability.EMAIL_READ, ConnectionCapability.EMAIL_SEND),
                protocols = setOf(ProtocolEnum.IMAP, ProtocolEnum.POP3, ProtocolEnum.SMTP),
                authOptions = listOf(
                    AuthOption(
                        authType = AuthTypeEnum.BASIC,
                        displayName = "Username / Password",
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

        val defaultsByProvider: Map<ProviderEnum, ProviderDescriptor> =
            defaults.associateBy { it.provider }
    }
}

@Serializable
data class AuthOption(
    val authType: AuthTypeEnum,
    val displayName: String,
    val fields: List<FormField>,
)

@Serializable
data class FormField(
    val type: FormFieldType,
    val label: String,
    val placeholder: String = "",
    val required: Boolean = true,
    val defaultValue: String = "",
    val isSecret: Boolean = false,
)

@Serializable
enum class FormFieldType {
    BASE_URL,
    USERNAME,
    PASSWORD,
    BEARER_TOKEN,
    HOST,
    PORT,
    USE_SSL,
    FOLDER_NAME,
    CLOUD_TOGGLE,
    PROTOCOL,
}
