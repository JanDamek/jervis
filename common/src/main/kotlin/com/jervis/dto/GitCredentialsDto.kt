package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * DTO for Git credentials display.
 * Sensitive fields (private keys, passphrases, tokens) are masked - only presence is indicated.
 * Public keys can be displayed in full.
 */
@Serializable
data class GitCredentialsDto(
    val hasSshPrivateKey: Boolean = false,
    val sshPublicKey: String? = null,
    val hasSshPassphrase: Boolean = false,
    val hasHttpsToken: Boolean = false,
    val httpsUsername: String? = null,
    val hasHttpsPassword: Boolean = false,
    val hasGpgPrivateKey: Boolean = false,
    val gpgPublicKey: String? = null,
    val hasGpgPassphrase: Boolean = false,
)
