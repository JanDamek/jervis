package com.jervis.dto.coding

import kotlinx.serialization.Serializable

/**
 * GPG certificate for coding agent commit signing.
 * Each client can have one active GPG key used by all coding agents.
 */
@Serializable
data class GpgCertificateDto(
    val id: String = "",
    val clientId: String,
    val keyId: String,
    val userName: String,
    val userEmail: String,
    val hasPrivateKey: Boolean = false,
    val createdAt: String = "",
)

/** Upload request — includes the actual private key (never returned in GpgCertificateDto). */
@Serializable
data class GpgCertificateUploadDto(
    val clientId: String,
    val keyId: String,
    val userName: String,
    val userEmail: String,
    val privateKeyArmored: String,
    val passphrase: String? = null,
)

/** Delete request. */
@Serializable
data class GpgCertificateDeleteDto(
    val id: String,
)
