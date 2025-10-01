package com.jervis.service.mcp.tools

import com.jervis.configuration.TimeoutsProperties
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.analysis.JoernAnalysisService
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
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
    override val promptRepository: PromptRepository,
) : McpTool {
    override val name: PromptTypeEnum = PromptTypeEnum.CODE_ANALYZE

    @Serializable
    data class CodeAnalyzeParams(
        val scriptContent: String = "",
        val scriptFilename: String = "",
        val targetMethods: List<String> = emptyList(),
        val analysisDepth: Int = 3,
        val includeExternalCalls: Boolean = false,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): CodeAnalyzeParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.CODE_ANALYZE,
                responseSchema = CodeAnalyzeParams(),
                quick = context.quick,
                mappingValue = mapOf("taskDescription" to taskDescription),
                stepContext = stepContext,
            )
        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult =
        withContext(Dispatchers.IO) {
            val params = parseTaskDescription(taskDescription, context, stepContext)
            val projectDir = File(context.projectDocument.path)

            if (!projectDir.exists() || !projectDir.isDirectory) {
                return@withContext ToolResult.error("Project path does not exist or is not a directory: $projectDir")
            }

            try {
                // Get all existing CPG files using the new multi-language approach
                val cpgList = joernAnalysisService.ensurePerLanguageCpgs(projectDir.toPath())
                if (cpgList.isEmpty()) {
                    return@withContext ToolResult.error("No CPG files found in project. Run indexing first to generate CPG files.")
                }

                val results = mutableListOf<String>()
                var totalMethodCount = 0

                // Process each CPG file separately
                for (cpgPath in cpgList) {
                    val languageName = extractLanguageFromCpgPath(cpgPath)

                    try {
                        val script = generateCallgraphScript(params, cpgPath)
                        val scriptFile = createTempScriptFile(script, languageName)
                        val result = runJoernScript(scriptFile, projectDir)

                        if (!result.isSuccess) {
                            return@withContext ToolResult.error("Joern failed for ${cpgPath.fileName}: ${result.output}")
                        }

                        // Add language header to the result
                        val languageResult = "=== ${languageName.uppercase()} ANALYSIS ===\n${result.output}"
                        results.add(languageResult)

                        // Count methods if this is callgraph analysis
                        if (params.targetMethods.isNotEmpty() || params.analysisDepth != 3 || params.includeExternalCalls) {
                            totalMethodCount += countMethodsInOutput(result.output, params)
                        }
                    } catch (e: Exception) {
                        val errorResult = "=== ${languageName.uppercase()} ANALYSIS (FAILED) ===\nError: ${e.message}"
                        results.add(errorResult)
                    }
                }

                // Format the multi-language result
                val combinedResults = results.joinToString("\n\n---\n\n")

                // Determine analysis type and format response accordingly
                if (params.targetMethods.isNotEmpty() || params.analysisDepth != 3 || params.includeExternalCalls) {
                    // This is a call graph analysis
                    val summary =
                        when {
                            params.targetMethods.isNotEmpty() -> "Multi-language analysis of ${params.targetMethods.size} target methods across ${cpgList.size} languages"
                            totalMethodCount > 0 -> "Multi-language analysis of $totalMethodCount methods across ${cpgList.size} languages"
                            else -> "Multi-language callgraph analysis across ${cpgList.size} languages"
                        }

                    val analysisParams =
                        buildString {
                            appendLine(
                                "Languages: ${
                                    cpgList.map { path -> extractLanguageFromCpgPath(path) }.joinToString(", ")
                                }",
                            )
                            if (params.targetMethods.isNotEmpty()) {
                                appendLine("Target Methods: ${params.targetMethods.joinToString(", ")}")
                            }
                            appendLine("Analysis Depth: ${params.analysisDepth}")
                            appendLine("Include External Calls: ${params.includeExternalCalls}")
                        }.trim()

                    ToolResult.success(
                        toolName = "MULTI_LANGUAGE_JOERN_ANALYSIS",
                        summary = summary,
                        content = analysisParams,
                        formatJoernOutput(combinedResults),
                    )
                } else {
                    // This is a static analysis
                    ToolResult.analysisResult(
                        toolName = "CODE_ANALYZE",
                        analysisType = "Multi-language CPG Analysis",
                        count = cpgList.size,
                        unit = "language",
                        results = formatJoernOutput(combinedResults),
                    )
                }
            } catch (e: Exception) {
                ToolResult.error("Multi-language Joern analysis error: ${e.message}")
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

    private fun generateCallgraphScript(
        params: CodeAnalyzeParams,
        cpgPath: java.nio.file.Path,
    ): String =
        buildString {
            appendLine("importCpg(\"${cpgPath}\")")
            appendLine("import io.shiftleft.semanticcpg.language._")
            appendLine("")

            if (params.targetMethods.isNotEmpty()) {
                appendLine("// Focused analysis on specific methods")
                val methodNames = params.targetMethods.joinToString("\", \"", "\"", "\"")
                appendLine("val targetMethods = List($methodNames)")
                appendLine("val callgraphData = cpg.method.name(targetMethods: _*).map { method =>")
                appendLine("  Map(")
                appendLine("    \"method\" -> method.name,")
                appendLine("    \"fullName\" -> method.fullName,")
                appendLine("    \"signature\" -> method.signature,")
                appendLine("    \"callers\" -> method.caller.take(${params.analysisDepth}).map(c => Map(")
                appendLine("      \"name\" -> c.name,")
                appendLine("      \"fullName\" -> c.fullName,")
                appendLine("      \"signature\" -> c.signature")
                appendLine("    )).toList,")
                if (params.includeExternalCalls) {
                    appendLine("    \"callees\" -> method.callee.take(${params.analysisDepth}).map(c => Map(")
                } else {
                    appendLine(
                        "    \"callees\" -> method.callee.take(${params.analysisDepth}).filter(c => !c.fullName.startsWith(\"java.\") && !c.fullName.startsWith(\"scala.\")).map(c => Map(",
                    )
                }
                appendLine("      \"name\" -> c.name,")
                appendLine("      \"fullName\" -> c.fullName,")
                appendLine("      \"signature\" -> c.signature")
                appendLine("    )).toList")
                appendLine("  )")
                appendLine("}.toList")
            } else {
                appendLine("// Full callgraph analysis")
                if (params.includeExternalCalls) {
                    appendLine("val callgraphData = cpg.method.take(100).map { method =>")
                } else {
                    appendLine(
                        "val callgraphData = cpg.method.filter(m => !m.fullName.startsWith(\"java.\") && !m.fullName.startsWith(\"scala.\")).take(100).map { method =>",
                    )
                }
                appendLine("  Map(")
                appendLine("    \"method\" -> method.name,")
                appendLine("    \"fullName\" -> method.fullName,")
                appendLine("    \"signature\" -> method.signature,")
                appendLine("    \"callCount\" -> method.caller.size,")
                appendLine("    \"calleeCount\" -> method.callee.size")
                appendLine("  )")
                appendLine("}.toList")
            }

            appendLine("")
            appendLine("val result = Map(")
            appendLine("  \"analysisType\" -> \"callgraph\",")
            appendLine("  \"targetMethods\" -> List(${params.targetMethods.joinToString("\", \"", "\"", "\"")}),")
            appendLine("  \"analysisDepth\" -> ${params.analysisDepth},")
            appendLine("  \"includeExternalCalls\" -> ${params.includeExternalCalls},")
            appendLine("  \"results\" -> callgraphData")
            appendLine(")")
            appendLine("")
            appendLine("println(result.toJson)")
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

    /**
     * Count methods in Joern output for progress reporting
     */
    private fun countMethodsInOutput(
        output: String,
        params: CodeAnalyzeParams,
    ): Int =
        if (params.targetMethods.isNotEmpty()) {
            params.targetMethods.size
        } else {
            try {
                val trimmed = output.trim()
                when {
                    trimmed.contains("\"results\"") -> trimmed.split("\"method\"").size - 1
                    trimmed.contains("\"callgraphData\"") -> trimmed.split("\"method\"").size - 1
                    else -> 0
                }
            } catch (_: Exception) {
                0
            }
        }

    data class ProcessResult(
        val output: String,
        val isSuccess: Boolean,
        val exitCode: Int,
    )
}
