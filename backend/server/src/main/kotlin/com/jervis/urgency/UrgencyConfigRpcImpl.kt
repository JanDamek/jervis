package com.jervis.urgency

import com.jervis.common.types.ClientId
import com.jervis.dto.urgency.UrgencyConfigDto
import com.jervis.dto.urgency.UserPresenceDto
import com.jervis.service.urgency.IUrgencyConfigRpc
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class UrgencyConfigRpcImpl(
    private val repository: UrgencyConfigRepository,
    private val presenceCache: PresenceCache,
) : IUrgencyConfigRpc {
    private val logger = KotlinLogging.logger {}

    override suspend fun getUrgencyConfig(clientId: String): UrgencyConfigDto {
        val id = ClientId(ObjectId(clientId))
        val doc = repository.findByClientId(id) ?: UrgencyConfigDocument.default(id)
        return doc.toDto()
    }

    override suspend fun updateUrgencyConfig(config: UrgencyConfigDto): UrgencyConfigDto {
        val existing = repository.findByClientId(ClientId(ObjectId(config.clientId)))
        val doc = UrgencyConfigDocument.fromDto(config, existingId = existing?.id)
        val saved = repository.save(doc)
        logger.info {
            "UrgencyConfig updated for client=${config.clientId} — defaultDeadline=${saved.defaultDeadlineMinutes}min"
        }
        return saved.toDto()
    }

    override suspend fun getUserPresence(userId: String, platform: String): UserPresenceDto {
        // TTL pulled per-client would require a clientId — for now use a conservative default
        // matching the on-disk default (120s). Full per-client resolution comes when caller
        // supplies clientId in the signature (next iteration).
        return presenceCache.getDto(userId, platform, ttlSeconds = 120)
    }
}
