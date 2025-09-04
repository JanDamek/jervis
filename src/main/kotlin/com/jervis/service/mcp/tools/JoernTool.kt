package com.jervis.service.mcp.tools

import com.jervis.configuration.TimeoutsProperties
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.service.analysis.JoernAnalysisService
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.mcp.util.McpFinalPromptProcessor
import com.jervis.util.ProcessStreamingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.io.File
import kotlin.io.path.pathString

@Service
class JoernTool(
    private val llmGateway: LlmGateway,
    private val mcpFinalPromptProcessor: McpFinalPromptProcessor,
    private val joernAnalysisService: JoernAnalysisService,
    private val timeoutsProperties: TimeoutsProperties,
) : McpTool {
    override val name: String = "joern"
    override val description: String =
        """
Use this tool to generate terminal-executable static analysis scripts for Joern in headless mode.
Joern builds a Code Property Graph (CPG) from the source code and allows powerful queries and scans over it. This tool translates natural language descriptions and be executed as isolated processes.
Supported use cases include:
  • Security scans (e.g. buffer overflows, eval injection, unsafe memory use)
  • Targeted queries (e.g. find all `eval()` calls, input reaching `system()`)
  • Structural code analysis (e.g. call graphs, control flow, complexity)
  • Graph metadata (e.g. node types, edge types, schema)
Use this tool for vulnerability research, automated code review, or post-processing by an LLM agent.
"""

    @Serializable
    data class JoernParams(
        val operations: List<JoernQuery>,
        val finalPrompt: String? = null,
    )

    @Serializable
    data class JoernQuery(
        val scriptContent: String,
        val scriptFilename: String,
        val terminalCommand: String,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
    ): JoernParams {
        val systemPrompt =
            """
You are a Joern static analysis resolver.
Your job is to convert a natural-language description of a static analysis task into one or more executable operations using Joern in headless script mode.
Each operation must return the following JSON fields:
{
  "scriptContent": "<Scala-based .sc script executed by Joern>",
  "scriptFilename": "<Name of the .sc script file (e.g., 'op-001-detect-lang.sc')>",
  "terminalCommand": "<Shell command that executes the script on macOS>"
}
────────────────────────────
SYSTEM RULES AND CONSTRAINTS:
────────────────────────────
• Do NOT use the interactive shell (`joern`) or any REPL-based commands.
• Only generate `.sc` scripts that can be executed via the `--script` flag.
• Each script must:
  - Be a valid Scala script with a single `@main def run(cpgFile: String, outFile: String)` function
  - Import the CPG using: `importCpg(cpgFile)`
  - Execute a Joern DSL traversal that ends with `.toJson` or `.toJsonPretty`
  - Redirect the output to the output file using `#> outFile`
• Each terminal command must:
  - Use `joern --script "<scriptPath>" --param cpgFile="<path>" --param outFile="<path>"`
  - Wrap all paths in double quotes
  - Log output using `2>&1 | tee "<logFile>"`
────────────────────────────
SUPPORTED ANALYSIS TASK TYPES:
────────────────────────────
Your output should support the following task intents:
1. Vulnerability scan
   - Detect unsafe function calls (e.g., `eval()`, `gets()`, `strcpy()`, etc.)
   - Search for hardcoded secrets, credentials, insecure hashing
   - Buffer overflows, format strings, unsafe pointer usage
   - Scan for command injections or user-controlled input reaching sinks
2. Custom queries
   - Show all calls to a given function (e.g., `eval`)
   - Show methods that call other methods
   - Display incoming/outgoing calls from specific nodes
   - Extract identifier names, constants, assignments
3. Structural analysis
   - Call graph
   - Control flow graph
   - Function nesting or complexity
   - Method resolution
4. Data flow (if supported)
   - Track user input through code
   - Show taint paths to dangerous sinks
   - Custom sources and sinks queries
5. Metadata and schema
   - List CPG node types
   - List edge types
   - Dump schema or project stats
────────────────────────────
RESPONSE FORMAT:
────────────────────────────
You must return only a valid JSON object with this structure:
{
  "operations": [
    {
      "scriptContent": "FULL .sc SCRIPT WITH @main",
      "scriptFilename": "SCRIPT FILENAME (e.g., 'op-001-detect-lang.sc')",
      "terminalCommand": "FULL SHELL COMMAND TO EXECUTE"
    }
  ],
  "finalPrompt": "OPTIONAL SYSTEM PROMPT FOR LLM POST-PROCESSING OR null"
}
- Multiple operations are allowed.
- The `finalPrompt` is used to summarize, analyze, cluster or validate results. Use it if post-processing is appropriate.
- The combined outputs from all operations will be passed as the userPrompt to the post-processor LLM.
────────────────────────────
TERMINAL EXECUTION CONTEXT:
────────────────────────────
• The working directory contains `.jervis/cpg.bin` which is the input CPG file.
• All output files should be placed in `.jervis/`, such as:
    - script: `.jervis/op-001-eval-calls.sc`
    - output: `.jervis/op-001-eval-calls.json`
    - log: `.jervis/op-001-eval-calls.run.log`
• Joern is already installed and available in PATH.
────────────────────────────
EXAMPLES:
────────────────────────────
Example task: "Find all uses of `eval()` and show the code + line number"
→ operations[0].scriptContent:
@main def run(cpgFile: String, outFile: String): Unit =
  importCpg(cpgFile)
  cpg.call.name("eval").map(x => Map(
    "code" -> x.code,
    "file" -> x.file.name,
    "line" -> x.lineNumber
  )).toJsonPretty #> outFile
→ operations[0].scriptFilename:
op-001-eval-calls.sc
→ operations[0].terminalCommand:
joern --script ".jervis/op-001-eval-calls.sc" \
      --param cpgFile=".jervis/cpg.bin" \
      --param outFile=".jervis/op-001-eval-calls.json" \
      2>&1 | tee ".jervis/op-001-eval-calls.run.log"
────────────────────────────
QUALITY CONTROL CHECKLIST:
────────────────────────────
scriptContent contains a valid Scala `@main` method with `importCpg(cpgFile)`
Query ends with `.toJsonPretty #> outFile`
terminalCommand invokes `joern --script` with quoted --param paths
finalPrompt is present if summarization or post-validation is needed
No markdown, no explanations, no shell commands outside JSON
Return ONLY the required JSON object as shown above.
            """.trimIndent()
        val llmResponse =
            llmGateway.callLlm(
                type = ModelType.INTERNAL,
                systemPrompt = systemPrompt,
                userPrompt = taskDescription,
                outputLanguage = "en",
                quick = context.quick,
            )

        val cleanedResponse =
            llmResponse.answer
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

        return Json.decodeFromString<JoernParams>(cleanedResponse)
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

        val parsed = parseTaskDescription(taskDescription, context)

        if (parsed.operations.isEmpty()) {
            return ToolResult.error("Joern operations cannot be empty")
        }

        val result =
            withContext(Dispatchers.IO) {
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
                            appendLine("Joern Analysis Results")
                            appendLine("Executed ${parsed.operations.size} operation(s)")
                            appendLine("Working Directory: ${joernDir.pathString}")
                            appendLine()
                            outputs.forEachIndexed { idx, output ->
                                appendLine("Operation ${idx + 1} Output:")
                                appendLine("```json")
                                appendLine(formatJoernOutput(output))
                                appendLine("```")
                                appendLine()
                            }
                            appendLine("Analysis completed successfully. Results can be used for further code analysis or security review.")
                        }

                    ToolResult.ok(enhancedOutput)
                } catch (e: Exception) {
                    ToolResult.error("Joern execution failed: ${e.message}")
                }
            }

        // Process through LLM if finalPrompt is provided and result is successful
        return mcpFinalPromptProcessor.processFinalPrompt(
            finalPrompt = parsed.finalPrompt,
            systemPrompt = mcpFinalPromptProcessor.createJoernSystemPrompt(),
            originalResult = result,
            context = context,
        )
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
