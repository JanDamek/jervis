package com.jervis.service.mcp.tools

import com.jervis.configuration.TimeoutsProperties
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.analysis.JoernAnalysisService
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.mcp.util.ToolResponseBuilder
import com.jervis.service.prompts.PromptRepository
import com.jervis.util.ProcessStreamingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Service
import java.io.File

@Service
class JoernCallgraphTool(
    private val llmGateway: LlmGateway,
    private val joernAnalysisService: JoernAnalysisService,
    private val timeoutsProperties: TimeoutsProperties,
    override val promptRepository: PromptRepository,
) : McpTool {
    override val name: PromptTypeEnum = PromptTypeEnum.JOERN_CALLGRAPH

    @Serializable
    data class CallgraphParams(
        val targetMethods: List<String> = emptyList(),
        val analysisDepth: Int = 3,
        val includeExternalCalls: Boolean = false,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): CallgraphParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.JOERN_CALLGRAPH,
                userPrompt = taskDescription,
                quick = context.quick,
                responseSchema = CallgraphParams(),
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
        val projectDir = File(context.projectDocument.path)

        if (!projectDir.exists() || !projectDir.isDirectory) {
            return ToolResult.error("Project path does not exist or is not a directory: $projectDir")
        }

        val parsed =
            try {
                parseTaskDescription(taskDescription, context, stepContext)
            } catch (e: Exception) {
                return ToolResult.error("Invalid Joern callgraph parameters: ${e.message}", "Joern callgraph parameter parsing failed")
            }

        return withContext(Dispatchers.IO) {
            try {
                // Setup .joern directory and ensure CPG exists
                val projectPathObj = projectDir.toPath()
                val joernDir = joernAnalysisService.setupJoernDirectory(projectPathObj)
                val cpgPath = joernDir.resolve("cpg.bin")

                // Ensure CPG exists before running analysis
                if (!joernAnalysisService.ensureCpgExists(projectPathObj, cpgPath)) {
                    return@withContext ToolResult.error("Failed to create or find CPG for callgraph analysis")
                }

                // Generate callgraph analysis script following proper Joern workflow
                val scriptContent = generateCallgraphScript(parsed, cpgPath)
                val scriptFilename = "callgraph-analysis-${System.currentTimeMillis()}.sc"
                
                // Save script to joern directory
                val scriptFile = joernDir.resolve(scriptFilename).toFile()
                scriptFile.writeText(scriptContent)

                // Execute using proper joern command with JVM flags to suppress deprecation warnings
                val processResult =
                    ProcessStreamingUtils.runProcess(
                        ProcessStreamingUtils.ProcessConfig(
                            command = buildString {
                                append("joern")
                                append(" -J-XX:+IgnoreUnrecognizedVMOptions")
                                append(" -J-Djava.awt.headless=true")
                                append(" -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED")
                                append(" -J--add-opens=java.base/java.io=ALL-UNNAMED")
                                append(" -J--add-opens=java.base/java.util=ALL-UNNAMED")
                                append(" --script \"${scriptFile.absolutePath}\"")
                            },
                            workingDirectory = projectDir,
                            timeoutSeconds = timeoutsProperties.mcp.joernToolTimeoutSeconds,
                        ),
                    )

                if (!processResult.isSuccess) {
                    return@withContext ToolResult.error(
                        "Joern callgraph analysis failed with exit code ${processResult.exitCode}: ${processResult.output}"
                    )
                }

                // Parse output from stdout (script prints results directly)
                val output = processResult.output

                // Extract summary, content, and results for unified response
                val methodCount = if (parsed.targetMethods.isNotEmpty()) {
                    parsed.targetMethods.size
                } else {
                    try {
                        val trimmed = output.trim()
                        if (trimmed.contains("\"results\"")) {
                            trimmed.split("\"method\"").size - 1
                        } else {
                            0
                        }
                    } catch (_: Exception) {
                        0
                    }
                }
                
                val summary = when {
                    parsed.targetMethods.isNotEmpty() -> "Analyzed ${parsed.targetMethods.size} target methods with depth ${parsed.analysisDepth}"
                    methodCount > 0 -> "Analyzed $methodCount methods with depth ${parsed.analysisDepth}"
                    else -> "Completed callgraph analysis with depth ${parsed.analysisDepth}"
                }
                
                val analysisParams = buildString {
                    if (parsed.targetMethods.isNotEmpty()) {
                        appendLine("Target Methods: ${parsed.targetMethods.joinToString(", ")}")
                    }
                    appendLine("Analysis Depth: ${parsed.analysisDepth}")
                    appendLine("Include External Calls: ${parsed.includeExternalCalls}")
                }.trim()
                
                val results = try {
                    val trimmed = output.trim()
                    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                        trimmed
                    } else {
                        output
                    }
                } catch (_: Exception) {
                    output
                }
                
                ToolResult.success(
                    toolName = "JOERN_CALLGRAPH",
                    summary = summary,
                    content = analysisParams,
                    results
                )
            } catch (e: Exception) {
                ToolResult.error("Joern callgraph execution failed: ${e.message}")
            }
        }
    }

    private fun generateCallgraphScript(params: CallgraphParams, cpgPath: java.nio.file.Path): String {
        return buildString {
            appendLine("importCpg(\"${cpgPath.toString()}\")")
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
                    appendLine("    \"callees\" -> method.callee.take(${params.analysisDepth}).filter(c => !c.fullName.startsWith(\"java.\") && !c.fullName.startsWith(\"scala.\")).map(c => Map(")
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
                    appendLine("val callgraphData = cpg.method.filter(m => !m.fullName.startsWith(\"java.\") && !m.fullName.startsWith(\"scala.\")).take(100).map { method =>")
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
    }

}