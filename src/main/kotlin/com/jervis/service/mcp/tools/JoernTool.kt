package com.jervis.service.mcp.tools

import com.jervis.configuration.TimeoutsProperties
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.analysis.JoernAnalysisService
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.util.ProcessStreamingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Service
import java.io.File

@Service
class JoernTool(
    private val llmGateway: LlmGateway,
    private val joernAnalysisService: JoernAnalysisService,
    private val timeoutsProperties: TimeoutsProperties,
    private val promptRepository: PromptRepository,
) : McpTool {
    override val name: String = "joern"
    override val description: String
        get() = promptRepository.getMcpToolDescription(PromptTypeEnum.JOERN)

    @Serializable
    data class JoernParams(
        val operations: List<JoernQuery> = emptyList(),
        val finalPrompt: String? = null,
    )

    @Serializable
    data class JoernQuery(
        val scriptContent: String = "",
        val scriptFilename: String = "",
        val terminalCommand: String = "",
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
    ): JoernParams {
        val userPrompt = promptRepository.getMcpToolUserPrompt(PromptTypeEnum.JOERN)
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.JOERN,
                userPrompt = userPrompt.replace("{userPrompt}", taskDescription),
                outputLanguage = "en",
                quick = context.quick,
                mappingValue = emptyMap(),
                exampleInstance = JoernParams(),
            )

        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult {
        val projectDir = File(context.projectDocument.path)

        if (!projectDir.exists() || !projectDir.isDirectory) {
            return ToolResult.error("Project path does not exist or is not a directory: $projectDir")
        }

        val parsed =
            try {
                parseTaskDescription(taskDescription, context)
            } catch (e: Exception) {
                return ToolResult.error("Invalid Joern parameters: ${e.message}", "Joern parameter parsing failed")
            }

        if (parsed.operations.isEmpty()) {
            return ToolResult.error("Joern operations cannot be empty")
        }

        return withContext(Dispatchers.IO) {
            try {
                // Check if Joern is available using the service
                if (!joernAnalysisService.isJoernAvailable()) {
                    return@withContext ToolResult.error(
                        "Joern is not installed or not accessible in system PATH. Please install Joern first.",
                    )
                }

                // Setup .joern directory
                val projectPathObj = projectDir.toPath()
                val joernDir = joernAnalysisService.setupJoernDirectory(projectPathObj)

                val outputs = mutableListOf<String>()
                val jobs = mutableListOf<Job>()
                for ((index, operation) in parsed.operations.withIndex()) {
                    jobs.add(
                        launch {
                            // Save scriptContent to a script file using the designated filename
                            val scriptFile = joernDir.resolve(operation.scriptFilename).toFile()
                            scriptFile.writeText(operation.scriptContent)

                            // Execute the terminal command using ProcessStreamingUtils
                            val processResult =
                                ProcessStreamingUtils.runProcess(
                                    ProcessStreamingUtils.ProcessConfig(
                                        command = operation.terminalCommand,
                                        workingDirectory = projectDir,
                                        timeoutSeconds = timeoutsProperties.mcp.joernToolTimeoutSeconds,
                                    ),
                                )
                            if (!processResult.isSuccess) {
                                outputs +=
                                    "Joern operation ${index + 1} failed with exit code ${processResult.exitCode}: ${processResult.output}"
                            }

                            // Collect output files mentioned in the command (heuristic)
                            val outFileMatch = Regex("--param outFile=\"([^\"]+)\"").find(operation.terminalCommand)
                            val outFilePath = outFileMatch?.groups?.get(1)?.value
                            val output =
                                if (outFilePath != null) {
                                    val outFile = File(outFilePath)
                                    if (outFile.exists()) {
                                        outFile.readText()
                                    } else {
                                        "Output file not found: $outFilePath"
                                    }
                                } else {
                                    "No output file specified in terminal command."
                                }
                            outputs.add(output)
                        },
                    )
                }
                jobs.joinAll()

                val enhancedOutput =
                    buildString {
                        outputs.forEachIndexed { idx, output ->
                            if (outputs.size > 1) append("Op${idx + 1}: ")
                            append(formatJoernOutput(output))
                            if (idx < outputs.size - 1) appendLine()
                        }
                    }

                ToolResult.ok(enhancedOutput)
            } catch (e: Exception) {
                ToolResult.error("Joern execution failed: ${e.message}")
            }
        }
    }

    private fun formatJoernOutput(output: String): String =
        try {
            // Try to format JSON if it's valid
            val trimmed = output.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                // Basic JSON formatting - could be enhanced with a proper JSON library
                trimmed
            } else {
                output
            }
        } catch (_: Exception) {
            output
        }
}
