package com.jervis.repository

import com.jervis.entity.MaintenanceStateDocument
import com.jervis.entity.MaintenanceType
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface MaintenanceStateRepository : CoroutineCrudRepository<MaintenanceStateDocument, String> {

    suspend fun findByMaintenanceTypeAndClientId(maintenanceType: MaintenanceType, clientId: String): MaintenanceStateDocument?

    suspend fun findByMaintenanceType(maintenanceType: MaintenanceType): List<MaintenanceStateDocument>
}
