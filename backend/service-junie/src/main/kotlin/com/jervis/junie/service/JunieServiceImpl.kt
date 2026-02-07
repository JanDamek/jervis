package com.jervis.junie.service

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

class JunieServiceImpl : ICodingClient {
    override suspend fun execute(request: CodingRequest): CodingResult =
        withContext(Dispatchers.IO) {
            val jobId = UUID.randomUUID().toString()

            logger.info { "JUNIE_JOB_START: jobId=$jobId" }

            runCatching {
                executeJunieJob(jobId, request)
            }.fold(
                onSuccess = { it },
                onFailure = { e ->
                    logger.error(e) { "JUNIE_EXECUTION_ERROR: jobId=$jobId" }
                    CodingResult(
                        success = false,
                        summary = "Execution failed: ${e.message ?: "Unknown error"}",
                        errorMessage = e.message,
                    )
                },
            )
        }

    private fun executeJunieJob(
        jobId: String,
        req: CodingRequest,
    ): CodingResult {
        logger.info { "JUNIE_EXECUTE: jobId=$jobId, instructions=${req.instructions.take(50)}..." }

        // Use API key from request (server settings) with env var fallback
        val apiKey = req.apiKey?.takeIf { it.isNotBlank() } ?: System.getenv("JUNIE_API_KEY")
        if (apiKey.isNullOrBlank()) {
            logger.error { "JUNIE_NO_API_KEY: jobId=$jobId" }
            return CodingResult(
                success = false,
                summary = "JUNIE_API_KEY is not set. Configure it in Settings > Coding Agenti or set the environment variable.",
                errorMessage = "Missing API key",
            )
        }

        val command =
            buildList {
                add("junie")
                add(req.instructions)
            }

        logger.info { "JUNIE_COMMAND: ${command.joinToString(" ")}" }

        val dataRoot = System.getenv("DATA_ROOT_DIR") ?: "/opt/jervis/data"
        val extraEnv = mapOf("JUNIE_API_KEY" to apiKey)
        val result = executeProcess(command, File(dataRoot), extraEnv)

        // Execute verification if requested
        val verifyCmd = req.verifyCommand
        val verificationResult =
            if (verifyCmd != null && result.exitCode == 0) {
                executeVerification(verifyCmd, File(dataRoot))
            } else {
                null
            }

        return when (result.exitCode) {
            null -> {
                logger.error { "JUNIE_TIMEOUT: jobId=$jobId" }
                CodingResult(
                    success = false,
                    summary = "Process timed out. Check logs for details.",
                    log = result.output.takeLast(2000),
                    errorMessage = "Timeout",
                )
            }

            0 -> {
                logger.info { "JUNIE_SUCCESS: jobId=$jobId" }
                CodingResult(
                    success = true,
                    summary = "Junie completed successfully.",
                    log = result.output.takeLast(2000),
                    verificationResult = verificationResult,
                )
            }

            else -> {
                logger.error { "JUNIE_FAILED: jobId=$jobId, exitCode=${result.exitCode}" }
                val errorSummary = extractErrorSummary(result.output)
                CodingResult(
                    success = false,
                    summary = "Junie failed with exit code ${result.exitCode}. $errorSummary",
                    log = result.output.takeLast(2000),
                    errorMessage = "Exit code ${result.exitCode}",
                )
            }
        }
    }

    private fun executeProcess(
        command: List<String>,
        workingDir: File,
        extraEnv: Map<String, String> = emptyMap(),
    ): ProcessResult {
        val process =
            ProcessBuilder(command)
                .apply {
                    directory(workingDir)
                    redirectErrorStream(true)
                    environment().putAll(System.getenv())
                    environment().putAll(extraEnv)
                }.start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()

        return ProcessResult(output = output, exitCode = process.exitValue())
    }

    private fun executeVerification(
        verifyCommand: String,
        workingDir: File,
    ): VerificationResult {
        logger.info { "JUNIE_VERIFY: $verifyCommand" }

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
