package com.jervis.claude.service

import com.jervis.common.client.ICodingClient
import com.jervis.common.dto.CodingRequest
import com.jervis.common.dto.CodingResult
import com.jervis.common.dto.VerificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

private data class ProcessResult(
    val output: String,
    val exitCode: Int?,
)

class ClaudeServiceImpl : ICodingClient {
    override suspend fun execute(request: CodingRequest): CodingResult =
        withContext(Dispatchers.IO) {
            val jobId = UUID.randomUUID().toString()

            logger.info { "CLAUDE_JOB_START: jobId=$jobId" }

            runCatching {
                executeClaudeJob(jobId, request)
            }.fold(
                onSuccess = { it },
                onFailure = { e ->
                    logger.error(e) { "CLAUDE_EXECUTION_ERROR: jobId=$jobId" }
                    CodingResult(
                        success = false,
                        summary = "Execution failed: ${e.message ?: "Unknown error"}",
                        errorMessage = e.message,
                    )
                },
            )
        }

    private fun executeClaudeJob(
        jobId: String,
        req: CodingRequest,
    ): CodingResult {
        logger.info { "CLAUDE_EXECUTE: jobId=$jobId, instructions=${req.instructions.take(50)}..." }

        // Auth priority: 1) OAuth credentials (Max/Pro account), 2) API key, 3) env var
        val oauthJson = req.oauthCredentialsJson?.takeIf { it.isNotBlank() }
        val apiKey = req.apiKey?.takeIf { it.isNotBlank() } ?: System.getenv("ANTHROPIC_API_KEY")

        if (oauthJson == null && apiKey.isNullOrBlank()) {
            logger.error { "CLAUDE_NO_AUTH: jobId=$jobId" }
            return CodingResult(
                success = false,
                summary = "No authentication configured. Set up your Max/Pro account credentials or API key in Settings > Coding Agenti.",
                errorMessage = "Missing authentication",
            )
        }

        // If OAuth credentials are provided, write them to ~/.claude/.credentials.json
        if (oauthJson != null) {
            writeOAuthCredentials(oauthJson)
            logger.info { "CLAUDE_OAUTH: jobId=$jobId, credentials written to ~/.claude/.credentials.json" }
        }

        // Build claude CLI command
        val command =
            buildList {
                add("claude")
                add("--print")
                add("--dangerously-skip-permissions")
                // Add files as context if specified
                if (req.files.isNotEmpty()) {
                    val filesContext = req.files.joinToString("\n") { "File: $it" }
                    add("$filesContext\n\n${req.instructions}")
                } else {
                    add(req.instructions)
                }
            }

        logger.info { "CLAUDE_COMMAND: claude --print --dangerously-skip-permissions <instructions>" }

        val dataRoot = System.getenv("DATA_ROOT_DIR") ?: "/opt/jervis/data"
        val maxIterations = req.maxIterations.coerceIn(1, 10)
        val timeoutMinutes = (maxIterations * 5).toLong().coerceAtMost(45)

        // Pass the API key via environment so the CLI picks it up (ignored if OAuth is used)
        val extraEnv = buildMap {
            if (apiKey != null) put("ANTHROPIC_API_KEY", apiKey)
        }
        val result = executeProcess(command, File(dataRoot), timeoutMinutes, extraEnv)

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
                logger.error { "CLAUDE_TIMEOUT: jobId=$jobId, timeout=${timeoutMinutes}m" }
                CodingResult(
                    success = false,
                    summary = "Process timed out after $timeoutMinutes minutes. Check logs for details.",
                    log = result.output.takeLast(2000),
                    errorMessage = "Timeout",
                )
            }

            0 -> {
                logger.info { "CLAUDE_SUCCESS: jobId=$jobId" }
                CodingResult(
                    success = true,
                    summary = "Claude completed successfully.",
                    log = result.output.takeLast(2000),
                    verificationResult = verificationResult,
                )
            }

            else -> {
                logger.error { "CLAUDE_FAILED: jobId=$jobId, exitCode=${result.exitCode}" }
                val errorSummary = extractErrorSummary(result.output)
                CodingResult(
                    success = false,
                    summary = "Claude failed with exit code ${result.exitCode}. $errorSummary",
                    log = result.output.takeLast(2000),
                    errorMessage = "Exit code ${result.exitCode}",
                )
            }
        }
    }

    private fun executeProcess(
        command: List<String>,
        workingDir: File,
        timeoutMinutes: Long = 30,
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

        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()

        // Read output in a streaming fashion to avoid blocking
        val readerThread = Thread {
            reader.forEachLine { line ->
                output.appendLine(line)
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        val completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)

        return if (completed) {
            readerThread.join(5000)
            ProcessResult(output = output.toString(), exitCode = process.exitValue())
        } else {
            process.destroyForcibly()
            readerThread.join(5000)
            ProcessResult(output = output.toString(), exitCode = null)
        }
    }

    private fun executeVerification(
        verifyCommand: String,
        workingDir: File,
    ): VerificationResult {
        logger.info { "CLAUDE_VERIFY: $verifyCommand" }

        val result = executeProcess(listOf("sh", "-c", verifyCommand), workingDir, timeoutMinutes = 10)

        return VerificationResult(
            passed = result.exitCode == 0,
            output = result.output.takeLast(500),
            exitCode = result.exitCode ?: -1,
        )
    }

    /**
     * Write OAuth credentials JSON to ~/.claude/.credentials.json so the CLI picks them up.
     * This is the same file format that `claude login` produces locally.
     */
    private fun writeOAuthCredentials(json: String) {
        val claudeDir = File(System.getProperty("user.home"), ".claude")
        claudeDir.mkdirs()
        val credFile = File(claudeDir, ".credentials.json")
        credFile.writeText(json)
        logger.info { "OAuth credentials written to ${credFile.absolutePath}" }
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
