package com.jervis.service.mcp.tools

import com.jervis.common.client.IJoernClient
import com.jervis.common.dto.JoernQueryDto
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Service

@Service
class CodeAnalyzeTool(
    private val llmGateway: LlmGateway,
    private val joernClient: IJoernClient,
    private val directoryStructureService: DirectoryStructureService,
    override val promptRepository: PromptRepository,
) : McpTool {
    override val name: PromptTypeEnum = PromptTypeEnum.CODE_ANALYZE_TOOL

    @Serializable
    data class CodeAnalyzeParams(
        val analysisQuery: String = "",
        val methodPattern: String = ".*",
        val maxResults: Int = 100,
        val includeExternal: Boolean = false,
        val targetLanguage: String = "",
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        plan: Plan,
    ): CodeAnalyzeParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.CODE_ANALYZE_TOOL,
                responseSchema = CodeAnalyzeParams(),
                correlationId = plan.correlationId,
                quick = plan.quick,
                mappingValue = mapOf("taskDescription" to taskDescription),
                backgroundMode = plan.backgroundMode,
            )
        return llmResponse.result
    }

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult =
        withContext(Dispatchers.IO) {
            val params = parseTaskDescription(taskDescription, plan)
            val projectPath =
                directoryStructureService.projectGitDir(
                    plan.clientDocument.id,
                    plan.projectDocument!!.id,
                )
            val projectDir = projectPath.toFile()

            if (!projectDir.exists() || !projectDir.isDirectory) {
                return@withContext ToolResult.error("Project path does not exist or is not a directory: $projectDir")
            }

            try {
                val script = buildJoernQuery(params)
                val zipB64 = zipDirectoryBase64(projectPath)
                val request = JoernQueryDto(query = script, projectZipBase64 = zipB64)
                val resp = joernClient.run(request)
                if (resp.exitCode != 0) {
                    return@withContext ToolResult.error("Joern failed (exit=${resp.exitCode}): ${resp.stderr ?: resp.stdout}")
                }

                ToolResult.analysisResult(
                    toolName = "CODE_ANALYZE",
                    analysisType = "Code Analysis: ${params.analysisQuery}",
                    count = 1,
                    unit = "project",
                    results = formatJoernOutput(resp.stdout),
                )
            } catch (e: Exception) {
                val errorMessage = e.message ?: e.toString()
                ToolResult.error("Code analysis error: $errorMessage")
            }
        }

    private fun buildJoernQuery(params: CodeAnalyzeParams): String {
        // Build a simple Joern query - the service-joern will handle the template processing
        return """
            importCode(".")
            val language = "${params.targetLanguage.ifBlank { "project" }}"
            val analysisQuery = "${params.analysisQuery}"
            val methodPattern = "${params.methodPattern}"
            val maxResults = ${params.maxResults}
            val includeExternal = ${params.includeExternal}
            """.trimIndent()
    }

    private fun zipDirectoryBase64(root: java.nio.file.Path): String {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            java.nio.file.Files.walk(root).use { stream ->
                stream
                    .filter { p ->
                        java.nio.file.Files
                            .isRegularFile(p)
                    }.filter { p -> !p.toString().contains("${java.io.File.separator}.git${java.io.File.separator}") }
                    .forEach { file ->
                        val entryName = root.relativize(file).toString().replace('\\', '/')
                        val entry = java.util.zip.ZipEntry(entryName)
                        zos.putNextEntry(entry)
                        java.nio.file.Files.newInputStream(file).use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
            }
        }
        return java.util.Base64
            .getEncoder()
            .encodeToString(baos.toByteArray())
    }

    private fun formatJoernOutput(output: String): String =
        try {
            val trimmed = output.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) trimmed else output
        } catch (_: Exception) {
            output
        }
}
