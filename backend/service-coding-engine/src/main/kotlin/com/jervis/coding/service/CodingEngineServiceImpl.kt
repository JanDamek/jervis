package com.jervis.coding.service

import com.jervis.coding.configuration.CodingEngineProperties
import com.jervis.common.client.ICodingClient
import com.jervis.common.dto.CodingRequest
import com.jervis.common.dto.CodingResult
import com.jervis.common.dto.VerificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.File
import java.util.UUID

private val logger = KotlinLogging.logger {}

private data class ProcessResult(
    val output: String,
    val exitCode: Int?,
)

class CodingEngineServiceImpl(
    private val properties: CodingEngineProperties,
) : ICodingClient {
    // Git write operations that are FORBIDDEN
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

    override suspend fun execute(request: CodingRequest): CodingResult =
        withContext(Dispatchers.IO) {
            val jobId = UUID.randomUUID().toString()

            logger.info { "OPENHANDS_JOB_START: jobId=$jobId" }

            runCatching {
                executeOpenHandsJob(jobId, request)
            }.fold(
                onSuccess = { result ->
                    when (result.exitCode) {
                        0 -> {
                            logger.info { "OPENHANDS_SUCCESS: jobId=$jobId" }
                            val verifyCmd = request.verifyCommand
                            val verificationResult =
                                if (verifyCmd != null) {
                                    executeVerification(verifyCmd)
                                } else {
                                    null
                                }

                            CodingResult(
                                success = true,
                                summary = "OpenHands completed successfully. " + extractSummary(result.output),
                                log = result.output.takeLast(2000),
                                verificationResult = verificationResult,
                            )
                        }

                        null -> {
                            logger.error { "OPENHANDS_TIMEOUT: jobId=$jobId" }
                            CodingResult(
                                success = false,
                                summary = "Process timed out after 60 minutes. Check logs for details.",
                                log = result.output.takeLast(2000),
                                errorMessage = "Timeout",
                            )
                        }

                        else -> {
                            logger.error { "OPENHANDS_FAILED: jobId=$jobId, exitCode=${result.exitCode}" }
                            val errorSummary = extractErrorSummary(result.output)
                            CodingResult(
                                success = false,
                                summary = "OpenHands failed with exit code ${result.exitCode}. $errorSummary",
                                log = result.output.takeLast(2000),
                                errorMessage = "Exit code ${result.exitCode}",
                            )
                        }
                    }
                },
                onFailure = { e ->
                    logger.error(e) { "OPENHANDS_EXECUTION_ERROR: jobId=$jobId" }
                    CodingResult(
                        success = false,
                        summary = "Execution failed: ${e.message ?: "Unknown error"}",
                        errorMessage = e.message,
                    )
                },
            )
        }

    private fun executeOpenHandsJob(
        jobId: String,
        req: CodingRequest,
    ): ProcessResult {
        logger.info { "OPENHANDS_EXECUTE: jobId=$jobId" }

        // Build OpenHands command
        val command =
            buildList {
                add("python3")
                add("-m")
                add("openhands.core.main")
                add("--task")
                add(req.instructions)
                add("--sandbox-image")
                add(properties.sandboxImage)
                add("--max-iterations")
                add(req.maxIterations.toString())
            }

        logger.info { "OPENHANDS_COMMAND: ${command.joinToString(" ")}" }

        // Execute the OpenHands process with command validation
        val result = executeProcessWithValidation(command)

        return result
    }

    private fun executeProcessWithValidation(command: List<String>): ProcessResult {
        // Validate command doesn't contain forbidden git operations
        validateCommand(command)

        val dataRoot = System.getenv("DATA_ROOT_DIR") ?: "/opt/jervis/data"
        val process =
            ProcessBuilder(command)
                .apply {
                    directory(File(dataRoot))
                    redirectErrorStream(true)
                    environment().apply {
                        putAll(System.getenv())
                        put("DOCKER_HOST", properties.dockerHost)
                        put("OLLAMA_API_BASE", properties.ollamaBaseUrl)
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

    private fun executeVerification(verifyCommand: String): VerificationResult {
        logger.info { "OPENHANDS_VERIFY: $verifyCommand" }

        val dataRoot = System.getenv("DATA_ROOT_DIR") ?: "/opt/jervis/data"
        val process =
            ProcessBuilder("sh", "-c", verifyCommand)
                .apply {
                    directory(File(dataRoot))
                    redirectErrorStream(true)
                    environment().putAll(System.getenv())
                }.start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()

        return VerificationResult(
            passed = process.exitValue() == 0,
            output = output.takeLast(500),
            exitCode = process.exitValue(),
        )
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
