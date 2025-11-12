package com.jervis.mapper

import com.jervis.dto.confluence.ConfluenceAccountDto
import com.jervis.dto.confluence.ConfluenceAccountStatsDto
import com.jervis.dto.confluence.ConfluencePageDto
import com.jervis.entity.ConfluenceAccountDocument
import com.jervis.entity.ConfluencePageDocument
import com.jervis.service.confluence.state.ConfluenceIndexingStats

/**
 * Extension function to convert Entity to DTO.
 * Used in Controller layer (mapping happens at boundary).
 */
fun ConfluenceAccountDocument.toDto(stats: ConfluenceIndexingStats? = null): ConfluenceAccountDto =
    ConfluenceAccountDto(
        id = id.toHexString(),
        clientId = clientId.toHexString(),
        projectId = projectId?.toHexString(),
        cloudId = cloudId,
        siteName = siteName,
        siteUrl = siteUrl,
        spaceKeys = spaceKeys,
        isActive = isActive,
        authStatus = authStatus,
        lastPolledAt = lastPolledAt?.toString(),
        lastSuccessfulSyncAt = lastSuccessfulSyncAt?.toString(),
        lastErrorMessage = lastErrorMessage,
        lastAuthCheckedAt = lastAuthCheckedAt?.toString(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        stats =
            stats?.let {
                ConfluenceAccountStatsDto(
                    totalPages = it.totalPages,
                    indexedPages = it.indexedPages,
                    newPages = it.newPages,
                    failedPages = it.failedPages,
                    totalSpaces = spaceKeys.size,
                )
            },
    )

fun ConfluencePageDocument.toDto(): ConfluencePageDto =
    ConfluencePageDto(
        id = id.toHexString(),
        accountId = accountId.toHexString(),
        pageId = pageId,
        spaceKey = spaceKey,
        title = title,
        url = url,
        lastKnownVersion = lastKnownVersion,
        state = state.name,
        parentPageId = parentPageId,
        internalLinksCount = internalLinks.size,
        externalLinksCount = externalLinks.size,
        childPagesCount = childPageIds.size,
        lastModifiedBy = lastModifiedBy,
        lastModifiedAt = lastModifiedAt?.toString(),
        lastIndexedAt = lastIndexedAt?.toString(),
        errorMessage = errorMessage,
    )
