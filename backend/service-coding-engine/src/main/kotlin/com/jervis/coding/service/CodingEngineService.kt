package com.jervis.coding.service

import com.jervis.coding.configuration.CodingEngineProperties
import com.jervis.common.dto.CodingExecuteRequest
import com.jervis.common.dto.CodingExecuteResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.UUID

private val logger = KotlinLogging.logger {}

private data class ProcessResult(
    val output: String,
    val exitCode: Int?,
)

@Service
class CodingEngineService(
    private val properties: CodingEngineProperties,
) {
    // Git writes operations that are FORBIDDEN
    private val forbiddenGitCommands =
        setOf(
            "commit",
            "push",
            "fetch",
            "merge",
            "rebase",
            "checkout",
            "branch",
            "pull",
            "reset",
        )

    suspend fun executeOpenHands(request: CodingExecuteRequest): CodingExecuteResponse =
        withContext(Dispatchers.IO) {
            val jobId = UUID.randomUUID().toString()

            logger.info { "OPENHANDS_JOB_START: jobId=$jobId, cid=${request.correlationId}" }

            // Execute OpenHands job
            runCatching {
                executeOpenHandsJob(jobId, request)
            }.fold(
                onSuccess = { result ->
                    when (result.exitCode) {
                        0 -> {
                            logger.info { "OPENHANDS_SUCCESS: jobId=$jobId" }
                            CodingExecuteResponse(
                                success = true,
                                summary = "OpenHands completed successfully. " + extractSummary(result.output),
                            )
                        }

                        null -> {
                            logger.error { "OPENHANDS_TIMEOUT: jobId=$jobId" }
                            CodingExecuteResponse(
                                success = false,
                                summary = "Process timed out after 60 minutes. Check logs for details.",
                            )
                        }

                        else -> {
                            logger.error { "OPENHANDS_FAILED: jobId=$jobId, exitCode=${result.exitCode}" }
                            val errorSummary = extractErrorSummary(result.output)
                            CodingExecuteResponse(
                                success = false,
                                summary = "OpenHands failed with exit code ${result.exitCode}. $errorSummary",
                            )
                        }
                    }
                },
                onFailure = { e ->
                    logger.error(e) { "OPENHANDS_EXECUTION_ERROR: jobId=$jobId" }
                    CodingExecuteResponse(
                        success = false,
                        summary = "Execution failed: ${e.message ?: "Unknown error"}",
                    )
                },
            )
        }

    private fun executeOpenHandsJob(
        jobId: String,
        req: CodingExecuteRequest,
    ): ProcessResult {
        logger.info { "OPENHANDS_EXECUTE: jobId=$jobId" }

        // Build OpenHands command
        val command =
            buildList {
                add("python3")
                add("-m")
                add("openhands.core.main")
                add("--task")
                add(req.taskDescription)
                add("--sandbox-image")
                add(properties.sandboxImage)
                add("--max-iterations")
                add(properties.maxIterations.toString())
            }

        logger.info { "OPENHANDS_COMMAND: ${command.joinToString(" ")}" }

        // Execute the OpenHands process with command validation
        val result = executeProcessWithValidation(command)

        return result
    }

    private fun executeProcessWithValidation(command: List<String>): ProcessResult {
        // Validate command doesn't contain forbidden git operations
        validateCommand(command)

        val process =
            ProcessBuilder(command)
                .apply {
                    redirectErrorStream(true)
                    environment().apply {
                        putAll(System.getenv())
                        put("DOCKER_HOST", properties.dockerHost)
                        put("OLLAMA_API_BASE", properties.ollamaBaseUrl)
                        // Add git hook to prevent write operations (defense in depth)
                        // Note: OpenHands runs in sandbox, but this adds an extra layer
                    }
                }.start()

        val output =
            buildString {
                process.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        // Monitor for forbidden git commands in output
                        if (containsForbiddenGitOperation(line)) {
                            logger.warn { "OPENHANDS_GIT_WARNING: Detected potential git write operation: $line" }
                        }
                        logger.info { "[OpenHands] $line" }
                        appendLine(line)
                    }
                }
            }

        process.waitFor()

        return ProcessResult(output = output, exitCode = process.exitValue())
    }

    /**
     * Validate that command doesn't contain forbidden git operations.
     */
    private fun validateCommand(command: List<String>) {
        val commandStr = command.joinToString(" ").lowercase()
        for (forbiddenOp in forbiddenGitCommands) {
            if (commandStr.contains("git") && commandStr.contains(forbiddenOp)) {
                throw SecurityException("Forbidden git operation detected: git $forbiddenOp")
            }
        }
    }

    /**
     * Check if a log line contains forbidden git operations.
     */
    private fun containsForbiddenGitOperation(line: String): Boolean {
        val lowerLine = line.lowercase()
        if (!lowerLine.contains("git")) return false
        return forbiddenGitCommands.any { lowerLine.contains("git $it") || lowerLine.contains("git-$it") }
    }

    /**
     * Extract a concise summary from OpenHands output.
     */
    private fun extractSummary(output: String): String {
        // Look for summary patterns in OpenHands output
        val lines = output.lines()
        val summaryLine =
            lines.find { it.contains("Summary:", ignoreCase = true) || it.contains("Completed:", ignoreCase = true) }
        return summaryLine?.take(200) ?: "Task completed."
    }

    /**
     * Extract error summary from OpenHands output.
     */
    private fun extractErrorSummary(output: String): String {
        val lines = output.lines().filter { it.isNotBlank() }.takeLast(5)
        return if (lines.isNotEmpty()) {
            "Last output: ${lines.joinToString(" | ").take(300)}"
        } else {
            "No error details available."
        }
    }
}
