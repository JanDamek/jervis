package com.jervis.dto.coding

import kotlinx.serialization.Serializable

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

@Serializable
data class GpgCertificateUploadDto(
    val clientId: String,
    val keyId: String,
    val userName: String,
    val userEmail: String,
    val privateKeyArmored: String,
    val passphrase: String? = null,
)

@Serializable
data class GpgCertificateDeleteDto(
    val id: String,
)
