package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * DTO for Git credentials (decrypted).
 * Used to return existing credentials to the UI for editing.
 */
@Serializable
data class GitCredentialsDto(
    val sshPrivateKey: String? = null,
    val sshPublicKey: String? = null,
    val sshPassphrase: String? = null,
    val httpsToken: String? = null,
    val httpsUsername: String? = null,
    val httpsPassword: String? = null,
    val gpgPrivateKey: String? = null,
    val gpgPublicKey: String? = null,
    val gpgPassphrase: String? = null,
)
