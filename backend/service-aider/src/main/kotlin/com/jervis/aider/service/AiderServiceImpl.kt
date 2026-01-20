package com.jervis.aider.service

import com.jervis.common.client.CodingRequest
import com.jervis.common.client.CodingResult
import com.jervis.common.client.ICodingClient
import com.jervis.common.client.VerificationResult
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

class AiderServiceImpl : ICodingClient {
    override suspend fun execute(request: CodingRequest): CodingResult =
        withContext(Dispatchers.IO) {
            val jobId = UUID.randomUUID().toString()

            logger.info { "AIDER_JOB_START: jobId=$jobId" }

            // Validate files - Aider requires specific files
            if (request.files.isEmpty()) {
                logger.warn { "AIDER_VALIDATION_FAILED: files required, jobId=$jobId" }
                return@withContext CodingResult(
                    success = false,
                    summary = "files required for aider - please specify which files to edit",
                    errorMessage = "No files specified",
                )
            }

            // Execute Aider job
            runCatching {
                executeAiderJob(jobId, request)
            }.fold(
                onSuccess = { it },
                onFailure = { e ->
                    logger.error(e) { "AIDER_EXECUTION_ERROR: jobId=$jobId" }
                    CodingResult(
                        success = false,
                        summary = "Execution failed: ${e.message ?: "Unknown error"}",
                        errorMessage = e.message,
                    )
                },
            )
        }

    private fun executeAiderJob(
        jobId: String,
        req: CodingRequest,
    ): CodingResult {
        logger.info { "AIDER_EXECUTE: jobId=$jobId, files=${req.files.size}" }

        // Build Aider command
        val command =
            buildList {
                add("aider")
                add("--yes")
                add("--message")
                add(req.instructions)
                // Add target files
                addAll(req.files)
            }

        logger.info { "AIDER_COMMAND: ${command.joinToString(" ")}" }

        // Execute Aider process
        val dataRoot = System.getenv("DATA_ROOT_DIR") ?: "/opt/jervis/data"
        val result = executeProcess(command, File(dataRoot))

        // Execute verification if requested
        val verifyCmd = req.verifyCommand
        val verificationResult =
            if (verifyCmd != null && result.exitCode == 0) {
                executeVerification(verifyCmd, File(dataRoot))
            } else {
                null
            }

        // Build response based on exit code
        return when (result.exitCode) {
            null -> {
                logger.error { "AIDER_TIMEOUT: jobId=$jobId" }
                CodingResult(
                    success = false,
                    summary = "Process timed out after 30 minutes. Check logs for details.",
                    log = result.output.takeLast(1000),
                    errorMessage = "Timeout",
                )
            }

            0 -> {
                logger.info { "AIDER_SUCCESS: jobId=$jobId" }
                val changedFilesSummary = req.files.joinToString(", ")
                CodingResult(
                    success = true,
                    summary = "Aider completed successfully. Files modified: $changedFilesSummary",
                    log = result.output.takeLast(1000),
                    verificationResult = verificationResult,
                )
            }

            else -> {
                logger.error { "AIDER_FAILED: jobId=$jobId, exitCode=${result.exitCode}" }
                val errorSummary = extractErrorSummary(result.output)
                CodingResult(
                    success = false,
                    summary = "Aider failed with exit code ${result.exitCode}. $errorSummary",
                    log = result.output.takeLast(1000),
                    errorMessage = "Exit code ${result.exitCode}",
                )
            }
        }
    }

    private fun executeProcess(
        command: List<String>,
        workingDir: File,
    ): ProcessResult {
        val process =
            ProcessBuilder(command)
                .apply {
                    directory(workingDir)
                    redirectErrorStream(true)
                    environment().putAll(System.getenv())
                }.start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()

        return ProcessResult(output = output, exitCode = process.exitValue())
    }

    private fun executeVerification(
        verifyCommand: String,
        workingDir: File,
    ): VerificationResult {
        logger.info { "AIDER_VERIFY: $verifyCommand" }

        val result = executeProcess(listOf("sh", "-c", verifyCommand), workingDir)

        return VerificationResult(
            passed = result.exitCode == 0,
            output = result.output.takeLast(500),
            exitCode = result.exitCode ?: -1,
        )
    }

    private fun extractErrorSummary(output: String): String {
        val lines = output.lines().filter { it.isNotBlank() }.takeLast(3)
        return if (lines.isNotEmpty()) {
            "Last output: ${lines.joinToString(" | ").take(200)}"
        } else {
            "No error details available."
        }
    }
}
