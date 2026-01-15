package com.jervis.junie.service

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

class JunieService {
    suspend fun executeJunie(request: CodingExecuteRequest): CodingExecuteResponse =
        withContext(Dispatchers.IO) {
            val jobId = UUID.randomUUID().toString()

            logger.info { "JUNIE_JOB_START: jobId=$jobId, cid=${request.correlationId}" }

            // Execute Junie job
            runCatching {
                executeJunieJob(jobId, request)
            }.fold(
                onSuccess = { it },
                onFailure = { e ->
                    logger.error(e) { "JUNIE_EXECUTION_ERROR: jobId=$jobId" }
                    CodingExecuteResponse(
                        success = false,
                        summary = "Execution failed: ${e.message ?: "Unknown error"}",
                    )
                },
            )
        }

    private fun executeJunieJob(
        jobId: String,
        req: CodingExecuteRequest,
    ): CodingExecuteResponse {
        logger.info { "JUNIE_EXECUTE: jobId=$jobId, taskDescription=${req.taskDescription.take(50)}..." }

        // Build Junie command (headless mode as per instructions)
        // junie --auth="$JUNIE_API_KEY" "Prompt"
        val command =
            buildList {
                add("junie")
                // Auth is handled via environment variable JUNIE_API_KEY
                add(req.taskDescription)
            }

        logger.info { "JUNIE_COMMAND: ${command.joinToString(" ")}" }

        // Execute Junie process
        val result = executeProcess(command)

        // Build response based on exit code
        return when (result.exitCode) {
            null -> {
                logger.error { "JUNIE_TIMEOUT: jobId=$jobId" }
                CodingExecuteResponse(
                    success = false,
                    summary = "Process timed out. Check logs for details.",
                )
            }

            0 -> {
                logger.info { "JUNIE_SUCCESS: jobId=$jobId" }
                CodingExecuteResponse(
                    success = true,
                    summary = "Junie completed successfully.",
                )
            }

            else -> {
                logger.error { "JUNIE_FAILED: jobId=$jobId, exitCode=${result.exitCode}" }
                val errorSummary = extractErrorSummary(result.output)
                CodingExecuteResponse(
                    success = false,
                    summary = "Junie failed with exit code ${result.exitCode}. $errorSummary",
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
                    // Ensure the process runs in the data root directory if provided
                    val dataRootDir = System.getenv("DATA_ROOT_DIR")
                    if (!dataRootDir.isNullOrBlank()) {
                        directory(java.io.File(dataRootDir))
                    }
                }.start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()

        return ProcessResult(output = output, exitCode = process.exitValue())
    }

    /**
     * Extract a concise error summary from Junie output.
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
