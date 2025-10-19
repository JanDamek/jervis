package com.jervis.dto

import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitProviderEnum
import kotlinx.serialization.Serializable

/**
 * Request DTO for setting up Git configuration for a client.
 * Contains all necessary information to configure Git provider, authentication, and workflow rules.
 */
@Serializable
data class GitSetupRequestDto(
    val gitProvider: GitProviderEnum,
    val monoRepoUrl: String,
    val defaultBranch: String = "main",
    val gitAuthType: GitAuthTypeEnum,
    val sshPrivateKey: String? = null,
    val sshPublicKey: String? = null,
    val sshPassphrase: String? = null,
    val httpsToken: String? = null,
    val httpsUsername: String? = null,
    val httpsPassword: String? = null,
    val gpgPrivateKey: String? = null,
    val gpgKeyId: String? = null,
    val gpgPassphrase: String? = null,
    val gitConfig: GitConfigDto? = null,
)

/**
 * Result DTO from Git clone operation.
 */
@Serializable
data class CloneResultDto(
    val success: Boolean,
    val repositoryPath: String? = null,
    val message: String,
)
