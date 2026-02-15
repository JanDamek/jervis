package com.jervis.rpc

import com.jervis.dto.coding.GpgCertificateDeleteDto
import com.jervis.dto.coding.GpgCertificateDto
import com.jervis.dto.coding.GpgCertificateUploadDto
import com.jervis.entity.GpgCertificateDocument
import com.jervis.repository.GpgCertificateRepository
import com.jervis.service.IGpgCertificateService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class GpgCertificateRpcImpl(
    private val gpgCertificateRepository: GpgCertificateRepository,
) : IGpgCertificateService {
    private val logger = KotlinLogging.logger {}

    override suspend fun getCertificates(clientId: String): List<GpgCertificateDto> {
        return gpgCertificateRepository.findByClientId(clientId)
            .map { it.toDto() }
            .toList()
    }

    override suspend fun uploadCertificate(request: GpgCertificateUploadDto): GpgCertificateDto {
        logger.info { "Uploading GPG certificate for client ${request.clientId}, keyId=${request.keyId}" }

        val doc = GpgCertificateDocument(
            clientId = request.clientId,
            keyId = request.keyId,
            userName = request.userName,
            userEmail = request.userEmail,
            privateKeyArmored = request.privateKeyArmored,
            passphrase = request.passphrase,
            createdAt = Instant.now(),
        )

        val saved = gpgCertificateRepository.save(doc)
        logger.info { "GPG certificate saved: id=${saved.id}, keyId=${saved.keyId}" }
        return saved.toDto()
    }

    override suspend fun deleteCertificate(request: GpgCertificateDeleteDto): Boolean {
        logger.info { "Deleting GPG certificate: id=${request.id}" }
        return try {
            gpgCertificateRepository.deleteById(request.id)
            true
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete GPG certificate ${request.id}" }
            false
        }
    }

    /**
     * Internal: get the active GPG key for a client (used by orchestrator for agent job creation).
     * Returns the private key armored text, or null if no certificate is configured.
     */
    suspend fun getActiveKey(clientId: String): GpgKeyInfo? {
        val doc = gpgCertificateRepository.findFirstByClientId(clientId) ?: return null
        return GpgKeyInfo(
            keyId = doc.keyId,
            userName = doc.userName,
            userEmail = doc.userEmail,
            privateKeyArmored = doc.privateKeyArmored,
            passphrase = doc.passphrase,
        )
    }

    private fun GpgCertificateDocument.toDto() = GpgCertificateDto(
        id = id ?: "",
        clientId = clientId,
        keyId = keyId,
        userName = userName,
        userEmail = userEmail,
        hasPrivateKey = privateKeyArmored.isNotBlank(),
        createdAt = createdAt.toString(),
    )
}

/** Internal data holder for GPG key distribution to agents. */
data class GpgKeyInfo(
    val keyId: String,
    val userName: String,
    val userEmail: String,
    val privateKeyArmored: String,
    val passphrase: String?,
)
