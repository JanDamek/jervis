package com.jervis.koog.tools.external

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.integration.wiki.WikiPage
import com.jervis.integration.wiki.WikiService
import com.jervis.integration.wiki.WikiSpace
import kotlinx.serialization.Serializable
import mu.KotlinLogging

/**
 * READ-ONLY wiki tools for context lookup.
 * Used by KoogQualifierAgent to enrich context when indexing mentions of wiki pages.
 * Supports Confluence, MediaWiki, Notion, etc.
 */
@LLMDescription("Read-only wiki operations for context lookup and enrichment (Confluence, MediaWiki, Notion)")
class WikiReadTools(
    private val task: TaskDocument,
    private val confluenceService: WikiService,
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
    ): WikiSearchResult =
        try {
            logger.info { "CONFLUENCE_SEARCH: query='$query', spaceKey=$spaceKey, maxResults=$maxResults" }
            val pages = confluenceService.searchPages(task.clientId, query, spaceKey, maxResults)
            WikiSearchResult(
                success = true,
                pages = pages,
                totalFound = pages.size,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to search Confluence pages" }
            WikiSearchResult(
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
    ): WikiPageResult =
        try {
            logger.info { "CONFLUENCE_GET_PAGE: pageId=$pageId" }
            val page = confluenceService.getPage(task.clientId, pageId)

            // Full content is returned without truncation to preserve context.
            // EvidencePack.MAX_CONTENT_LENGTH is used only as a hint for UI summary.

            WikiPageResult(
                success = true,
                page = page,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get Confluence page: $pageId" }
            WikiPageResult(
                success = false,
                page = null,
                error = e.message ?: "Unknown error",
            )
        }

    @Tool
    @LLMDescription("List all Confluence spaces for client. Use to discover available spaces.")
    suspend fun listSpaces(): WikiSpacesResult =
        try {
            logger.info { "CONFLUENCE_LIST_SPACES" }
            val spaces = confluenceService.listSpaces(task.clientId)
            WikiSpacesResult(
                success = true,
                spaces = spaces,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to list Confluence spaces" }
            WikiSpacesResult(
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
    ): WikiChildrenResult =
        try {
            logger.info { "CONFLUENCE_GET_CHILDREN: pageId=$pageId" }
            val children = confluenceService.getChildren(task.clientId, pageId)
            WikiChildrenResult(
                success = true,
                children = children,
                totalChildren = children.size,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get Confluence children: $pageId" }
            WikiChildrenResult(
                success = false,
                children = emptyList(),
                totalChildren = 0,
                error = e.message ?: "Unknown error",
            )
        }
}

@Serializable
data class WikiSearchResult(
    val success: Boolean,
    val pages: List<WikiPage>,
    val totalFound: Int,
    val error: String? = null,
)

@Serializable
data class WikiPageResult(
    val success: Boolean,
    val page: WikiPage?,
    val error: String? = null,
)

@Serializable
data class WikiSpacesResult(
    val success: Boolean,
    val spaces: List<WikiSpace>,
    val error: String? = null,
)

@Serializable
data class WikiChildrenResult(
    val success: Boolean,
    val children: List<WikiPage>,
    val totalChildren: Int,
    val error: String? = null,
)
