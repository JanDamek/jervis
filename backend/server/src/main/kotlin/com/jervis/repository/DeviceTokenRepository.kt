package com.jervis.repository

import com.jervis.entity.DeviceTokenDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface DeviceTokenRepository : CoroutineCrudRepository<DeviceTokenDocument, ObjectId> {
    fun findByClientId(clientId: String): Flow<DeviceTokenDocument>

    fun findByClientIdAndPlatform(clientId: String, platform: String): Flow<DeviceTokenDocument>

    suspend fun findByDeviceId(deviceId: String): DeviceTokenDocument?

    suspend fun deleteByDeviceId(deviceId: String)
}
