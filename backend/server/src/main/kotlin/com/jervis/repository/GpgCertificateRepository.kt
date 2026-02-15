package com.jervis.repository

import com.jervis.entity.GpgCertificateDocument
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface GpgCertificateRepository : CoroutineCrudRepository<GpgCertificateDocument, String> {
    fun findByClientId(clientId: String): Flow<GpgCertificateDocument>
    suspend fun findFirstByClientId(clientId: String): GpgCertificateDocument?
}
