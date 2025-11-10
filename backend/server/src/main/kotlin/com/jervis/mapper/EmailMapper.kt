package com.jervis.mapper

import com.jervis.domain.email.EmailAccount
import com.jervis.entity.EmailAccountDocument

/**
 * Mappers for EmailAccount between Entity (persistence) ↔ Domain (business logic).
 * Used in Service layer for database operations.
 */

// Entity → Domain (used in Service layer when reading from DB)
fun EmailAccountDocument.toDomain(): EmailAccount =
    EmailAccount(
        id = this.id,
        clientId = this.clientId,
        projectId = this.projectId,
        provider = this.provider,
        displayName = this.displayName,
        description = this.description,
        email = this.email,
        username = this.username,
        password = this.password,
        serverHost = this.serverHost,
        serverPort = this.serverPort,
        useSsl = this.useSsl,
        isActive = this.isActive,
        lastPolledAt = this.lastPolledAt,
        highestSeenUid = this.highestSeenUid,
    )

// Domain → Entity (used in Service layer when saving to DB)
fun EmailAccount.toEntity(): EmailAccountDocument =
    EmailAccountDocument(
        id = this.id,
        clientId = this.clientId,
        projectId = this.projectId,
        provider = this.provider,
        displayName = this.displayName,
        description = this.description,
        email = this.email,
        username = this.username,
        password = this.password,
        serverHost = this.serverHost,
        serverPort = this.serverPort,
        useSsl = this.useSsl,
        isActive = this.isActive,
        lastPolledAt = this.lastPolledAt,
        highestSeenUid = this.highestSeenUid,
    )
