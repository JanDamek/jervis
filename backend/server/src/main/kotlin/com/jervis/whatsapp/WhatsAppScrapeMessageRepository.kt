package com.jervis.whatsapp

import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface WhatsAppScrapeMessageRepository : CoroutineCrudRepository<WhatsAppScrapeMessageDocument, ObjectId> {
    fun findByConnectionIdAndState(connectionId: String, state: String): Flow<WhatsAppScrapeMessageDocument>
}
