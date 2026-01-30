package com.jervis.koog.tools.coding

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.common.client.CodingRequest
import com.jervis.common.client.ICodingClient
import com.jervis.common.rpc.withRpcRetry
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Coding tools for code generation and modification using various coding agents.
 *
 * These tools provide access to three coding agents:
 * - **Aider**: Fast, efficient for small localized changes (1-3 files)
 * - **OpenHands**: Powerful for complex multi-file refactoring and large changes
 * - **Junie**: Premium agent - very fast and capable, but expensive
 *
 * Use Aider for quick fixes, OpenHands for complex tasks, and Junie only when speed is critical.
 */
@Component
class CodingTools(
    @Qualifier("aiderClient") private val aiderClient: ICodingClient,
    @Qualifier("codingEngineClient") private val codingEngineClient: ICodingClient,
    @Qualifier("junieClient") private val junieClient: ICodingClient,
    private val reconnectHandler: com.jervis.configuration.RpcReconnectHandler,
) : ToolSet {
    private val logger = KotlinLogging.logger {}

    @Tool
    @LLMDescription(
        """Execute a coding task using Aider - a fast AI coding assistant.

        **When to use:**
        - Small, localized code changes (bug fixes, small features)
        - Modifications to 1-3 specific files
        - Quick iterations and fast turnaround needed

        **NOT suitable for:**
        - Large refactorings across many files
        - Complex architectural changes
        - Tasks requiring deep project understanding

        **Examples:**
        - "Fix the null pointer exception in UserService.kt line 42"
        - "Add input validation to the login form"
        - "Update the API endpoint to return 404 instead of 500"
        """
    )
    suspend fun executeAider(
        @LLMDescription("Clear, specific coding instructions") instructions: String,
        @LLMDescription("List of file paths to modify (1-3 files recommended)") files: List<String> = emptyList(),
        @LLMDescription("Optional command to verify the changes (e.g., './gradlew test')") verifyCommand: String? = null,
    ): String {
        logger.info { "Executing Aider coding task: ${instructions.take(100)}..." }

        return try {
            val request = CodingRequest(
                instructions = instructions,
                files = files,
                verifyCommand = verifyCommand,
                maxIterations = 3,
            )

            val result = withRpcRetry(
                name = "Aider",
                reconnect = { reconnectHandler.reconnectAider() }
            ) {
                aiderClient.execute(request)
            }

            if (result.success) {
                buildString {
                    appendLine("✅ **Aider Task Completed Successfully**")
                    appendLine()
                    appendLine("**Summary:**")
                    appendLine(result.summary)

                    val verification = result.verificationResult
                    if (verification != null) {
                        appendLine()
                        if (verification.passed) {
                            appendLine("✅ **Verification Passed**")
                        } else {
                            appendLine("❌ **Verification Failed** (exit code: ${verification.exitCode})")
                            appendLine("```")
                            appendLine(verification.output.takeLast(500))
                            appendLine("```")
                        }
                    }

                    if (result.log.isNotBlank()) {
                        appendLine()
                        appendLine("**Log:**")
                        appendLine("```")
                        appendLine(result.log.takeLast(1000))
                        appendLine("```")
                    }
                }
            } else {
                "❌ **Aider Task Failed**: ${result.errorMessage ?: "Unknown error"}\n\n**Log:**\n```\n${result.log.takeLast(1000)}\n```"
            }
        } catch (e: Exception) {
            logger.error(e) { "Aider execution failed" }
            "❌ **Aider Error**: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        """Execute a coding task using OpenHands - a powerful autonomous coding agent.

        **When to use:**
        - Complex, multi-file refactorings
        - Large feature implementations
        - Tasks requiring project-wide understanding
        - Architectural changes
        - When Aider has failed or is insufficient

        **Advantages:**
        - Can navigate and understand entire project structure
        - Handles complex dependencies between files
        - Can run tests and self-correct
        - More thorough than Aider

        **Trade-offs:**
        - Slower than Aider (minutes vs seconds)
        - Uses more resources

        **Examples:**
        - "Refactor the authentication module to use JWT instead of sessions"
        - "Implement a new REST API for user management with full CRUD operations"
        - "Add comprehensive error handling across all service classes"
        """
    )
    suspend fun executeOpenHands(
        @LLMDescription("Detailed coding instructions with context") instructions: String,
        @LLMDescription("Optional command to verify the changes") verifyCommand: String? = null,
    ): String {
        logger.info { "Executing OpenHands coding task: ${instructions.take(100)}..." }

        return try {
            val request = CodingRequest(
                instructions = instructions,
                files = emptyList(), // OpenHands explores the project itself
                verifyCommand = verifyCommand,
                maxIterations = 10, // OpenHands can iterate more
            )

            val result = withRpcRetry(
                name = "OpenHands",
                reconnect = { reconnectHandler.reconnectCodingEngine() }
            ) {
                codingEngineClient.execute(request)
            }

            if (result.success) {
                buildString {
                    appendLine("✅ **OpenHands Task Completed Successfully**")
                    appendLine()
                    appendLine("**Summary:**")
                    appendLine(result.summary)

                    val verification = result.verificationResult
                    if (verification != null) {
                        appendLine()
                        if (verification.passed) {
                            appendLine("✅ **Verification Passed**")
                        } else {
                            appendLine("❌ **Verification Failed** (exit code: ${verification.exitCode})")
                            appendLine("```")
                            appendLine(verification.output.takeLast(500))
                            appendLine("```")
                        }
                    }

                    if (result.log.isNotBlank()) {
                        appendLine()
                        appendLine("**Log:**")
                        appendLine("```")
                        appendLine(result.log.takeLast(2000))
                        appendLine("```")
                    }
                }
            } else {
                "❌ **OpenHands Task Failed**: ${result.errorMessage ?: "Unknown error"}\n\n**Log:**\n```\n${result.log.takeLast(2000)}\n```"
            }
        } catch (e: Exception) {
            logger.error(e) { "OpenHands execution failed" }
            "❌ **OpenHands Error**: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        """Execute a coding task using Junie - a premium, ultra-fast coding agent.

        **When to use:**
        - Time-critical situations where speed is essential
        - After OpenHands has failed twice
        - Production incidents requiring immediate fixes
        - Complex tasks that need both speed AND quality

        **Advantages:**
        - Fastest coding agent available
        - Very high quality output
        - Best for complex problems

        **Trade-offs:**
        - **EXPENSIVE** - use sparingly!
        - Should be last resort, not first choice

        **Decision logic:**
        1. Try Aider first for small changes
        2. Try OpenHands for complex tasks
        3. Try OpenHands again if it fails once
        4. Use Junie only if OpenHands failed twice OR time is critical

        **Examples:**
        - "Production is down - fix the database connection pooling issue immediately"
        - "OpenHands failed twice - implement the payment gateway integration"
        - "Critical security vulnerability - patch the SQL injection in AuthController"
        """
    )
    suspend fun executeJunie(
        @LLMDescription("Detailed coding instructions") instructions: String,
        @LLMDescription("Optional command to verify the changes") verifyCommand: String? = null,
    ): String {
        logger.warn { "Using EXPENSIVE Junie agent for: ${instructions.take(100)}..." }

        return try {
            val request = CodingRequest(
                instructions = instructions,
                files = emptyList(), // Junie explores the project itself
                verifyCommand = verifyCommand,
                maxIterations = 5,
            )

            val result = withRpcRetry(
                name = "Junie",
                reconnect = { reconnectHandler.reconnectJunie() }
            ) {
                junieClient.execute(request)
            }

            if (result.success) {
                buildString {
                    appendLine("✅ **Junie Task Completed Successfully** 💎")
                    appendLine()
                    appendLine("**Summary:**")
                    appendLine(result.summary)

                    val verification = result.verificationResult
                    if (verification != null) {
                        appendLine()
                        if (verification.passed) {
                            appendLine("✅ **Verification Passed**")
                        } else {
                            appendLine("❌ **Verification Failed** (exit code: ${verification.exitCode})")
                            appendLine("```")
                            appendLine(verification.output.takeLast(500))
                            appendLine("```")
                        }
                    }

                    if (result.log.isNotBlank()) {
                        appendLine()
                        appendLine("**Log:**")
                        appendLine("```")
                        appendLine(result.log.takeLast(2000))
                        appendLine("```")
                    }
                }
            } else {
                "❌ **Junie Task Failed**: ${result.errorMessage ?: "Unknown error"}\n\n**Log:**\n```\n${result.log.takeLast(2000)}\n```"
            }
        } catch (e: Exception) {
            logger.error(e) { "Junie execution failed" }
            "❌ **Junie Error**: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        """Execute a coding task using the most appropriate agent based on task complexity and policy.
        
        **When to use:**
        - This is the preferred generic method for all coding tasks.
        - Use when you want the system to automatically select between Aider, OpenHands, or Junie.
        
        **System logic:**
        - Small tasks (1-3 files) -> Aider
        - Complex tasks / Refactorings -> OpenHands
        - Critical / Repeated failure -> Junie
        """
    )
    suspend fun execute(
        @LLMDescription("Clear, specific coding instructions") instructions: String,
        @LLMDescription("Optional list of file paths to modify (if known)") files: List<String> = emptyList(),
        @LLMDescription("Optional command to verify the changes") verifyCommand: String? = null,
        @LLMDescription("Strategy hint: 'FAST' (Aider), 'THOROUGH' (OpenHands), 'PREMIUM' (Junie), 'AUTO' (System decides)") strategy: String = "AUTO"
    ): String {
        return when(strategy.uppercase()) {
            "FAST" -> executeAider(instructions, files, verifyCommand)
            "THOROUGH" -> executeOpenHands(instructions, verifyCommand)
            "PREMIUM" -> executeJunie(instructions, verifyCommand)
            else -> {
                // Heuristic: if many files or no files specified, use OpenHands, otherwise Aider
                if (files.isEmpty() || files.size > 3) {
                    executeOpenHands(instructions, verifyCommand)
                } else {
                    executeAider(instructions, files, verifyCommand)
                }
            }
        }
    }
}
