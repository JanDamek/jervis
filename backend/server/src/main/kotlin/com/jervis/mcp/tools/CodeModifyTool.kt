package com.jervis.mcp.tools

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.mcp.McpTool
import com.jervis.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files

@Service
class CodeModifyTool(
    private val directoryStructureService: DirectoryStructureService,
    override val promptRepository: PromptRepository,
) : McpTool<CodeModifyTool.CodeModifyParams> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name = ToolTypeEnum.CODE_MODIFY_TOOL

    override val descriptionObject =
        CodeModifyParams(
            targetPath = "Relative path to target file from project root (required)",
            patch = "Complete replacement content of the file (required)",
            createNewFile = false,
        )

    @Serializable
    data class CodeModifyParams(
        val targetPath: String,
        val patch: String,
        val createNewFile: Boolean,
    )

    override suspend fun execute(
        plan: Plan,
        request: CodeModifyParams,
    ): ToolResult = executeCodeModifyOperation(request, plan)

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
