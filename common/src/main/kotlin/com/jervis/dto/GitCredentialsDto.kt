package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * DTO carrying raw (unencrypted) Git credentials for internal use.
 * This project intentionally stores and returns credential values as-is.
 * Use only in trusted environments.
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
