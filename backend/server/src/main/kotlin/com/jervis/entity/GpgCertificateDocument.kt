package com.jervis.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Stores GPG private keys for coding agent commit signing.
 * Keys are global — any key can be used by any client/project.
 * clientId is kept for backwards compatibility but no longer required.
 */
@Document(collection = "gpg_certificates")
data class GpgCertificateDocument(
    @Id
    val id: String? = null,
    @Indexed
    val clientId: String,
    val keyId: String,
    val userName: String,
    val userEmail: String,
    val privateKeyArmored: String,
    val passphrase: String? = null,
    val createdAt: Instant = Instant.now(),
)
