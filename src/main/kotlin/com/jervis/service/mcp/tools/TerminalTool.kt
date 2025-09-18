package com.jervis.service.mcp.tools

import com.jervis.configuration.TimeoutsProperties
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@Service
class TerminalTool(
    private val llmGateway: LlmGateway,
    private val timeoutsProperties: TimeoutsProperties,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name = PromptTypeEnum.TERMINAL

    @Serializable
    data class TerminalParams(
        val command: String = "",
        val timeout: Int? = null,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): TerminalParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.TERMINAL,
                userPrompt = taskDescription,
                quick = context.quick,
                responseSchema = TerminalParams(),
                stepContext = stepContext,
            )

        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription, context, stepContext)

        val validationResult = validateCommand(parsed)
        validationResult?.let { return it }

        val executionResult = executeCommand(parsed, context)

        return executionResult
    }

    private fun validateCommand(params: TerminalParams): ToolResult? =
        when {
            params.command.isBlank() -> ToolResult.error("Command cannot be empty")
            isDangerousCommand(params.command) -> ToolResult.error("Command contains dangerous operations and is not allowed")
            else -> null
        }

    private fun isDangerousCommand(command: String): Boolean {
        val dangerousCommands = listOf("rm -rf", "format", "shutdown", "reboot", "sudo rm", "del /", "rmdir /s")
        return dangerousCommands.any { command.contains(it, ignoreCase = true) }
    }

    private suspend fun executeCommand(
        params: TerminalParams,
        context: TaskContext,
    ): ToolResult {
        return try {
            val workingDirectory = validateWorkingDirectory(context)
            workingDirectory?.let { return it }

            executeProcessWithTimeout(params, context)
        } catch (e: TimeoutCancellationException) {
            ToolResult.error("Command execution timed out: ${e.message}")
        } catch (e: AccessDeniedException) {
            ToolResult.error("Access denied to working directory: ${e.message}")
        } catch (e: IOException) {
            ToolResult.error("I/O error during command execution: ${e.message}")
        } catch (e: SecurityException) {
            ToolResult.error("Security restriction prevented command execution: ${e.message}")
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error during terminal command execution" }
            ToolResult.error("Terminal execution failed: ${e.message}")
        }
    }

    private fun validateWorkingDirectory(context: TaskContext): ToolResult? {
        val workingDirectory = File(context.projectDocument.path)
        return when {
            !workingDirectory.exists() -> ToolResult.error("Working directory does not exist: ${workingDirectory.absolutePath}")
            !workingDirectory.isDirectory -> ToolResult.error("Path is not a directory: ${workingDirectory.absolutePath}")
            !workingDirectory.canRead() -> ToolResult.error("Cannot read working directory: ${workingDirectory.absolutePath}")
            !workingDirectory.canWrite() -> ToolResult.error("Cannot write to working directory: ${workingDirectory.absolutePath}")
            else -> null
        }
    }

    private suspend fun executeProcessWithTimeout(
        params: TerminalParams,
        context: TaskContext,
    ): ToolResult {
        val workingDirectory = File(context.projectDocument.path)
        val timeoutSeconds = params.timeout?.toLong() ?: timeoutsProperties.mcp.terminalToolTimeoutSeconds

        return withTimeout(timeoutSeconds.seconds) {
            withContext(Dispatchers.IO) {
                val processBuilder = createProcessBuilder(params, workingDirectory)
                executeProcess(processBuilder, params, workingDirectory, timeoutSeconds)
            }
        }
    }

    private fun createProcessBuilder(
        params: TerminalParams,
        workingDirectory: File,
    ): ProcessBuilder =
        ProcessBuilder().apply {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            command(
                when {
                    isWindows -> listOf("cmd", "/c", params.command)
                    else -> listOf("sh", "-c", params.command)
                },
            )
            directory(workingDirectory)
            redirectErrorStream(true)
        }

    private suspend fun executeProcess(
        processBuilder: ProcessBuilder,
        params: TerminalParams,
        workingDirectory: File,
        timeoutSeconds: Long,
    ): ToolResult {
        return try {
            val process =
                processBuilder.start()
                    ?: return ToolResult.error("Failed to start process")

            val processCompleted = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            return when {
                !processCompleted -> handleTimeoutProcess(process, timeoutSeconds)
                else -> handleCompletedProcess(process, params, workingDirectory)
            }
        } catch (e: IOException) {
            ToolResult.error("Failed to start process: ${e.message}")
        } catch (e: InterruptedException) {
            ToolResult.error("Process execution was interrupted: ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("Process execution failed: ${e.message}")
        }
    }

    private fun handleTimeoutProcess(
        process: Process,
        timeoutSeconds: Long,
    ): ToolResult =
        try {
            process.destroyForcibly()
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                ToolResult.error("Command timed out after $timeoutSeconds seconds and was terminated")
            } else {
                ToolResult.error("Command timed out after $timeoutSeconds seconds and could not be terminated")
            }
        } catch (e: Exception) {
            ToolResult.error("Command timed out and cleanup failed: ${e.message}")
        }

    private fun handleCompletedProcess(
        process: Process,
        params: TerminalParams,
        workingDirectory: File,
    ): ToolResult {
        return try {
            val output = readProcessOutput(process)
            val exitCode = process.exitValue()

            logger.debug { "[DEBUG_LOG] Terminal process output: $output" }

            return when (exitCode) {
                0 -> createSuccessResult(output)
                else -> ToolResult.error("Command failed with exit code $exitCode:\n$output")
            }
        } catch (e: IOException) {
            ToolResult.error("Failed to read process output: ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("Failed to process command result: ${e.message}")
        }
    }

    private fun readProcessOutput(process: Process): String =
        try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw IOException("Failed to read process output stream", e)
        }

    private fun createSuccessResult(output: String): ToolResult {
        val enhancedOutput = output.trim().ifEmpty { "Command executed successfully" }
        return ToolResult.ok(enhancedOutput)
    }
}
