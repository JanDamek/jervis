package com.jervis.dto.connection

import kotlinx.serialization.Serializable

@Serializable
data class ProviderDescriptor(
    val provider: ProviderEnum,
    val displayName: String,
    val capabilities: Set<ConnectionCapability>,
    val authTypes: List<AuthTypeEnum>,
    val protocols: Set<ProtocolEnum>,
    val defaultCloudBaseUrl: String? = null,
    val supportsCloud: Boolean = true,
    val supportsSelfHosted: Boolean = false,
    val oauth2AuthorizationUrl: String? = null,
    val oauth2TokenUrl: String? = null,
    val oauth2Scopes: String = "",
    val uiHints: ProviderUiHints = ProviderUiHints(),
    val defaultPollingIntervalSeconds: Int = 300,
)

@Serializable
data class ProviderUiHints(
    val showBaseUrl: Boolean = true,
    val baseUrlPlaceholder: String = "https://",
    val usernameLabel: String = "Username",
    val passwordLabel: String = "Password",
    val showCloudToggle: Boolean = false,
    val showEmailFields: Boolean = false,
)
