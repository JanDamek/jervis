package com.jervis.koog.tools.external

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.service.confluence.ConfluencePage
import com.jervis.service.confluence.ConfluenceService
import com.jervis.service.confluence.ConfluenceSpace
import kotlinx.serialization.Serializable
import mu.KotlinLogging

/**
 * READ-ONLY Confluence tools for context lookup.
 * Used by KoogQualifierAgent to enrich context when indexing mentions of Confluence pages.
 */
@LLMDescription("Read-only Confluence operations for context lookup and enrichment")
class ConfluenceReadTools(
    private val task: TaskDocument,
    private val confluenceService: ConfluenceService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription("Search Confluence pages by CQL query. Use to find relevant pages by keywords, space, title, etc.")
    suspend fun searchPages(
        @LLMDescription("CQL query (e.g., 'type=page AND space=PROJ AND title~Architecture')")
        query: String,
        @LLMDescription("Space key to filter by (optional)")
        spaceKey: String? = null,
        @LLMDescription("Max results to return")
        maxResults: Int = 20,
    ): ConfluenceSearchResult =
        try {
            logger.info { "CONFLUENCE_SEARCH: query='$query', spaceKey=$spaceKey, maxResults=$maxResults" }
            val pages = confluenceService.searchPages(task.clientId, query, spaceKey, maxResults)
            ConfluenceSearchResult(
                success = true,
                pages = pages,
                totalFound = pages.size,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to search Confluence pages" }
            ConfluenceSearchResult(
                success = false,
                pages = emptyList(),
                totalFound = 0,
                error = e.message ?: "Unknown error",
            )
        }

    @Tool
    @LLMDescription("Get specific Confluence page by ID. Use to retrieve full content of mentioned page.")
    suspend fun getPage(
        @LLMDescription("Page ID")
        pageId: String,
    ): ConfluencePageResult =
        try {
            logger.info { "CONFLUENCE_GET_PAGE: pageId=$pageId" }
            val page = confluenceService.getPage(task.clientId, pageId)
            ConfluencePageResult(
                success = true,
                page = page,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get Confluence page: $pageId" }
            ConfluencePageResult(
                success = false,
                page = null,
                error = e.message ?: "Unknown error",
            )
        }

    @Tool
    @LLMDescription("List all Confluence spaces for client. Use to discover available spaces.")
    suspend fun listSpaces(): ConfluenceSpacesResult =
        try {
            logger.info { "CONFLUENCE_LIST_SPACES" }
            val spaces = confluenceService.listSpaces(task.clientId)
            ConfluenceSpacesResult(
                success = true,
                spaces = spaces,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list Confluence spaces" }
            ConfluenceSpacesResult(
                success = false,
                spaces = emptyList(),
                error = e.message ?: "Unknown error",
            )
        }

    @Tool
    @LLMDescription("Get child pages of specific page. Use to navigate page hierarchy.")
    suspend fun getChildren(
        @LLMDescription("Parent page ID")
        pageId: String,
    ): ConfluenceChildrenResult =
        try {
            logger.info { "CONFLUENCE_GET_CHILDREN: pageId=$pageId" }
            val children = confluenceService.getChildren(task.clientId, pageId)
            ConfluenceChildrenResult(
                success = true,
                children = children,
                totalChildren = children.size,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get Confluence children: $pageId" }
            ConfluenceChildrenResult(
                success = false,
                children = emptyList(),
                totalChildren = 0,
                error = e.message ?: "Unknown error",
            )
        }
}

@Serializable
data class ConfluenceSearchResult(
    val success: Boolean,
    val pages: List<ConfluencePage>,
    val totalFound: Int,
    val error: String? = null,
)

@Serializable
data class ConfluencePageResult(
    val success: Boolean,
    val page: ConfluencePage?,
    val error: String? = null,
)

@Serializable
data class ConfluenceSpacesResult(
    val success: Boolean,
    val spaces: List<ConfluenceSpace>,
    val error: String? = null,
)

@Serializable
data class ConfluenceChildrenResult(
    val success: Boolean,
    val children: List<ConfluencePage>,
    val totalChildren: Int,
    val error: String? = null,
)
