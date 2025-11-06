package com.jervis.service.mcp.util

/**
 * Utility class for building standardized MCP tool responses.
 *
 * Ensures a consistent format across all tools with:
 * 1. Clear first-line summary for planning context (max 150 chars)
 * 2. Structured content for step context usage
 * 3. No verbose decorative separators
 * 4. AI-optimized format for context insertion
 */
object ToolResponseBuilder {
    /**
     * Creates a standardized tool response with summary and structured content.
     *
     * @param toolName Name of the tool (e.g., "TIKA", "JOERN_CALLGRAPH")
     * @param summary Concise summary of what was achieved (max 150 chars)
     * @param content Main structured content
     * @param sections Optional additional sections separated by "---"
     * @return Formatted response string
     */
    fun buildResponse(
        toolName: String,
        summary: String,
        content: String,
        vararg sections: String,
    ): String =
        buildString {
            // First line: TOOL_NAME_RESULT: summary
            val truncatedSummary = summary.take(150)
            appendLine("${toolName}_RESULT: $truncatedSummary")

            // Add blank line after summary
            if (content.isNotBlank() || sections.isNotEmpty()) {
                appendLine()
            }

            // Main content
            if (content.isNotBlank()) {
                append(content.trimEnd())
            }

            // Additional sections
            sections.forEach { section ->
                if (section.isNotBlank()) {
                    if (content.isNotBlank() || length > "${toolName}_RESULT: $truncatedSummary\n\n".length) {
                        appendLine()
                        appendLine("---")
                    }
                    append(section.trimEnd())
                }
            }
        }

    /**
     * Creates a simple success response with just summary and content.
     */
    fun success(
        toolName: String,
        summary: String,
        content: String = "",
    ): String = buildResponse(toolName, summary, content)

    /**
     * Creates a response for analysis results with counts/statistics.
     */
    fun analysisResult(
        toolName: String,
        count: Int,
        unit: String,
        details: String = "",
        results: String = "",
    ): String {
        val text = "Analyzed $count $unit"
        val content =
            buildString {
                if (details.isNotBlank()) {
                    appendLine(details)
                    if (results.isNotBlank()) {
                        appendLine()
                    }
                }
                if (results.isNotBlank()) {
                    append(results)
                }
            }
        return buildResponse(toolName, text, content)
    }

    /**
     * Creates a tree/listing response.
     */
    fun listingResult(
        toolName: String,
        itemType: String,
        rootInfo: String = "",
        listing: String,
    ): String {
        val text = "Listed $itemType"
        val content =
            buildString {
                if (rootInfo.isNotBlank()) {
                    appendLine(rootInfo)
                    appendLine()
                }
                append(listing)
            }
        return buildResponse(toolName, text, content)
    }
}
