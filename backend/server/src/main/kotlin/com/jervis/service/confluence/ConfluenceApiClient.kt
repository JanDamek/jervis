package com.jervis.service.confluence

import com.jervis.domain.confluence.ConfluencePage
import com.jervis.domain.confluence.ConfluencePageContent
import com.jervis.domain.confluence.ConfluenceSpace
import com.jervis.domain.confluence.PageVersion
import com.jervis.entity.ConfluenceAccountDocument
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Confluence Cloud REST API v2 client using Ktor (coroutines-first).
 *
 * Documentation: https://developer.atlassian.com/cloud/confluence/rest/v2/
 *
 * Architecture:
 * - Uses Ktor HttpClient (coroutines-based, NOT Reactor WebClient)
 * - Returns domain models (not DTOs)
 * - Internal DTOs map to domain at API boundary
 * - All operations are suspend functions with Flow for collections
 *
 * Change Detection:
 * - Each page has 'version.number' that increments on edit
 * - Query: ?body-format=storage (gets XHTML content)
 */
@Component
class ConfluenceApiClient {
    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    suspend fun listSpaces(account: ConfluenceAccountDocument): Flow<ConfluenceSpace> =
        flow {
            var cursor: String? = null

            do {
                val client = createHttpClient(account)
                try {
                    val response =
                        client
                            .get("${account.siteUrl}/wiki/api/v2/spaces") {
                                parameter("limit", 250)
                                cursor?.let { parameter("cursor", it) }
                            }.body<SpacesResponseDto>()

                    response.results.forEach { emit(it.toDomain()) }
                    cursor = response.links?.next?.let { extractCursor(it) }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch spaces for account ${account.id}" }
                    client.close()
                    throw e
                }
                client.close()
            } while (cursor != null)
        }

    suspend fun listPagesInSpace(
        account: ConfluenceAccountDocument,
        spaceKey: String,
        modifiedSince: Instant? = null,
    ): Flow<ConfluencePage> =
        flow {
            var cursor: String? = null

            do {
                val client = createHttpClient(account)
                try {
                    val response =
                        client
                            .get("${account.siteUrl}/wiki/api/v2/pages") {
                                parameter("space-key", spaceKey)
                                parameter("limit", 250)
                                parameter("sort", "-modified-date")
                                cursor?.let { parameter("cursor", it) }
                            }.body<PagesResponseDto>()

                    for (pageDto in response.results) {
                        val pageDomain = pageDto.toDomain()
                        if (modifiedSince != null && pageDomain.version.createdAt.isBefore(modifiedSince)) {
                            logger.debug { "Reached pages older than $modifiedSince, stopping" }
                            client.close()
                            return@flow
                        }
                        emit(pageDomain)
                    }

                    cursor = response.links?.next?.let { extractCursor(it) }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch pages for space $spaceKey" }
                    client.close()
                    throw e
                }
                client.close()
            } while (cursor != null)
        }

    suspend fun getPageContent(
        account: ConfluenceAccountDocument,
        pageId: String,
    ): ConfluencePageContent? {
        val client = createHttpClient(account)
        return try {
            val response =
                client
                    .get("${account.siteUrl}/wiki/api/v2/pages/$pageId") {
                        parameter("body-format", "storage")
                    }.body<ConfluencePageContentDto>()

            response.toDomain()
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch content for page $pageId" }
            null
        } finally {
            client.close()
        }
    }

    suspend fun getChildPages(
        account: ConfluenceAccountDocument,
        pageId: String,
    ): Flow<ConfluencePage> =
        flow {
            var cursor: String? = null

            do {
                val client = createHttpClient(account)
                try {
                    val response =
                        client
                            .get("${account.siteUrl}/wiki/api/v2/pages/$pageId/children") {
                                parameter("limit", 250)
                                cursor?.let { parameter("cursor", it) }
                            }.body<PagesResponseDto>()

                    response.results.forEach { emit(it.toDomain()) }
                    cursor = response.links?.next?.let { extractCursor(it) }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch child pages for $pageId" }
                    client.close()
                    throw e
                }
                client.close()
            } while (cursor != null)
        }

    private fun createHttpClient(account: ConfluenceAccountDocument): HttpClient =
        HttpClient {
            install(ContentNegotiation) {
                json(json)
            }
            defaultRequest {
                bearerAuth(account.accessToken)
                contentType(ContentType.Application.Json)
            }
        }

    private fun extractCursor(nextLink: String): String? =
        nextLink
            .substringAfter("cursor=", "")
            .substringBefore("&")
            .takeIf { it.isNotBlank() }
}

// ========== Internal API Response DTOs ==========

@Serializable
private data class SpacesResponseDto(
    val results: List<ConfluenceSpaceDto>,
    @SerialName("_links")
    val links: ResponseLinksDto? = null,
)

@Serializable
private data class PagesResponseDto(
    val results: List<ConfluencePageDto>,
    @SerialName("_links")
    val links: ResponseLinksDto? = null,
)

@Serializable
private data class ResponseLinksDto(
    val next: String? = null,
)

@Serializable
private data class ConfluenceSpaceDto(
    val id: String,
    val key: String,
    val name: String,
    val type: String,
    val status: String,
    @SerialName("_links")
    val links: Map<String, String> = emptyMap(),
) {
    fun toDomain() =
        ConfluenceSpace(
            id = id,
            key = key,
            name = name,
            type = type,
            status = status,
            links = links,
        )
}

@Serializable
private data class ConfluencePageDto(
    val id: String,
    val status: String,
    val title: String,
    val spaceId: String,
    val parentId: String? = null,
    val version: PageVersionDto,
    @SerialName("_links")
    val links: Map<String, String> = emptyMap(),
) {
    fun toDomain() =
        ConfluencePage(
            id = id,
            status = status,
            title = title,
            spaceId = spaceId,
            parentId = parentId,
            version = version.toDomain(),
            links = links,
        )
}

@Serializable
private data class PageVersionDto(
    val number: Int,
    // Represent as String to avoid kotlinx Instant serializer; parse manually
    val createdAt: String,
    val message: String? = null,
    val authorId: String? = null,
) {
    fun toDomain() =
        PageVersion(
            number = number,
            createdAt = runCatching { Instant.parse(createdAt) }.getOrElse { Instant.now() },
            message = message,
            authorId = authorId,
        )
}

@Serializable
private data class ConfluencePageContentDto(
    val id: String,
    val status: String,
    val title: String,
    val spaceId: String,
    val parentId: String? = null,
    val version: PageVersionDto,
    val body: PageBodyDto? = null,
    @SerialName("_links")
    val links: Map<String, String> = emptyMap(),
) {
    fun toDomain() =
        ConfluencePageContent(
            id = id,
            status = status,
            title = title,
            spaceId = spaceId,
            parentId = parentId,
            version = version.toDomain(),
            bodyHtml = body?.storage?.value,
            links = links,
        )
}

@Serializable
private data class PageBodyDto(
    val storage: BodyRepresentationDto? = null,
)

@Serializable
private data class BodyRepresentationDto(
    val value: String,
    val representation: String,
)
