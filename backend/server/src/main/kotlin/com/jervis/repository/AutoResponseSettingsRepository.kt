package com.jervis.repository

import com.jervis.entity.AutoResponseSettingsDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface AutoResponseSettingsRepository : CoroutineCrudRepository<AutoResponseSettingsDocument, ObjectId> {

    suspend fun findByClientIdAndProjectIdAndChannelTypeAndChannelId(
        clientId: String?,
        projectId: String?,
        channelType: String?,
        channelId: String?,
    ): AutoResponseSettingsDocument?

    fun findByClientId(clientId: String): Flow<AutoResponseSettingsDocument>
}
