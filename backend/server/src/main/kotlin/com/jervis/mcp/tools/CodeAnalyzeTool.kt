package com.jervis.mcp.tools

import com.jervis.common.client.IJoernClient
import com.jervis.common.dto.JoernQueryDto
import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.mcp.McpTool
import com.jervis.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.storage.DirectoryStructureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Service
class CodeAnalyzeTool(
    private val joernClient: IJoernClient,
    private val directoryStructureService: DirectoryStructureService,
    override val promptRepository: PromptRepository,
) : McpTool<CodeAnalyzeTool.CodeAnalyzeParams> {
    override val name = ToolTypeEnum.CODE_ANALYZE_TOOL
    override val descriptionObject =
        CodeAnalyzeParams(
            analysisQuery = "Find all instances of SQL injection vulnerabilities.",
            methodPattern = ".*Database.*",
            maxResults = 50,
            includeExternal = false,
            targetLanguage = "auto",
        )

    @Serializable
    data class Description(
        val instruction: String,
    )

    @Serializable
    data class CodeAnalyzeParams(
        val analysisQuery: String,
        val methodPattern: String,
        val maxResults: Int,
        val includeExternal: Boolean,
        val targetLanguage: String,
    )

    override suspend fun execute(
        plan: Plan,
        request: CodeAnalyzeParams,
    ): ToolResult =
        withContext(Dispatchers.IO) {
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
                val script = buildJoernQuery(request)
                val zipB64 = zipDirectoryBase64(projectPath)
                val request = JoernQueryDto(query = script, projectZipBase64 = zipB64)
                val resp = joernClient.run(request)
                if (resp.exitCode != 0) {
                    return@withContext ToolResult.error("Joern failed (exit=${resp.exitCode}): ${resp.stderr ?: resp.stdout}")
                }

                ToolResult.analysisResult(
                    toolName = "CODE_ANALYZE",
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

    private fun zipDirectoryBase64(root: Path): String {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            Files.walk(root).use { stream ->
                stream
                    .filter { p ->
                        Files
                            .isRegularFile(p)
                    }.filter { p -> !p.toString().contains("${File.separator}.git${File.separator}") }
                    .forEach { file ->
                        val entryName = root.relativize(file).toString().replace('\\', '/')
                        val entry = ZipEntry(entryName)
                        zos.putNextEntry(entry)
                        Files.newInputStream(file).use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
            }
        }
        return Base64
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
