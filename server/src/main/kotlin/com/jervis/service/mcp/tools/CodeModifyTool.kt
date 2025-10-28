package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files

@Service
class CodeModifyTool(
    private val llmGateway: LlmGateway,
    private val directoryStructureService: DirectoryStructureService,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.CODE_MODIFY_TOOL

    @Serializable
    data class CodeModifyParams(
        val targetPath: String = "",
        val patch: String = "",
        val createNewFile: Boolean = false,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        plan: Plan,
        stepContext: String,
    ): CodeModifyParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.CODE_MODIFY_TOOL,
                responseSchema = CodeModifyParams(),
                quick = plan.quick,
                mappingValue =
                    mapOf(
                        "taskDescription" to taskDescription,
                        "filePath" to "", // Will be extracted from taskDescription by LLM
                        "language" to "kotlin", // Default to Kotlin for this project
                        "requirements" to "", // Additional requirements will be extracted from taskDescription
                        "stepContext" to stepContext,
                    ),
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

        return executeCodeModifyOperation(parsed, plan)
    }

    private suspend fun executeCodeModifyOperation(
        params: CodeModifyParams,
        plan: Plan,
    ): ToolResult {
        val projectPath =
            directoryStructureService.projectGitDir(
                plan.clientDocument.id,
                plan.projectDocument!!.id,
            )
        val targetFile = projectPath.resolve(params.targetPath)

        targetFile.parent?.let { Files.createDirectories(it) }

        Files.writeString(targetFile, params.patch)

        val action = if (params.createNewFile) "Created" else "Modified"
        val details =
            buildString {
                appendLine("Operation: ${if (params.createNewFile) "Created new file" else "Modified existing file"}")
                appendLine("File: ${params.targetPath}")
                appendLine("Content length: ${params.patch.length} characters")
            }

        return ToolResult.success("CODE_MODIFY", action, details)
    }
}
