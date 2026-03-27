package com.jervis.maintenance

import com.jervis.maintenance.MaintenanceStateDocument
import com.jervis.maintenance.MaintenanceType
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface MaintenanceStateRepository : CoroutineCrudRepository<MaintenanceStateDocument, String> {

    suspend fun findByMaintenanceTypeAndClientId(maintenanceType: MaintenanceType, clientId: String): MaintenanceStateDocument?

    suspend fun findByMaintenanceType(maintenanceType: MaintenanceType): List<MaintenanceStateDocument>
}
