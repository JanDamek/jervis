package com.jervis.service.git

import com.jervis.dto.coding.GpgCertificateDeleteDto
import com.jervis.dto.coding.GpgCertificateDto
import com.jervis.dto.coding.GpgCertificateUploadDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IGpgCertificateService {
    suspend fun getCertificates(clientId: String): List<GpgCertificateDto>
    suspend fun getAllCertificates(): List<GpgCertificateDto>
    suspend fun uploadCertificate(request: GpgCertificateUploadDto): GpgCertificateDto
    suspend fun deleteCertificate(request: GpgCertificateDeleteDto): Boolean
}
