package com.jervis.service.mcp.tools

import com.jervis.configuration.TimeoutsProperties
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.mcp.util.McpFinalPromptProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    private val mcpFinalPromptProcessor: McpFinalPromptProcessor,
    private val timeoutsProperties: TimeoutsProperties,
    private val projectMongoRepository: ProjectMongoRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: String = "terminal"
    override val description: String =
        "Executes system commands and development tools via terminal. Use for building, testing, git operations, file management, package installation, and running development tools. Specify command details: 'run mvn test in target directory', 'build project with npm', 'check git status', or 'install dependencies with timeout 300s'."

    @Serializable
    data class TerminalParams(
        val command: String,
        val timeout: Int? = null,
        val finalPrompt: String? = null,
    )

    private suspend fun parseTaskDescription(taskDescription: String): TerminalParams {
        val systemPrompt =
            """
            You are the Terminal Tool parameter resolver. Your task is to convert a natural language task description into proper parameters for the Terminal Tool.           
            The Terminal Tool provides:
            - Direct access to system commands and development tools
            - Building and compiling projects (mvn compile, npm build, cargo build, etc.)
            - Running tests and verification (npm test, mvn test, pytest, etc.)
            - Installing dependencies and packages (npm install, pip install, apt-get, etc.)
            - Git operations and version control (git status, git commit, git push, etc.)
            - File system operations (ls, find, grep, mkdir, cp, etc.)
            - Database operations, deployment commands, and development server management            
            Return ONLY a valid JSON object with this exact structure:
            {
              "command": "<terminal command to execute>",
              "timeout": <timeout in seconds, optional>,
              "finalPrompt": "<LLM prompt to process results, optional>"
            }          
            Examples:
            - "run tests" → {"command": "mvn test"}
            - "build the project" → {"command": "mvn clean compile"}
            - "check git status" → {"command": "git status"}            
            Rules:
            - command: must be a specific, executable terminal command
            - timeout: only specify if command might take longer than default (>60s)
            - finalPrompt: add if results need specific interpretation
            - Never include dangerous commands like 'rm -rf', 'format', 'shutdown', 'reboot'
            - Return only valid JSON, no explanations or markdown
            """.trimIndent()

        return try {
            val llmResponse =
                llmGateway.callLlm(
                    type = ModelType.INTERNAL,
                    systemPrompt = systemPrompt,
                    userPrompt = "Task: $taskDescription",
                )

            val cleanedResponse =
                llmResponse.answer
                    .trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

            Json.decodeFromString<TerminalParams>(cleanedResponse)
        } catch (e: Exception) {
            logger.warn { "Failed to parse task description via LLM: ${e.message}. Using fallback parsing." }
            // Fallback: create simple command from task description
            val fallbackCommand =
                when {
                    taskDescription.contains("test", ignoreCase = true) -> "mvn test"
                    taskDescription.contains("build", ignoreCase = true) -> "mvn clean compile"
                    taskDescription.contains("git", ignoreCase = true) -> "git status"
                    taskDescription.contains("install", ignoreCase = true) &&
                        taskDescription.contains("npm", ignoreCase = true) -> "npm install"

                    taskDescription.contains("install", ignoreCase = true) &&
                        taskDescription.contains("maven", ignoreCase = true) -> "mvn clean install"

                    else -> taskDescription.trim()
                }

            TerminalParams(
                command = fallbackCommand,
                timeout = null,
                finalPrompt = null,
            )
        }
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription)

        val validationResult = validateCommand(parsed)
        validationResult?.let { return it }

        val executionResult = executeCommand(parsed, context)

        return mcpFinalPromptProcessor.processFinalPrompt(
            finalPrompt = parsed.finalPrompt,
            systemPrompt = mcpFinalPromptProcessor.createTerminalSystemPrompt(),
            originalResult = executionResult,
        )
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
                0 -> createSuccessResult(params, workingDirectory, output, exitCode)
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
        params: TerminalParams,
        workingDirectory: File,
        output: String,
        exitCode: Int,
    ): ToolResult {
        val enhancedOutput =
            buildString {
                appendLine("Command: ${params.command}")
                appendLine("Working Directory: ${workingDirectory.absolutePath}")
                appendLine("Exit Code: $exitCode")
                appendLine("Execution Time: < ${params.timeout ?: timeoutsProperties.mcp.terminalToolTimeoutSeconds}s")
                appendLine()
                appendLine("Output:")
                appendLine("```")
                append(output.trim())
                appendLine()
                appendLine("```")

                if (output.trim().isEmpty()) {
                    appendLine("(No output - command executed successfully)")
                }
            }

        return ToolResult.ok(enhancedOutput)
    }
}
