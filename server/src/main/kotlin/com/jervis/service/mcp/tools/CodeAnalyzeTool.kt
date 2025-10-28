package com.jervis.service.mcp.tools

import com.jervis.configuration.TimeoutsProperties
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.service.analysis.JoernAnalysisService
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.storage.DirectoryStructureService
import com.jervis.util.ProcessStreamingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Service
import java.io.File

@Service
class CodeAnalyzeTool(
    private val llmGateway: LlmGateway,
    private val joernAnalysisService: JoernAnalysisService,
    private val timeoutsProperties: TimeoutsProperties,
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
                // Detect language buckets and ensure CPG files exist
                val languageBuckets = joernAnalysisService.detectLanguageBuckets(projectPath)
                val allCpgMap = joernAnalysisService.ensurePerLanguageCpg(projectPath, languageBuckets)

                if (allCpgMap.isEmpty()) {
                    return@withContext ToolResult.error("No CPG files found in project. Run indexing first to generate CPG files.")
                }

                val cpgMap =
                    if (params.targetLanguage.isNotBlank()) {
                        allCpgMap.filterKeys { language ->
                            language.equals(params.targetLanguage, ignoreCase = true)
                        }
                    } else {
                        allCpgMap
                    }

                if (cpgMap.isEmpty()) {
                    return@withContext ToolResult.error(
                        "No CPG files found for language '${params.targetLanguage}'. Available languages: ${
                            allCpgMap.keys.joinToString(", ")
                        }",
                    )
                }

                val results = mutableListOf<String>()

                for ((languageName, cpgPath) in cpgMap) {
                    try {
                        val script = loadAndPrepareScript(params, cpgPath, languageName)
                        val scriptFile = createTempScriptFile(script, languageName)
                        val result = runJoernScript(scriptFile, projectDir)

                        if (!result.isSuccess) {
                            return@withContext ToolResult.error("Joern failed for ${cpgPath.fileName}: ${result.output}")
                        }

                        val languageResult = "=== ${languageName.uppercase()} ANALYSIS ===\n${result.output}"
                        results.add(languageResult)
                    } catch (e: Exception) {
                        val errorMessage = e.message ?: e.toString()
                        val errorResult = "=== ${languageName.uppercase()} ANALYSIS (FAILED) ===\nError: $errorMessage"
                        results.add(errorResult)
                    }
                }

                val combinedResults = results.joinToString("\n\n---\n\n")

                ToolResult.analysisResult(
                    toolName = "CODE_ANALYZE",
                    analysisType = "Code Analysis: ${params.analysisQuery}",
                    count = cpgMap.size,
                    unit = "language",
                    results = formatJoernOutput(combinedResults),
                )
            } catch (e: Exception) {
                val errorMessage = e.message ?: e.toString()
                ToolResult.error("Code analysis error: $errorMessage")
            }
        }

    private suspend fun runJoernScript(
        scriptPath: File,
        projectDir: File,
    ): ProcessResult =
        withContext(Dispatchers.IO) {
            val processResult =
                ProcessStreamingUtils.runProcess(
                    ProcessStreamingUtils.ProcessConfig(
                        command =
                            buildString {
                                append("joern")
                                append(" -J-XX:+IgnoreUnrecognizedVMOptions")
                                append(" -J-Djava.awt.headless=true")
                                append(" -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED")
                                append(" -J--add-opens=java.base/java.io=ALL-UNNAMED")
                                append(" -J--add-opens=java.base/java.util=ALL-UNNAMED")
                                append(" --script \"${scriptPath.absolutePath}\"")
                            },
                        workingDirectory = projectDir,
                        timeoutSeconds = timeoutsProperties.mcp.joernToolTimeoutSeconds,
                    ),
                )

            ProcessResult(processResult.output, processResult.isSuccess, processResult.exitCode)
        }

    private fun loadAndPrepareScript(
        params: CodeAnalyzeParams,
        cpgPath: java.nio.file.Path,
        language: String,
    ): String {
        val templateResource =
            this::class.java.getResourceAsStream("/joern/code_analysis.sc")
                ?: throw RuntimeException("Code analysis template not found in resources")

        val template = templateResource.bufferedReader().use { it.readText() }

        return template
            .replace("{{CPG_PATH}}", cpgPath.toString())
            .replace("{{LANGUAGE}}", language)
            .replace("{{ANALYSIS_QUERY}}", params.analysisQuery)
            .replace("{{METHOD_PATTERN}}", params.methodPattern)
            .replace("{{MAX_RESULTS}}", params.maxResults.toString())
            .replace("{{INCLUDE_EXTERNAL}}", params.includeExternal.toString())
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

    /**
     * Extract language name from CPG file path (e.g., "cpg_kotlin.bin" -> "kotlin")
     */
    private fun extractLanguageFromCpgPath(cpgPath: java.nio.file.Path): String {
        val fileName = cpgPath.fileName.toString()
        return if (fileName.startsWith("cpg_") && fileName.endsWith(".bin")) {
            fileName.removePrefix("cpg_").removeSuffix(".bin")
        } else {
            "unknown"
        }
    }

    /**
     * Create temporary script file for a specific language
     */
    private suspend fun createTempScriptFile(
        script: String,
        languageName: String,
    ): File =
        withContext(Dispatchers.IO) {
            val scriptFile = File.createTempFile("joern-$languageName-", ".sc")
            scriptFile.writeText(script)
            scriptFile
        }

    data class ProcessResult(
        val output: String,
        val isSuccess: Boolean,
        val exitCode: Int,
    )
}
