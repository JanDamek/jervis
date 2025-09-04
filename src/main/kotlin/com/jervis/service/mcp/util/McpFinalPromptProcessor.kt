package com.jervis.service.mcp.util

import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.domain.ToolResult
import org.springframework.stereotype.Service

/**
 * Service for processing final prompts through LLM in MCP tools.
 * Provides shared functionality for processing FinalPrompt across different tools.
 * Follows SOLID principles by having single responsibility for prompt processing.
 */
@Service
class McpFinalPromptProcessor(
    private val llmGateway: LlmGateway,
) {
    /**
     * Processes the final prompt through LLM if provided, otherwise returns the original result.
     *
     * @param finalPrompt The optional final prompt to process through LLM
     * @param systemPrompt The system prompt template for the LLM
     * @param originalResult The original tool result to process or return
     * @param modelType The model type to use (defaults to INTERNAL)
     * @return The processed result or original result if no final prompt provided
     */
    suspend fun processFinalPrompt(
        finalPrompt: String?,
        systemPrompt: String,
        originalResult: ToolResult,
        modelType: ModelType = ModelType.INTERNAL,
        context: TaskContext,
    ): ToolResult =
        if (finalPrompt != null && originalResult is ToolResult.Ok) {
            try {
                val userPrompt =
                    """
                    $finalPrompt
                    
                    Tool Results:
                    ${originalResult.output}
                    """.trimIndent()

                val llmResponse =
                    llmGateway.callLlm(
                        type = modelType,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        outputLanguage = "en",
                        quick = context.quick,
                    )

                ToolResult.ok(llmResponse.answer)
            } catch (e: Exception) {
                ToolResult.ok("LLM processing failed: ${e.message}\n\nOriginal Results:\n${originalResult.output}")
            }
        } else {
            originalResult
        }

    /**
     * Creates a system prompt template for terminal command analysis.
     */
    fun createTerminalSystemPrompt(): String =
        """
        You are a terminal command expert. Your task is to analyze terminal command results and provide actionable insights.
        
        CRITICAL REQUIREMENT: You must NEVER invent or fabricate any information. All information you provide must come from available tools (McpTools) or the actual command output provided to you. If you don't have access to specific information through tools, explicitly state that you cannot provide it rather than guessing or inventing details.
        
        You must always include:
        1. Summary of what the command accomplished
        2. Key findings or important information from the output
        3. Potential next steps or recommendations
        4. Any warnings or issues that should be addressed
        
        Be precise and actionable in your response. Focus on what the user needs to know for their next step.
        """.trimIndent()

    /**
     * Creates a system prompt template for Joern code analysis.
     */
    fun createJoernSystemPrompt(): String =
        """
        You are a code analysis expert familiar with Joern and static analysis results.
        Your task is to analyze Joern query results and provide actionable insights.
        
        CRITICAL REQUIREMENT: You must NEVER invent or fabricate any information. All information you provide must come from available tools (McpTools) or the actual Joern analysis results provided to you. If you don't have access to specific information through tools, explicitly state that you cannot provide it rather than guessing or inventing details.
        
        You must always include:
        1. Summary of findings from the Joern analysis
        2. Security implications or code quality issues identified
        3. Specific recommendations for addressing any issues
        4. Suggestions for additional analysis or next steps
        5. Code locations or patterns that need attention
        
        Be precise and actionable in your response. Focus on practical insights that can improve code quality or security.
        """.trimIndent()

    /**
     * Creates a system prompt template for RAG query analysis.
     */
    fun createRagSystemPrompt(): String =
        """
        You are an expert code analyst. Your task is to process RAG search results and provide actionable insights.
        
        CRITICAL REQUIREMENT: You must NEVER invent or fabricate any information. All information you provide must come from available tools (McpTools) or the actual RAG search results provided to you. If you don't have access to specific information through tools, explicitly state that you cannot provide it rather than guessing or inventing details.
        
        UNDERSTANDING RAG SEARCH RESULTS:
        The search results come from a vector database containing code and documentation with the following metadata fields:
        - projectId: Project identifier (always filtered for security)
        - documentType: Type of document (UNKNOWN, CODE, TEXT, MEETING, NOTE, GIT_HISTORY, DEPENDENCY, DEPENDENCY_DESCRIPTION, CLASS_SUMMARY, ACTION, DECISION, PLAN, JOERN_ANALYSIS)
        - ragSourceType: Source type (LLM, FILE, GIT, ANALYSIS, CLASS, AGENT)  
        - source: Source file path or identifier
        - language: Programming language (e.g., kotlin, java, python)
        - module: Module or component name
        - path: File path
        - pageContent: The actual content that was searched
        - timestamp: When the document was indexed
        - isDefaultBranch: Whether from default branch (true/false)
        - inspirationOnly: Whether for inspiration only (true/false)
        - createdAt: Creation timestamp
        
        The results may be filtered by any of these fields to provide targeted search results.
        
        You must always include:
        1. Clear specifications about where relevant information is located (file paths, line numbers, functions)
        2. Code snippets that are directly relevant to the query
        3. Specific next steps or recommendations
        4. Any patterns or relationships found in the results
        5. Analysis of which document types and sources provided the most relevant information
        
        Be precise and actionable in your response. Focus on what the user needs to know for their next step.
        """.trimIndent()
}
