package com.jervis.service.sender

import com.jervis.domain.email.AliasTypeEnum
import com.jervis.domain.email.RelationshipTypeEnum
import com.jervis.domain.sender.SenderAlias
import com.jervis.domain.sender.SenderProfile
import com.jervis.mapper.toDomain
import com.jervis.mapper.toEntity
import com.jervis.repository.SenderProfileMongoRepository
import kotlinx.coroutines.flow.firstOrNull
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class SenderProfileService(
    private val repository: SenderProfileMongoRepository,
) {
    suspend fun findById(id: ObjectId): SenderProfile? = repository.findById(id)?.toDomain()

    suspend fun findByIdentifier(identifier: String): SenderProfile? =
        repository
            .findByPrimaryIdentifier(identifier)
            ?.toDomain()

    suspend fun findByAnyAlias(identifier: String): SenderProfile? =
        repository
            .findByAliasesValueIn(listOf(identifier))
            .firstOrNull()
            ?.toDomain()

    suspend fun findOrCreateProfile(
        identifier: String,
        displayName: String?,
        aliasType: AliasTypeEnum,
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
        type: AliasTypeEnum,
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

    private fun inferRelationship(
        identifier: String,
        aliasType: AliasTypeEnum,
    ): RelationshipTypeEnum =
        when {
            identifier.contains("noreply", ignoreCase = true) -> RelationshipTypeEnum.SYSTEM
            identifier.contains("support", ignoreCase = true) -> RelationshipTypeEnum.SUPPORT
            identifier.contains("backup", ignoreCase = true) -> RelationshipTypeEnum.SYSTEM
            identifier.contains("@localhost") -> RelationshipTypeEnum.SYSTEM
            aliasType == AliasTypeEnum.GIT_AUTHOR -> RelationshipTypeEnum.COLLEAGUE
            else -> RelationshipTypeEnum.UNKNOWN
        }
}
