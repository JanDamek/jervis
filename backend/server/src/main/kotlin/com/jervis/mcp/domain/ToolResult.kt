package com.jervis.mcp.domain

import com.jervis.domain.plan.PlanStep
import com.jervis.mcp.util.ToolResponseBuilder
import org.bson.types.ObjectId

sealed interface ToolResult {
    val output: String

    data class Ok(
        override val output: String,
    ) : ToolResult

    data class Error(
        override val output: String,
        val errorMessage: String? = null,
    ) : ToolResult

    data class Ask(
        override val output: String,
    ) : ToolResult

    data class Stop(
        override val output: String,
        val reason: String,
    ) : ToolResult

    data class InsertStep(
        override val output: String,
        val stepToInsert: PlanStep,
        val insertBeforeStepId: ObjectId,
    ) : ToolResult

    companion object {
        fun ok(output: String): ToolResult = Ok(output)

        fun error(
            output: String,
            message: String? = null,
        ): ToolResult = Error(output, message)

        fun ask(output: String): ToolResult = Ask(output)

        fun stop(
            output: String,
            reason: String,
        ): ToolResult = Stop(output, reason)

        /**
         * Creates a successful result with standardized formatting.
         * @param toolName Name of the tool (e.g., "TIKA", "JOERN")
         * @param summary Concise summary of what was achieved (max 150 chars)
         * @param content Main structured content (optional)
         * @param additionalSections Optional additional sections
         */
        fun success(
            toolName: String,
            summary: String,
            content: String,
            vararg additionalSections: String,
        ): ToolResult =
            Ok(
                ToolResponseBuilder.buildResponse(toolName, summary, content, *additionalSections),
            )

        /**
         * Creates a result for analysis operations with counts/statistics.
         * @param toolName Name of the analysis tool
         * @param analysisType Type of analysis performed
         * @param count Number of items analyzed
         * @param unit Unit of the count (e.g., "methods", "files", "lines")
         * @param details Optional details about the analysis
         * @param results Analysis results content
         */
        fun analysisResult(
            toolName: String,
            count: Int,
            unit: String,
            details: String = "",
            results: String = "",
        ): ToolResult =
            Ok(
                ToolResponseBuilder.analysisResult(toolName, count, unit, details, results),
            )

        /**
         * Creates a result for listing operations.
         * @param toolName Name of the tool
         * @param itemType Type of items (e.g., "files", "directories", "tasks")
         * @param rootInfo Optional information about the root/context
         * @param listing The actual listing content
         */
        fun listingResult(
            toolName: String,
            itemType: String,
            rootInfo: String = "",
            listing: String,
        ): ToolResult =
            Ok(
                ToolResponseBuilder.listingResult(toolName, itemType, rootInfo, listing),
            )
    }
}
