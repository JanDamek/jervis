package com.jervis.service.mcp.tools

import com.jervis.configuration.TimeoutsProperties
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@Service
class SystemExecuteCommandTool(
    private val llmGateway: LlmGateway,
    private val timeoutsProperties: TimeoutsProperties,
    override val promptRepository: PromptRepository,
    private val directoryStructureService: DirectoryStructureService,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name = PromptTypeEnum.SYSTEM_EXECUTE_COMMAND_TOOL

    @Serializable
    data class SystemExecuteCommandParams(
        val command: String = "",
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        plan: Plan,
        stepContext: String = "",
    ): SystemExecuteCommandParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.SYSTEM_EXECUTE_COMMAND_TOOL,
                mappingValue =
                    mapOf(
                        "taskDescription" to taskDescription,
                        "stepContext" to stepContext,
                    ),
                quick = plan.quick,
                responseSchema = SystemExecuteCommandParams(),
                backgroundMode = plan.backgroundMode,
            )

        return llmResponse.result
    }

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription, plan, stepContext)

        return executeSystemExecuteCommandOperation(parsed, plan)
    }

    private fun validateCommand(params: SystemExecuteCommandParams): ToolResult? =
        when {
            params.command.isBlank() -> ToolResult.error("Command cannot be empty")
            isDangerousCommand(params.command) -> ToolResult.error("Command contains dangerous operations and is not allowed")
            else -> null
        }

    private fun isDangerousCommand(command: String): Boolean {
        val dangerousCommands = listOf("rm -rf", "format", "shutdown", "reboot", "sudo rm", "del /", "rmdir /s")
        return dangerousCommands.any { command.contains(it, ignoreCase = true) }
    }

    private suspend fun executeSystemExecuteCommandOperation(
        params: SystemExecuteCommandParams,
        plan: Plan,
    ): ToolResult {
        val workingDirectory = validateWorkingDirectory(plan)
        workingDirectory?.let { return it }

        return executeProcessWithTimeout(params, plan)
    }

    private fun validateWorkingDirectory(plan: Plan): ToolResult? {
        val workingDirectory =
            directoryStructureService
                .projectGitDir(
                    plan.clientDocument.id,
                    plan.projectDocument!!.id,
                ).toFile()
        return when {
            !workingDirectory.exists() -> ToolResult.error("Working directory does not exist: ${workingDirectory.absolutePath}")
            !workingDirectory.isDirectory -> ToolResult.error("Path is not a directory: ${workingDirectory.absolutePath}")
            !workingDirectory.canRead() -> ToolResult.error("Cannot read working directory: ${workingDirectory.absolutePath}")
            !workingDirectory.canWrite() -> ToolResult.error("Cannot write to working directory: ${workingDirectory.absolutePath}")
            else -> null
        }
    }

    private suspend fun executeProcessWithTimeout(
        params: SystemExecuteCommandParams,
        plan: Plan,
    ): ToolResult {
        val workingDirectory =
            directoryStructureService
                .projectGitDir(
                    plan.clientDocument.id,
                    plan.projectDocument!!.id,
                ).toFile()
        val timeoutSeconds = timeoutsProperties.mcp.terminalToolTimeoutSeconds

        return withTimeout(timeoutSeconds.seconds) {
            withContext(Dispatchers.IO) {
                val processBuilder = createProcessBuilder(params, workingDirectory)
                executeProcess(processBuilder, params, workingDirectory, timeoutSeconds)
            }
        }
    }

    private fun createProcessBuilder(
        params: SystemExecuteCommandParams,
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
        params: SystemExecuteCommandParams,
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
        params: SystemExecuteCommandParams,
        workingDirectory: File,
    ): ToolResult {
        return try {
            val output = readProcessOutput(process)
            val exitCode = process.exitValue()

            logger.debug { "[DEBUG_LOG] Terminal process output: $output" }

            return when (exitCode) {
                0 -> createSuccessResult(params.command, output)
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

    private fun createSuccessResult(
        command: String,
        output: String,
    ): ToolResult {
        val summary = "Executed command successfully"
        val content =
            buildString {
                appendLine("Command: $command")
                val trimmedOutput = output.trim()
                if (trimmedOutput.isNotEmpty()) {
                    appendLine()
                    append(trimmedOutput)
                }
            }
        return ToolResult.success("TERMINAL", summary, content)
    }
}
