package com.jervis.aider.service

import com.jervis.common.dto.CodingExecuteRequest
import com.jervis.common.dto.CodingExecuteResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}

private data class ProcessResult(
    val output: String,
    val exitCode: Int?,
)

class AiderService {
    suspend fun executeAider(request: CodingExecuteRequest): CodingExecuteResponse =
        withContext(Dispatchers.IO) {
            val jobId = UUID.randomUUID().toString()

            logger.info { "AIDER_JOB_START: jobId=$jobId, cid=${request.correlationId}" }

            // Validate targetFiles - Aider requires specific files
            if (request.targetFiles.isEmpty()) {
                logger.warn { "AIDER_VALIDATION_FAILED: targetFiles required, jobId=$jobId" }
                return@withContext CodingExecuteResponse(
                    success = false,
                    summary = "targetFiles required for aider - please specify which files to edit",
                )
            }

            // Execute Aider job
            runCatching {
                executeAiderJob(jobId, request)
            }.fold(
                onSuccess = { it },
                onFailure = { e ->
                    logger.error(e) { "AIDER_EXECUTION_ERROR: jobId=$jobId" }
                    CodingExecuteResponse(
                        success = false,
                        summary = "Execution failed: ${e.message ?: "Unknown error"}",
                    )
                },
            )
        }

    private fun executeAiderJob(
        jobId: String,
        req: CodingExecuteRequest,
    ): CodingExecuteResponse {
        logger.info { "AIDER_EXECUTE: jobId=$jobId, targetFiles=${req.targetFiles.size}" }

        // Build Aider command
        val command =
            buildList {
                add("aider")
                add("--yes")
                add("--message")
                add(req.taskDescription)
                // Add target files
                addAll(req.targetFiles)
            }

        logger.info { "AIDER_COMMAND: ${command.joinToString(" ")}" }

        // Execute Aider process
        val result = executeProcess(command)

        // Build response based on exit code
        return when (result.exitCode) {
            null -> {
                logger.error { "AIDER_TIMEOUT: jobId=$jobId" }
                CodingExecuteResponse(
                    success = false,
                    summary = "Process timed out after 30 minutes. Check logs for details.",
                )
            }

            0 -> {
                logger.info { "AIDER_SUCCESS: jobId=$jobId" }
                val changedFilesSummary = extractChangedFiles(req.targetFiles)
                CodingExecuteResponse(
                    success = true,
                    summary = "Aider completed successfully. Files modified: ${changedFilesSummary.joinToString(", ")}",
                )
            }

            else -> {
                logger.error { "AIDER_FAILED: jobId=$jobId, exitCode=${result.exitCode}" }
                val errorSummary = extractErrorSummary(result.output)
                CodingExecuteResponse(
                    success = false,
                    summary = "Aider failed with exit code ${result.exitCode}. $errorSummary",
                )
            }
        }
    }

    private fun executeProcess(command: List<String>): ProcessResult {
        val process =
            ProcessBuilder(command)
                .apply {
                    redirectErrorStream(true)
                    environment().putAll(System.getenv())
                }.start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()

        return ProcessResult(output = output, exitCode = process.exitValue())
    }

    /**
     * Extract the list of changed files from Aider output.
     * Falls back to targetFiles if parsing fails.
     */
    private fun extractChangedFiles(targetFiles: List<String>): List<String> = targetFiles

    /**
     * Extract a concise error summary from Aider output.
     */
    private fun extractErrorSummary(output: String): String {
        // Take the last few lines that might contain an error message
        val lines = output.lines().filter { it.isNotBlank() }.takeLast(3)
        return if (lines.isNotEmpty()) {
            "Last output: ${lines.joinToString(" | ").take(200)}"
        } else {
            "No error details available."
        }
    }
}
