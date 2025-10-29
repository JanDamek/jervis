package com.jervis.service.sender

import com.jervis.domain.sender.SenderAlias
import com.jervis.domain.sender.SenderProfile
import com.jervis.entity.AliasType
import com.jervis.entity.RelationshipType
import com.jervis.mapper.toDomain
import com.jervis.mapper.toEntity
import com.jervis.repository.mongo.SenderProfileMongoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class SenderProfileService(
    private val repository: SenderProfileMongoRepository,
) {
    suspend fun findById(id: ObjectId): SenderProfile? = repository.findById(id)?.let { it.toDomain() }

    suspend fun findByIdentifier(identifier: String): SenderProfile? = repository.findByPrimaryIdentifier(identifier)?.let { it.toDomain() }

    suspend fun findByAnyAlias(identifier: String): SenderProfile? =
        repository
            .findByAliasesValueIn(listOf(identifier))
            .firstOrNull()
            ?.let { it.toDomain() }

    fun findByTopic(topic: String): Flow<SenderProfile> =
        repository
            .findByTopicsContaining(topic)
            .map { it.toDomain() }

    suspend fun findOrCreateProfile(
        identifier: String,
        displayName: String?,
        aliasType: AliasType,
    ): SenderProfile {
        findByIdentifier(identifier)?.let { return it }

        findByAnyAlias(identifier)?.let { existing ->
            return addAlias(
                profile = existing,
                type = aliasType,
                value = identifier,
                displayName = displayName,
            )
        }

        logger.info { "Creating new sender profile: $identifier" }

        val newProfile =
            SenderProfile(
                id = ObjectId(),
                primaryIdentifier = identifier,
                displayName = displayName,
                aliases =
                    listOf(
                        SenderAlias(
                            type = aliasType,
                            value = identifier,
                            displayName = displayName,
                            verified = true,
                            firstSeenAt = Instant.now(),
                            lastSeenAt = Instant.now(),
                        ),
                    ),
                relationship = inferRelationship(identifier, aliasType),
                organization = null,
                role = null,
                conversationSummary = null,
                lastSummaryUpdate = null,
                topics = emptyList(),
                communicationStats = null,
                firstSeenAt = Instant.now(),
                lastSeenAt = Instant.now(),
                lastInteractionAt = null,
                totalMessagesReceived = 0,
                totalMessagesSent = 0,
            )

        val entity = newProfile.toEntity()
        val saved = repository.save(entity)
        return saved.toDomain()
    }

    suspend fun addAlias(
        profile: SenderProfile,
        type: AliasType,
        value: String,
        displayName: String?,
    ): SenderProfile {
        val aliasExists = profile.aliases.any { it.value == value }
        if (aliasExists) {
            return profile
        }

        logger.info { "Adding alias to profile ${profile.id}: $type = $value" }

        val newAlias =
            SenderAlias(
                type = type,
                value = value,
                displayName = displayName,
                verified = false,
                firstSeenAt = Instant.now(),
                lastSeenAt = Instant.now(),
            )

        val updated =
            profile.copy(
                aliases = profile.aliases + newAlias,
                lastSeenAt = Instant.now(),
            )

        val entity = updated.toEntity()
        val saved = repository.save(entity)
        return saved.toDomain()
    }

    suspend fun updateLastSeen(profileId: ObjectId): SenderProfile? {
        val profile = findById(profileId) ?: return null
        val updated = profile.copy(lastSeenAt = Instant.now())
        val entity = updated.toEntity()
        val saved = repository.save(entity)
        return saved.toDomain()
    }

    suspend fun incrementMessagesReceived(profileId: ObjectId): SenderProfile? {
        val profile = findById(profileId) ?: return null
        val updated =
            profile.copy(
                totalMessagesReceived = profile.totalMessagesReceived + 1,
                lastInteractionAt = Instant.now(),
                lastSeenAt = Instant.now(),
            )
        val entity = updated.toEntity()
        val saved = repository.save(entity)
        return saved.toDomain()
    }

    suspend fun updateSummary(
        profileId: ObjectId,
        summary: String,
        topics: List<String>,
    ): SenderProfile? {
        val profile = findById(profileId) ?: return null
        val updated =
            profile.copy(
                conversationSummary = summary,
                topics = (profile.topics + topics).distinct().take(20),
                lastSummaryUpdate = Instant.now(),
            )
        val entity = updated.toEntity()
        val saved = repository.save(entity)
        return saved.toDomain()
    }

    suspend fun updateRelationship(
        profileId: ObjectId,
        relationship: RelationshipType,
        organization: String?,
        role: String?,
    ): SenderProfile? {
        val profile = findById(profileId) ?: return null
        val updated =
            profile.copy(
                relationship = relationship,
                organization = organization,
                role = role,
            )
        val entity = updated.toEntity()
        val saved = repository.save(entity)
        return saved.toDomain()
    }

    private fun inferRelationship(
        identifier: String,
        aliasType: AliasType,
    ): RelationshipType =
        when {
            identifier.contains("noreply", ignoreCase = true) -> RelationshipType.SYSTEM
            identifier.contains("support", ignoreCase = true) -> RelationshipType.SUPPORT
            identifier.contains("backup", ignoreCase = true) -> RelationshipType.SYSTEM
            identifier.contains("@localhost") -> RelationshipType.SYSTEM
            aliasType == AliasType.GIT_AUTHOR -> RelationshipType.COLLEAGUE
            else -> RelationshipType.UNKNOWN
        }
}
