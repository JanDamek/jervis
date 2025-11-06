package com.jervis.dto

import com.jervis.domain.git.GitAuthTypeEnum
import kotlinx.serialization.Serializable

/**
 * Request DTO for setting up Git override configuration for a project.
 * Allows projects to use different Git provider, authentication, and credentials than the client.
 */
@Serializable
data class ProjectGitOverrideRequestDto(
    val gitRemoteUrl: String? = null,
    val gitAuthType: GitAuthTypeEnum? = null,
    val sshPrivateKey: String? = null,
    val sshPublicKey: String? = null,
    val sshPassphrase: String? = null,
    val httpsToken: String? = null,
    val httpsUsername: String? = null,
    val httpsPassword: String? = null,
    val gpgPrivateKey: String? = null,
    val gpgPublicKey: String? = null,
    val gpgPassphrase: String? = null,
    val gitConfig: GitConfigDto? = null,
)
