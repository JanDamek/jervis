package com.jervis.aider.service

import com.jervis.aider.configuration.AiderProperties
import com.jervis.common.dto.CodingExecuteRequest
import com.jervis.common.dto.CodingExecuteResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path

private val logger = KotlinLogging.logger {}

private data class ProcessResult(
    val output: String,
    val exitCode: Int?,
)

@Service
class AiderService(
    private val aiderProperties: AiderProperties,
) {
    private val dataRootDir: String get() = aiderProperties.dataRootDir

    suspend fun executeAider(request: CodingExecuteRequest): CodingExecuteResponse =
        withContext(Dispatchers.IO) {
            val jobId = UUID.randomUUID().toString()
            val projectDir = resolveAndValidateProjectDir(request.clientId, request.projectId)

            projectDir.fold(
                onSuccess = { dir ->
                    runCatching { executeAiderJob(jobId, request, dir) }
                        .getOrElse { e ->
                            logger.error(e) { "Aider execution failed for job $jobId" }
                            CodingExecuteResponse(
                                success = false,
                                summary = "Execution failed",
                                details = e.message
                            )
                        }
                },
                onFailure = { e ->
                    logger.error { "Project directory validation failed: ${e.message}" }
                    CodingExecuteResponse(
                        success = false,
                        summary = "Validation failed",
                        details = e.message
                    )
                },
            )
        }

    private fun resolveAndValidateProjectDir(
        clientId: String,
        projectId: String?,
    ): Result<File> =
        runCatching {
            val rootDir = File(dataRootDir).canonicalFile
            val projectPath =
                when (projectId) {
                    null -> Path(rootDir.path, clientId).toString()
                    else -> Path(rootDir.path, clientId, projectId).toString()
                }

            val projectDir = File(projectPath).canonicalFile

            require(projectDir.absolutePath.startsWith(rootDir.absolutePath)) {
                "Project path is outside of allowed data root directory"
            }

            require(projectDir.exists()) {
                "Project directory does not exist: ${projectDir.absolutePath}"
            }

            projectDir
        }

    private fun executeAiderJob(
        jobId: String,
        req: CodingExecuteRequest,
        projectDir: File,
    ): CodingExecuteResponse {
        logger.info { "Starting Aider job $jobId in ${projectDir.name}" }

        ensureGitInitialized(projectDir)

        val command =
            buildList {
                add("aider")
                add("--yes")
                add("--message")
                add(req.taskDescription)
                addAll(req.targetFiles)
            }

        logger.info { "Executing command: ${command.joinToString(" ")}" }

        val result = executeProcess(command, projectDir, timeoutMinutes = 30)

        return when (result.exitCode) {
            null -> {
                logger.error { "Aider process timed out for job $jobId" }
                CodingExecuteResponse(
                    success = false,
                    summary = "Process timed out after 30 minutes",
                    details = result.output
                )
            }

            0 -> {
                logger.info { "Aider job $jobId completed successfully" }
                CodingExecuteResponse(
                    success = true,
                    summary = "COMPLETED",
                    details = result.output
                )
            }

            else -> {
                logger.error { "Aider failed (code ${result.exitCode}): ${result.output}" }
                CodingExecuteResponse(
                    success = false,
                    summary = "Exit code ${result.exitCode}",
                    details = result.output
                )
            }
        }
    }

    private fun executeProcess(
        command: List<String>,
        workingDir: File,
        timeoutMinutes: Long,
    ): ProcessResult {
        val process =
            ProcessBuilder(command)
                .apply {
                    directory(workingDir)
                    redirectErrorStream(true)
                    environment().putAll(System.getenv())
                }.start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)

        return if (completed) {
            ProcessResult(output = output, exitCode = process.exitValue())
        } else {
            process.destroyForcibly()
            ProcessResult(output = output, exitCode = null)
        }
    }

    private fun ensureGitInitialized(projectDir: File) {
        if (File(projectDir, ".git").exists()) return

        logger.info { "Initializing git repository in ${projectDir.absolutePath}" }

        listOf(
            listOf("git", "init"),
            listOf("git", "add", "."),
            listOf("git", "commit", "-m", "Initial commit"),
        ).forEach { command ->
            executeGitCommand(projectDir, command)
        }

        logger.info { "Git repository initialized successfully" }
    }

    private fun executeGitCommand(
        workingDir: File,
        command: List<String>,
    ) {
        val result = executeProcess(command, workingDir, timeoutMinutes = 1)
        check(result.exitCode == 0) { "Git command failed: ${command.joinToString(" ")}\nOutput: ${result.output}" }
    }
}
