package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.mcp.util.ToolResponseBuilder
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

@Service
class CodeExtractorTool(
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    private val logger = KotlinLogging.logger {}
    override val name: PromptTypeEnum = PromptTypeEnum.CODE_EXTRACTOR

    @Serializable
    data class CodeExtractorParams(
        val filePath: String? = null,
        val className: String? = null,
        val methodName: String? = null,
        val searchPattern: String? = null,
        val includeImports: Boolean = true,
        val includeComments: Boolean = true,
        val signatureOnly: Boolean = false,
        val languageHint: String? = null,
        val contextFromSteps: Int = 3, // Number of previous steps to include in context
        val smartFiltering: Boolean = true, // Enable smart content filtering based on context
    )

    @Serializable
    data class SimpleCodeParams(
        val target: String, // File path, class name, method name, or search pattern
        val type: String = "file" // "file", "class", "method", or "pattern"
    )

    @Serializable
    data class CodeFragmentResult(
        val title: String,
        val className: String?,
        val methodName: String?,
        val file: String,
        val packageName: String?,
        val language: String,
        val content: String,
        val comment: String?,
        val tags: List<String> = emptyList(),
        val returnType: String? = null,
        val parameters: List<String> = emptyList(),
    )

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult =
        withContext(Dispatchers.IO) {
            logger.info { "CODE_EXTRACTOR_START: Starting code extraction for project: ${context.projectDocument.path}" }
            logger.debug { "CODE_EXTRACTOR_TASK: Task description: $taskDescription" }
            logger.debug { "CODE_EXTRACTOR_CONTEXT: Step context length: ${stepContext.length}" }
            
            try {
                // Validate stepBack context when stepBack > 0
                val contextValidation = validateStepBackContext(stepContext, taskDescription)
                if (contextValidation != null) {
                    logger.warn { "CODE_EXTRACTOR_VALIDATION_FAILED: Step context validation failed" }
                    return@withContext contextValidation
                }

                logger.debug { "CODE_EXTRACTOR_PARSING: Parsing task description into parameters" }
                val params = parseTaskDescription(taskDescription, context, plan, stepContext)
                logger.info { "CODE_EXTRACTOR_PARAMS: Parsed parameters - filePath=${params.filePath}, className=${params.className}, methodName=${params.methodName}, searchPattern=${params.searchPattern}, smartFiltering=${params.smartFiltering}" }
                
                val projectPath = Path.of(context.projectDocument.path)
                logger.debug { "CODE_EXTRACTOR_PROJECT: Project path resolved to: $projectPath" }

                if (!Files.exists(projectPath)) {
                    logger.error { "CODE_EXTRACTOR_PROJECT_NOT_FOUND: Project path does not exist: ${context.projectDocument.path}" }
                    return@withContext ToolResult.error("Project path does not exist: ${context.projectDocument.path}")
                }
                logger.debug { "CODE_EXTRACTOR_PROJECT_VALID: Project path exists and is accessible" }

                val results =
                    when {
                        params.filePath != null -> {
                            logger.info { "CODE_EXTRACTOR_MODE: Specific file extraction - ${params.filePath}" }
                            // Parse specific file
                            val filePath = resolveFilePath(projectPath, params.filePath)
                            logger.debug { "CODE_EXTRACTOR_FILE_PATH: Resolved file path to: $filePath" }
                            if (!Files.exists(filePath)) {
                                logger.error { "CODE_EXTRACTOR_FILE_NOT_FOUND: File does not exist: ${params.filePath} (resolved to: $filePath)" }
                                return@withContext ToolResult.error("File does not exist: ${params.filePath}")
                            }
                            logger.debug { "CODE_EXTRACTOR_FILE_VALID: File exists, starting extraction" }
                            val extracted = extractFromFile(filePath, params)
                            logger.info { "CODE_EXTRACTOR_FILE_EXTRACTED: Extracted ${extracted.size} fragments from file" }
                            if (params.smartFiltering) {
                                logger.debug { "CODE_EXTRACTOR_SMART_FILTERING: Applying smart filtering" }
                                applySmartFiltering(extracted, context, plan, params)
                            } else {
                                extracted
                            }
                        }

                        params.className != null && params.methodName != null -> {
                            logger.info { "CODE_EXTRACTOR_MODE: Method in class search - class: ${params.className}, method: ${params.methodName}" }
                            // Search for specific method in specific class
                            val extracted =
                                searchMethodInClass(projectPath, params.className, params.methodName, params)
                            logger.info { "CODE_EXTRACTOR_METHOD_CLASS_EXTRACTED: Found ${extracted.size} fragments for method ${params.methodName} in class ${params.className}" }
                            if (params.smartFiltering) {
                                logger.debug { "CODE_EXTRACTOR_SMART_FILTERING: Applying smart filtering" }
                                applySmartFiltering(extracted, context, plan, params)
                            } else {
                                extracted
                            }
                        }

                        params.className != null -> {
                            logger.info { "CODE_EXTRACTOR_MODE: Class search - ${params.className}" }
                            // Search for class by name
                            val extracted = searchClassByName(projectPath, params.className, params)
                            logger.info { "CODE_EXTRACTOR_CLASS_EXTRACTED: Found ${extracted.size} fragments for class ${params.className}" }
                            if (params.smartFiltering) {
                                logger.debug { "CODE_EXTRACTOR_SMART_FILTERING: Applying smart filtering" }
                                applySmartFiltering(extracted, context, plan, params)
                            } else {
                                extracted
                            }
                        }

                        params.methodName != null -> {
                            logger.info { "CODE_EXTRACTOR_MODE: Method search - ${params.methodName}" }
                            // Search for method by name
                            val extracted = searchMethodByName(projectPath, params.methodName, params)
                            logger.info { "CODE_EXTRACTOR_METHOD_EXTRACTED: Found ${extracted.size} fragments for method ${params.methodName}" }
                            if (params.smartFiltering) {
                                logger.debug { "CODE_EXTRACTOR_SMART_FILTERING: Applying smart filtering" }
                                applySmartFiltering(extracted, context, plan, params)
                            } else {
                                extracted
                            }
                        }

                        params.searchPattern != null -> {
                            logger.info { "CODE_EXTRACTOR_MODE: Pattern search - ${params.searchPattern}" }
                            // Search by pattern
                            val extracted = searchByPattern(projectPath, params.searchPattern, params)
                            logger.info { "CODE_EXTRACTOR_PATTERN_EXTRACTED: Found ${extracted.size} fragments for pattern ${params.searchPattern}" }
                            if (params.smartFiltering) {
                                logger.debug { "CODE_EXTRACTOR_SMART_FILTERING: Applying smart filtering" }
                                applySmartFiltering(extracted, context, plan, params)
                            } else {
                                extracted
                            }
                        }

                        else -> {
                            logger.info { "CODE_EXTRACTOR_MODE: Intelligent file discovery" }
                            // Intelligent file discovery - if no specific criteria provided, try to extract from context
                            val discoveredFiles = discoverRelevantFiles(projectPath, context, plan, params)
                            logger.info { "CODE_EXTRACTOR_DISCOVERY: Discovered ${discoveredFiles.size} potentially relevant files" }
                            if (discoveredFiles.isNotEmpty()) {
                                logger.debug { "CODE_EXTRACTOR_DISCOVERY_FILES: ${discoveredFiles.map { it.fileName }}" }
                                val extracted =
                                    discoveredFiles.flatMap { file ->
                                        try {
                                            logger.debug { "CODE_EXTRACTOR_DISCOVERY_EXTRACTING: Processing file ${file.fileName}" }
                                            extractFromFile(file, params)
                                        } catch (e: Exception) {
                                            logger.warn { "CODE_EXTRACTOR_DISCOVERY_ERROR: Failed to extract from ${file.fileName}: ${e.message}" }
                                            emptyList()
                                        }
                                    }
                                logger.info { "CODE_EXTRACTOR_DISCOVERY_EXTRACTED: Extracted ${extracted.size} fragments from discovered files" }
                                if (params.smartFiltering) {
                                    logger.debug { "CODE_EXTRACTOR_SMART_FILTERING: Applying smart filtering" }
                                    applySmartFiltering(extracted, context, plan, params)
                                } else {
                                    extracted
                                }
                            } else {
                                logger.error { "CODE_EXTRACTOR_DISCOVERY_FAILED: No relevant files could be automatically discovered and no specific criteria provided" }
                                return@withContext ToolResult.error(
                                    "Must specify either filePath, className, methodName, or searchPattern. No relevant files could be automatically discovered.",
                                )
                            }
                        }
                    }

                logger.info { "CODE_EXTRACTOR_RESULTS: Extraction completed with ${results.size} total fragments" }
                if (results.isEmpty()) {
                    logger.error { "CODE_EXTRACTOR_NO_RESULTS: No code fragments found matching the criteria. Parameters: filePath=${params.filePath}, className=${params.className}, methodName=${params.methodName}, searchPattern=${params.searchPattern}" }
                    ToolResult.error("CODE_EXTRACTOR_FAILED")
                } else {
                    val resultsContent = buildString {
                        results.forEachIndexed { index, result ->
                            appendLine("Fragment ${index + 1}: ${result.title}")
                            appendLine("File: ${result.file}")
                            appendLine("Language: ${result.language}")
                            if (result.className != null) appendLine("Class: ${result.className}")
                            if (result.methodName != null) appendLine("Method: ${result.methodName}")
                            if (result.packageName != null) appendLine("Package: ${result.packageName}")
                            if (result.returnType != null) appendLine("Return Type: ${result.returnType}")
                            if (result.parameters.isNotEmpty()) {
                                appendLine("Parameters: ${result.parameters.joinToString(", ")}")
                            }
                            if (result.comment != null) appendLine("Comment: ${result.comment}")
                            appendLine("Tags: ${result.tags.joinToString(", ")}")
                            appendLine()
                            appendLine(result.content)
                            if (index < results.size - 1) {
                                appendLine()
                                appendLine("---")
                                appendLine()
                            }
                        }
                    }
                    ToolResult.analysisResult(
                        "CODE_EXTRACTOR",
                        "code extraction",
                        results.size,
                        "fragment${if (results.size != 1) "s" else ""}",
                        results = resultsContent
                    )
                }
            } catch (e: SecurityException) {
                logger.error(e) { "CODE_EXTRACTOR_SECURITY_ERROR: Security exception during extraction - cannot access file or directory" }
                ToolResult.error(
                    "CODE_EXTRACTOR_ACCESS_DENIED: Cannot access file or directory. Check file permissions and project path configuration. Error: ${e.message}",
                )
            } catch (e: java.nio.file.NoSuchFileException) {
                logger.error(e) { "CODE_EXTRACTOR_FILE_NOT_FOUND_ERROR: File not found during extraction - ${e.file}" }
                ToolResult.error(
                    "CODE_EXTRACTOR_FILE_NOT_FOUND: Specified file does not exist: ${e.file}. Please verify the file path relative to project root or use searchPattern to find files.",
                )
            } catch (e: java.nio.file.AccessDeniedException) {
                logger.error(e) { "CODE_EXTRACTOR_ACCESS_DENIED_ERROR: Access denied to file - ${e.file}" }
                ToolResult.error("CODE_EXTRACTOR_ACCESS_DENIED: Permission denied accessing file: ${e.file}. Check file permissions.")
            } catch (e: java.io.IOException) {
                logger.error(e) { "CODE_EXTRACTOR_IO_ERROR: I/O error during file operations" }
                ToolResult.error("CODE_EXTRACTOR_IO_ERROR: File system error occurred: ${e.message}. Check if file is locked or corrupted.")
            } catch (e: kotlinx.serialization.SerializationException) {
                logger.error(e) { "CODE_EXTRACTOR_SERIALIZATION_ERROR: JSON parsing failed for task description" }
                ToolResult.error("CODE_EXTRACTOR_PARSING_ERROR: ${e.message}")
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "CODE_EXTRACTOR_INVALID_ARGS_ERROR: Invalid argument provided" }
                ToolResult.error("CODE_EXTRACTOR_INVALID_PARAMS: ${e.message}")
            } catch (e: Exception) {
                logger.error(e) { "CODE_EXTRACTOR_UNEXPECTED_ERROR: Unexpected error of type ${e.javaClass.simpleName}" }
                ToolResult.error("CODE_EXTRACTOR_UNKNOWN_ERROR: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        plan: Plan,
        stepContext: String = "",
    ): CodeExtractorParams {
        logger.debug { "CODE_EXTRACTOR_PARSE_START: Attempting to parse task description (${taskDescription.length} chars)" }
        try {
            val cleanedJson = taskDescription
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            logger.debug { "CODE_EXTRACTOR_PARSE_JSON: Trying direct JSON parsing of cleaned description" }
            val params = Json.decodeFromString<CodeExtractorParams>(cleanedJson)
            logger.debug { "CODE_EXTRACTOR_PARSE_SUCCESS: Successfully parsed JSON parameters directly" }
            return params
        } catch (e: Exception) {
            logger.debug { "CODE_EXTRACTOR_PARSE_JSON_FAILED: Direct JSON parsing failed, falling back to simplified parsing: ${e.message}" }
            // If direct JSON parsing fails, use simplified approach
            val simpleParams = generateSimpleCodeParams(taskDescription, context, plan, stepContext)
            logger.debug { "CODE_EXTRACTOR_PARSE_SIMPLE_SUCCESS: Successfully parsed using simplified approach" }
            
            // Convert SimpleCodeParams to CodeExtractorParams for backward compatibility
            return CodeExtractorParams(
                filePath = if (simpleParams.type == "file") simpleParams.target else null,
                className = if (simpleParams.type == "class") simpleParams.target else null,
                methodName = if (simpleParams.type == "method") simpleParams.target else null,
                searchPattern = if (simpleParams.type == "pattern") simpleParams.target else null,
                includeImports = true,
                includeComments = true,
                signatureOnly = false,
                languageHint = null,
                contextFromSteps = 3,
                smartFiltering = true
            )
        }
    }

    private suspend fun generateSimpleCodeParams(
        taskDescription: String,
        context: TaskContext,
        plan: Plan,
        stepContext: String = "",
    ): SimpleCodeParams {
        // Simple string-based parsing instead of complex JSON generation
        val parsePrompt = """
        Extract target from: "$taskDescription"
        Context: ${stepContext.take(200)}
        
        What to extract? Respond with target name only.
        Examples: "UserService", "authenticate", "config.yaml", "*.java"
        """.trimIndent()
        
        val target = llmGateway.callLlm(
            type = PromptTypeEnum.CODE_EXTRACTOR,
            userPrompt = parsePrompt,
            quick = context.quick,
            responseSchema = "",
            stepContext = stepContext,
        )
        
        // Determine type based on target content
        val type = when {
            target.contains(".") && !target.contains("*") -> "file"
            target.lowercase().contains("method") || target.lowercase().contains("function") -> "method"
            target.contains("*") || target.lowercase().contains("pattern") -> "pattern"
            else -> "class"
        }
        
        return SimpleCodeParams(target = target.trim(), type = type)
    }

    private suspend fun applySmartFiltering(
        fragments: List<CodeFragmentResult>,
        context: TaskContext,
        plan: Plan,
        params: CodeExtractorParams,
    ): List<CodeFragmentResult> {
        if (fragments.isEmpty() || !params.smartFiltering) {
            return fragments
        }

        // Get context from recent steps
        val recentSteps = plan.steps.takeLast(params.contextFromSteps)
        val contextKeywords = mutableSetOf<String>()

        // Extract keywords from step names and outputs
        recentSteps.forEach { step ->
            // Add step names as keywords
            step.name.split("_", " ").forEach { keyword ->
                if (keyword.length > 2) {
                    contextKeywords.add(keyword.lowercase())
                }
            }

            // Extract keywords from step outputs
            step.output?.let { output ->
                val outputText = output.output
                // Extract class names, method names, and other relevant terms
                val relevantTerms = extractRelevantTerms(outputText)
                contextKeywords.addAll(relevantTerms)
            }
        }

        // Add keywords from current task if available
        if (context.contextSummary.isNotEmpty()) {
            val taskKeywords = extractRelevantTerms(context.contextSummary)
            contextKeywords.addAll(taskKeywords)
        }

        // If no context keywords found, return all fragments
        if (contextKeywords.isEmpty()) {
            return fragments
        }

        // Score and filter fragments based on relevance
        return fragments
            .map { fragment -> fragment to calculateRelevanceScore(fragment, contextKeywords) }
            .filter { (_, score) -> score > 0 } // Only include fragments with some relevance
            .sortedByDescending { (_, score) -> score } // Sort by relevance
            .take(10) // Limit to top 10 most relevant fragments
            .map { (fragment, _) -> fragment }
    }

    private fun extractRelevantTerms(text: String): Set<String> {
        val terms = mutableSetOf<String>()

        // Extract capitalized words (likely class names)
        val capitalizedWords = Regex("[A-Z][a-zA-Z0-9]*").findAll(text)
        capitalizedWords.forEach { match ->
            terms.add(match.value.lowercase())
        }

        // Extract camelCase identifiers
        val camelCaseWords = Regex("[a-z]+[A-Z][a-zA-Z0-9]*").findAll(text)
        camelCaseWords.forEach { match ->
            terms.add(match.value.lowercase())
            // Split camelCase into individual words
            val splitWords = match.value.split(Regex("(?=[A-Z])"))
            splitWords.forEach { word ->
                if (word.length > 2) {
                    terms.add(word.lowercase())
                }
            }
        }

        // Extract quoted strings (potential file names, method names)
        val quotedStrings = Regex("\"([^\"]+)\"").findAll(text)
        quotedStrings.forEach { match ->
            val content = match.groupValues[1]
            if (content.length > 2) {
                terms.add(content.lowercase())
                // Also add without file extensions
                val withoutExt = content.substringBeforeLast(".")
                if (withoutExt != content) {
                    terms.add(withoutExt.lowercase())
                }
            }
        }

        return terms.filter { it.length > 2 }.toSet()
    }

    private fun calculateRelevanceScore(
        fragment: CodeFragmentResult,
        contextKeywords: Set<String>,
    ): Int {
        var score = 0

        // Check fragment title
        contextKeywords.forEach { keyword ->
            if (fragment.title.lowercase().contains(keyword)) {
                score += 5
            }
        }

        // Check class name
        fragment.className?.let { className ->
            contextKeywords.forEach { keyword ->
                if (className.lowercase().contains(keyword)) {
                    score += 4
                }
            }
        }

        // Check method name
        fragment.methodName?.let { methodName ->
            contextKeywords.forEach { keyword ->
                if (methodName.lowercase().contains(keyword)) {
                    score += 4
                }
            }
        }

        // Check tags
        fragment.tags.forEach { tag ->
            contextKeywords.forEach { keyword ->
                if (tag.contains(keyword)) {
                    score += 2
                }
            }
        }

        // Check content (less weight to avoid noise)
        val contentLower = fragment.content.lowercase()
        contextKeywords.forEach { keyword ->
            if (contentLower.contains(keyword)) {
                score += 1
            }
        }

        // Check file path for relevant directories/files
        val fileLower = fragment.file.lowercase()
        contextKeywords.forEach { keyword ->
            if (fileLower.contains(keyword)) {
                score += 2
            }
        }

        return score
    }

    private suspend fun discoverRelevantFiles(
        projectPath: Path,
        context: TaskContext,
        plan: Plan,
        params: CodeExtractorParams,
    ): List<Path> {
        // Get context from recent steps to identify relevant files
        val recentSteps = plan.steps.takeLast(params.contextFromSteps)
        val fileHints = mutableSetOf<String>()

        // Extract file names and patterns from step outputs
        recentSteps.forEach { step ->
            step.output?.let { output ->
                val outputText = output.output

                // Look for file paths in outputs
                val filePaths = Regex("\\b[\\w/.-]+\\.(kt|java|js|ts|py)\\b").findAll(outputText)
                filePaths.forEach { match ->
                    fileHints.add(match.value)
                }

                // Look for class names that might correspond to files
                val classNames = Regex("\\b[A-Z][a-zA-Z0-9]*\\b").findAll(outputText)
                classNames.forEach { match ->
                    fileHints.add("${match.value}.kt")
                    fileHints.add("${match.value}.java")
                }

                // Look for quoted strings that might be file names
                val quotedStrings = Regex("\"([^\"]*\\.(kt|java|js|ts|py))\"").findAll(outputText)
                quotedStrings.forEach { match ->
                    fileHints.add(match.groupValues[1])
                }
            }
        }

        // Also check context summary for file hints
        if (context.contextSummary.isNotEmpty()) {
            val summaryHints = extractFileHintsFromText(context.contextSummary)
            fileHints.addAll(summaryHints)
        }

        // Find actual files based on hints
        val discoveredFiles = mutableListOf<Path>()

        Files
            .walk(projectPath)
            .filter { it.toFile().isFile && isSupportedFile(it) }
            .forEach { file ->
                val fileName = file.fileName.toString()
                val relativePath = projectPath.relativize(file).toString()

                // Check if file matches any of the hints
                fileHints.forEach { hint ->
                    when {
                        fileName.equals(hint, ignoreCase = true) -> discoveredFiles.add(file)
                        relativePath.endsWith(hint, ignoreCase = true) -> discoveredFiles.add(file)
                        fileName.contains(hint.substringBeforeLast("."), ignoreCase = true) -> {
                            if (discoveredFiles.size < 5) discoveredFiles.add(file) // Limit fuzzy matches
                        }
                    }
                }
            }

        // If no specific files found, return main application files
        if (discoveredFiles.isEmpty()) {
            Files
                .walk(projectPath)
                .filter { it.toFile().isFile && isSupportedFile(it) }
                .filter { file ->
                    val fileName = file.fileName.toString().lowercase()
                    fileName.contains("main") ||
                        fileName.contains("application") ||
                        fileName.contains("app") ||
                        fileName.contains("service") ||
                        file.toString().contains("src/main/kotlin") ||
                        file.toString().contains("src/main/java")
                }.limit(10) // Limit to prevent overwhelming output
                .forEach { discoveredFiles.add(it) }
        }

        return discoveredFiles.distinct()
    }

    private fun extractFileHintsFromText(text: String): Set<String> {
        val hints = mutableSetOf<String>()

        // Extract file paths
        val filePaths = Regex("\\b[\\w/.-]+\\.(kt|java|js|ts|py)\\b").findAll(text)
        filePaths.forEach { match ->
            hints.add(match.value)
        }

        // Extract class names
        val classNames = Regex("\\b[A-Z][a-zA-Z0-9]*\\b").findAll(text)
        classNames.forEach { match ->
            hints.add("${match.value}.kt")
            hints.add("${match.value}.java")
        }

        return hints
    }

    private fun resolveFilePath(
        projectPath: Path,
        filePath: String,
    ): Path {
        val path = Path.of(filePath)
        return if (path.isAbsolute) {
            path
        } else {
            projectPath.resolve(filePath)
        }
    }

    private fun extractFromFile(
        filePath: Path,
        params: CodeExtractorParams,
    ): List<CodeFragmentResult> {
        val content = Files.readString(filePath)
        return when (val language = determineLanguage(filePath, params.languageHint)) {
            "kotlin", "java" -> parseKotlinJavaFile(content, filePath, params, language)
            "javascript", "typescript" -> parseJavaScriptTypeScriptFile(content, filePath, params, language)
            "python" -> parsePythonFile(content, filePath, params, language)
            else -> emptyList()
        }
    }

    private fun determineLanguage(
        filePath: Path,
        languageHint: String?,
    ): String {
        if (!languageHint.isNullOrBlank()) {
            return languageHint.lowercase()
        }

        return when (filePath.extension.lowercase()) {
            "kt" -> "kotlin"
            "java" -> "java"
            "js" -> "javascript"
            "ts" -> "typescript"
            "py" -> "python"
            else -> "unknown"
        }
    }

    private fun parseKotlinJavaFile(
        content: String,
        filePath: Path,
        params: CodeExtractorParams,
        language: String,
    ): List<CodeFragmentResult> {
        val lines = content.lines()
        val packageName = extractPackageName(lines)
        val results = mutableListOf<CodeFragmentResult>()

        // Extract classes
        val classRegex =
            if (language == "kotlin") {
                Regex("^\\s*(class|interface|object|data class|sealed class|enum class)\\s+([A-Za-z_][A-Za-z0-9_]*)")
            } else {
                Regex("^\\s*(public|private|protected|abstract|final)?\\s*(class|interface|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)")
            }

        lines.forEachIndexed { index, line ->
            val classMatch = classRegex.find(line.trim())
            if (classMatch != null) {
                val className = if (language == "kotlin") classMatch.groupValues[2] else classMatch.groupValues[3]
                val classContent =
                    if (params.signatureOnly) {
                        extractClassSignature(lines, index, params.includeComments, language)
                    } else {
                        extractClassContent(lines, index, params.includeComments, language)
                    }

                results.add(
                    CodeFragmentResult(
                        title = className,
                        className = className,
                        methodName = null,
                        file = filePath.toString(),
                        packageName = packageName,
                        language = language,
                        content = classContent,
                        comment = extractCommentAbove(lines, index),
                        tags = listOf("class", className.lowercase()),
                    ),
                )

                // Extract methods within the class if not signature only
                if (!params.signatureOnly) {
                    results.addAll(
                        extractMethodsFromClass(
                            lines,
                            index,
                            className,
                            packageName,
                            filePath,
                            params,
                            language,
                        ),
                    )
                }
            }
        }

        return results
    }

    private fun extractMethodsFromClass(
        lines: List<String>,
        classStartIndex: Int,
        className: String,
        packageName: String?,
        filePath: Path,
        params: CodeExtractorParams,
        language: String,
    ): List<CodeFragmentResult> {
        val results = mutableListOf<CodeFragmentResult>()
        val methodRegex =
            if (language == "kotlin") {
                Regex("^\\s*(suspend\\s+)?(fun)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
            } else {
                Regex("^\\s*(public|private|protected|static)?\\s*([A-Za-z_][A-Za-z0-9_<>\\[\\]]+)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
            }

        val classEndIndex = findClassEnd(lines, classStartIndex, language)

        for (i in classStartIndex + 1 until classEndIndex) {
            val methodMatch = methodRegex.find(lines[i].trim())
            if (methodMatch != null) {
                val methodName = if (language == "kotlin") methodMatch.groupValues[3] else methodMatch.groupValues[3]
                val methodContent =
                    if (params.signatureOnly) {
                        extractMethodSignature(lines, i, params.includeComments, language)
                    } else {
                        extractMethodContent(lines, i, params.includeComments, language)
                    }

                val methodInfo = extractMethodInfo(lines, i, language)

                results.add(
                    CodeFragmentResult(
                        title = "$className.$methodName",
                        className = className,
                        methodName = methodName,
                        file = filePath.toString(),
                        packageName = packageName,
                        language = language,
                        content = methodContent,
                        comment = extractCommentAbove(lines, i),
                        tags = listOf("method", methodName.lowercase(), className.lowercase()),
                        returnType = methodInfo.returnType,
                        parameters = methodInfo.parameters,
                    ),
                )
            }
        }

        return results
    }

    private fun parseJavaScriptTypeScriptFile(
        content: String,
        filePath: Path,
        params: CodeExtractorParams,
        language: String,
    ): List<CodeFragmentResult> {
        val lines = content.lines()
        val results = mutableListOf<CodeFragmentResult>()

        val functionRegex = Regex("^\\s*(export\\s+)?(async\\s+)?function\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
        val classRegex = Regex("^\\s*(export\\s+)?(class)\\s+([A-Za-z_][A-Za-z0-9_]*)")

        lines.forEachIndexed { index, line ->
            val functionMatch = functionRegex.find(line)
            val classMatch = classRegex.find(line)

            when {
                functionMatch != null -> {
                    val functionName = functionMatch.groupValues[3]
                    val content = if (params.signatureOnly) line else extractJSFunctionContent(lines, index)
                    results.add(
                        CodeFragmentResult(
                            title = functionName,
                            className = null,
                            methodName = functionName,
                            file = filePath.toString(),
                            packageName = null,
                            language = language,
                            content = content,
                            comment = extractCommentAbove(lines, index),
                            tags = listOf("function", functionName.lowercase()),
                            parameters = extractJSParameters(line),
                        ),
                    )
                }

                classMatch != null -> {
                    val className = classMatch.groupValues[3]
                    val content = if (params.signatureOnly) line else extractJSClassContent(lines, index)
                    results.add(
                        CodeFragmentResult(
                            title = className,
                            className = className,
                            methodName = null,
                            file = filePath.toString(),
                            packageName = null,
                            language = language,
                            content = content,
                            comment = extractCommentAbove(lines, index),
                            tags = listOf("class", className.lowercase()),
                        ),
                    )
                }
            }
        }

        return results
    }

    private fun parsePythonFile(
        content: String,
        filePath: Path,
        params: CodeExtractorParams,
        language: String,
    ): List<CodeFragmentResult> {
        val lines = content.lines()
        val results = mutableListOf<CodeFragmentResult>()

        val classRegex = Regex("^\\s*class\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val functionRegex = Regex("^\\s*def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")

        lines.forEachIndexed { index, line ->
            val classMatch = classRegex.find(line)
            val functionMatch = functionRegex.find(line)

            when {
                classMatch != null -> {
                    val className = classMatch.groupValues[1]
                    val content = if (params.signatureOnly) line else extractPythonClassContent(lines, index)
                    results.add(
                        CodeFragmentResult(
                            title = className,
                            className = className,
                            methodName = null,
                            file = filePath.toString(),
                            packageName = null,
                            language = language,
                            content = content,
                            comment = extractCommentAbove(lines, index),
                            tags = listOf("class", className.lowercase()),
                        ),
                    )
                }

                functionMatch != null -> {
                    val functionName = functionMatch.groupValues[1]
                    val content = if (params.signatureOnly) line else extractPythonFunctionContent(lines, index)
                    results.add(
                        CodeFragmentResult(
                            title = functionName,
                            className = null,
                            methodName = functionName,
                            file = filePath.toString(),
                            packageName = null,
                            language = language,
                            content = content,
                            comment = extractCommentAbove(lines, index),
                            tags = listOf("function", functionName.lowercase()),
                            parameters = extractPythonParameters(line),
                        ),
                    )
                }
            }
        }

        return results
    }

    private fun searchClassByName(
        projectPath: Path,
        className: String,
        params: CodeExtractorParams,
    ): List<CodeFragmentResult> {
        val results = mutableListOf<CodeFragmentResult>()
        Files
            .walk(projectPath)
            .filter { it.toFile().isFile && isSupportedFile(it) }
            .forEach { file ->
                try {
                    val fragments = extractFromFile(file, params)
                    results.addAll(
                        fragments.filter {
                            it.className?.contains(className, ignoreCase = true) == true
                        },
                    )
                } catch (e: Exception) {
                    // Skip files that can't be processed
                }
            }
        return results
    }

    private fun searchMethodByName(
        projectPath: Path,
        methodName: String,
        params: CodeExtractorParams,
    ): List<CodeFragmentResult> {
        val results = mutableListOf<CodeFragmentResult>()
        Files
            .walk(projectPath)
            .filter { it.toFile().isFile && isSupportedFile(it) }
            .forEach { file ->
                try {
                    val fragments = extractFromFile(file, params)
                    results.addAll(
                        fragments.filter {
                            it.methodName?.contains(methodName, ignoreCase = true) == true
                        },
                    )
                } catch (e: Exception) {
                    // Skip files that can't be processed
                }
            }
        return results
    }

    private fun searchMethodInClass(
        projectPath: Path,
        className: String,
        methodName: String,
        params: CodeExtractorParams,
    ): List<CodeFragmentResult> {
        val results = mutableListOf<CodeFragmentResult>()
        Files
            .walk(projectPath)
            .filter { it.toFile().isFile && isSupportedFile(it) }
            .forEach { file ->
                try {
                    val fragments = extractFromFile(file, params)
                    results.addAll(
                        fragments.filter {
                            it.className?.contains(className, ignoreCase = true) == true &&
                                it.methodName?.contains(methodName, ignoreCase = true) == true
                        },
                    )
                } catch (e: Exception) {
                    // Skip files that can't be processed
                }
            }
        return results
    }

    private fun searchByPattern(
        projectPath: Path,
        pattern: String,
        params: CodeExtractorParams,
    ): List<CodeFragmentResult> {
        logger.debug { "CODE_EXTRACTOR_PATTERN_SEARCH_START: Starting pattern search for: '$pattern'" }
        val results = mutableListOf<CodeFragmentResult>()
        val processedFiles = mutableSetOf<Path>()

        // Enhanced pattern matching with multiple strategies
        val regex = try {
            // First try to use pattern as-is (it might already be a valid regex like ".*Authorization.*")
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            logger.debug { "CODE_EXTRACTOR_REGEX_SUCCESS: Pattern '$pattern' used as-is as valid regex" }
            regex
        } catch (e: Exception) {
            logger.debug { "CODE_EXTRACTOR_REGEX_FALLBACK: Pattern '$pattern' is not valid regex, trying fallback strategies: ${e.message}" }
            // If pattern is not a valid regex, check if it contains wildcards for simple substitution
            val wildcard = pattern.contains("*")
            if (wildcard) {
                try {
                    // Only apply wildcard replacement if pattern is not already a valid regex
                    val wildcardPattern = pattern.replace("*", ".*")
                    val regex = Regex(wildcardPattern, RegexOption.IGNORE_CASE)
                    logger.debug { "CODE_EXTRACTOR_REGEX_WILDCARD: Applied wildcard replacement: '$pattern' -> '$wildcardPattern'" }
                    regex
                } catch (e2: Exception) {
                    // Fallback to escaped literal pattern
                    val escapedPattern = Regex.escape(pattern)
                    val regex = Regex(escapedPattern, RegexOption.IGNORE_CASE)
                    logger.debug { "CODE_EXTRACTOR_REGEX_ESCAPED: Used escaped literal pattern: '$pattern' -> '$escapedPattern'" }
                    regex
                }
            } else {
                // No wildcards, treat as literal string
                val escapedPattern = Regex.escape(pattern)
                val regex = Regex(escapedPattern, RegexOption.IGNORE_CASE)
                logger.debug { "CODE_EXTRACTOR_REGEX_LITERAL: Used escaped literal pattern (no wildcards): '$pattern' -> '$escapedPattern'" }
                regex
            }
        }

        logger.debug { "CODE_EXTRACTOR_FILE_WALK: Starting to walk project directory for supported files" }
        var totalFiles = 0
        var processedFilesCount = 0
        var failedFiles = 0
        var totalFragments = 0
        var matchingFragments = 0

        Files
            .walk(projectPath)
            .filter { it.toFile().isFile && isSupportedFile(it) }
            .forEach { file ->
                totalFiles++
                if (processedFiles.add(file)) {
                    processedFilesCount++
                    try {
                        logger.debug { "CODE_EXTRACTOR_FILE_PROCESSING: Processing file ${file.fileName}" }
                        val fragments = extractFromFile(file, params)
                        totalFragments += fragments.size
                        val matchingInFile = fragments.filter { fragment ->
                            matchesPattern(fragment, pattern, regex)
                        }
                        matchingFragments += matchingInFile.size
                        if (matchingInFile.isNotEmpty()) {
                            logger.debug { "CODE_EXTRACTOR_FILE_MATCHES: Found ${matchingInFile.size} matching fragments in ${file.fileName}" }
                        }
                        results.addAll(matchingInFile)
                    } catch (e: Exception) {
                        failedFiles++
                        logger.warn { "CODE_EXTRACTOR_FILE_ERROR: Failed to process file ${file.fileName}: ${e.message}" }
                    }
                }
            }

        logger.info { "CODE_EXTRACTOR_PATTERN_STATS: Pattern '$pattern' - processed $processedFilesCount/$totalFiles files, extracted $totalFragments total fragments, found $matchingFragments matches, $failedFiles failures" }

        // If no results found, try broader search with individual pattern terms
        if (results.isEmpty() && pattern.contains(" ")) {
            logger.debug { "CODE_EXTRACTOR_PATTERN_FALLBACK: No results for full pattern '$pattern', trying individual terms" }
            val terms = pattern.split(" ").filter { it.length > 2 }
            logger.debug { "CODE_EXTRACTOR_PATTERN_TERMS: Searching for individual terms: ${terms.joinToString(", ")}" }
            terms.forEach { term ->
                val termResults = searchByPattern(projectPath, term, params)
                logger.debug { "CODE_EXTRACTOR_PATTERN_TERM_RESULTS: Term '$term' found ${termResults.size} results" }
                results.addAll(termResults)
            }
        }

        val distinctResults = results.distinct()
        logger.info { "CODE_EXTRACTOR_PATTERN_COMPLETE: Pattern search for '$pattern' completed with ${distinctResults.size} unique results (${results.size} total before deduplication)" }
        return distinctResults
    }

    private fun matchesPattern(
        fragment: CodeFragmentResult,
        pattern: String,
        regex: Regex,
    ): Boolean {
        // Direct string matching (most common case)
        if (fragment.content.contains(pattern, ignoreCase = true)) return true
        if (fragment.className?.contains(pattern, ignoreCase = true) == true) return true
        if (fragment.methodName?.contains(pattern, ignoreCase = true) == true) return true
        if (fragment.tags.any { it.contains(pattern, ignoreCase = true) }) return true

        // Regex matching for wildcards
        if (pattern.contains("*")) {
            if (regex.containsMatchIn(fragment.content)) return true
            if (fragment.className?.let { regex.containsMatchIn(it) } == true) return true
            if (fragment.methodName?.let { regex.containsMatchIn(it) } == true) return true
            if (fragment.tags.any { regex.containsMatchIn(it) }) return true
        }

        // File path matching
        if (fragment.file.contains(pattern, ignoreCase = true)) return true

        // Package name matching
        if (fragment.packageName?.contains(pattern, ignoreCase = true) == true) return true

        // Comment matching
        if (fragment.comment?.contains(pattern, ignoreCase = true) == true) return true

        return false
    }

    private fun isSupportedFile(path: Path): Boolean {
        val extension = path.extension.lowercase()
        return extension in setOf("kt", "java", "js", "ts", "py")
    }

    // Helper methods for parsing
    private fun extractPackageName(lines: List<String>): String? =
        lines
            .find { it.trim().startsWith("package ") }
            ?.substringAfter("package ")
            ?.substringBefore(";")
            ?.trim()

    private fun extractCommentAbove(
        lines: List<String>,
        lineIndex: Int,
    ): String? {
        val comments = mutableListOf<String>()
        var i = lineIndex - 1

        while (i >= 0) {
            val line = lines[i].trim()
            when {
                line.startsWith("//") -> comments.add(0, line.removePrefix("//").trim())
                line.startsWith("/*") || line.startsWith("*") || line.endsWith("*/") -> {
                    comments.add(0, line.replace(Regex("/\\*|\\*/|^\\s*\\*"), "").trim())
                }

                line.startsWith("#") -> comments.add(0, line.removePrefix("#").trim()) // Python comments
                line.isEmpty() -> {
                    // Skip empty lines
                }

                else -> break
            }
            i--
        }

        return if (comments.isNotEmpty()) comments.joinToString(" ") else null
    }

    private fun extractClassSignature(
        lines: List<String>,
        startIndex: Int,
        includeComments: Boolean,
        language: String,
    ): String = lines[startIndex]

    private fun extractClassContent(
        lines: List<String>,
        startIndex: Int,
        includeComments: Boolean,
        language: String,
    ): String {
        val endIndex = findClassEnd(lines, startIndex, language)
        val classLines = lines.subList(startIndex, minOf(endIndex + 1, lines.size))

        return if (includeComments) {
            classLines.joinToString("\n")
        } else {
            classLines
                .filter { line ->
                    val trimmed = line.trim()
                    !trimmed.startsWith("//") &&
                        !trimmed.startsWith("/*") &&
                        !trimmed.startsWith("*") &&
                        !trimmed.endsWith("*/") &&
                        !trimmed.startsWith("#")
                }.joinToString("\n")
        }
    }

    private fun extractMethodSignature(
        lines: List<String>,
        startIndex: Int,
        includeComments: Boolean,
        language: String,
    ): String = lines[startIndex]

    private fun extractMethodContent(
        lines: List<String>,
        startIndex: Int,
        includeComments: Boolean,
        language: String,
    ): String {
        val endIndex = findMethodEnd(lines, startIndex, language)
        val methodLines = lines.subList(startIndex, minOf(endIndex + 1, lines.size))

        return if (includeComments) {
            methodLines.joinToString("\n")
        } else {
            methodLines
                .filter { line ->
                    val trimmed = line.trim()
                    !trimmed.startsWith("//") &&
                        !trimmed.startsWith("/*") &&
                        !trimmed.startsWith("*") &&
                        !trimmed.endsWith("*/") &&
                        !trimmed.startsWith("#")
                }.joinToString("\n")
        }
    }

    private fun findClassEnd(
        lines: List<String>,
        startIndex: Int,
        language: String,
    ): Int {
        when (language) {
            "python" -> return findPythonBlockEnd(lines, startIndex)
            else -> {
                var braceCount = 0
                var inClass = false

                for (i in startIndex until lines.size) {
                    val line = lines[i]
                    for (char in line) {
                        when (char) {
                            '{' -> {
                                braceCount++
                                inClass = true
                            }

                            '}' -> {
                                braceCount--
                                if (inClass && braceCount == 0) {
                                    return i
                                }
                            }
                        }
                    }
                }
                return lines.size - 1
            }
        }
    }

    private fun findMethodEnd(
        lines: List<String>,
        startIndex: Int,
        language: String,
    ): Int {
        when (language) {
            "python" -> return findPythonBlockEnd(lines, startIndex)
            else -> {
                var braceCount = 0
                var inMethod = false

                for (i in startIndex until lines.size) {
                    val line = lines[i]
                    for (char in line) {
                        when (char) {
                            '{' -> {
                                braceCount++
                                inMethod = true
                            }

                            '}' -> {
                                braceCount--
                                if (inMethod && braceCount == 0) {
                                    return i
                                }
                            }
                        }
                    }
                }
                return lines.size - 1
            }
        }
    }

    private fun findPythonBlockEnd(
        lines: List<String>,
        startIndex: Int,
    ): Int {
        val baseIndentation = lines[startIndex].takeWhile { it.isWhitespace() }.length
        for (i in startIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.isNotBlank()) {
                val currentIndentation = line.takeWhile { it.isWhitespace() }.length
                if (currentIndentation <= baseIndentation) {
                    return i - 1
                }
            }
        }
        return lines.size - 1
    }

    private data class MethodInfo(
        val returnType: String?,
        val parameters: List<String>,
    )

    private fun extractMethodInfo(
        lines: List<String>,
        startIndex: Int,
        language: String,
    ): MethodInfo {
        val methodLine = lines[startIndex].trim()

        return when (language) {
            "kotlin" -> {
                val returnType =
                    if (methodLine.contains(":")) {
                        methodLine
                            .substringAfter(":")
                            .substringBefore("=")
                            .substringBefore("{")
                            .trim()
                    } else {
                        null
                    }
                val params = extractParameters(methodLine)
                MethodInfo(returnType, params)
            }

            "java" -> {
                val parts = methodLine.split("\\s+".toRegex())
                val returnType = parts.findLast { it.contains("(") }?.substringBefore("(") ?: "void"
                val params = extractParameters(methodLine)
                MethodInfo(returnType, params)
            }

            else -> MethodInfo(null, extractParameters(methodLine))
        }
    }

    private fun extractParameters(methodLine: String): List<String> {
        val paramSection = methodLine.substringAfter("(").substringBefore(")")
        return if (paramSection.isBlank()) {
            emptyList()
        } else {
            paramSection.split(",").map { it.trim() }
        }
    }

    private fun extractJSFunctionContent(
        lines: List<String>,
        startIndex: Int,
    ): String = extractJSBlockContent(lines, startIndex)

    private fun extractJSClassContent(
        lines: List<String>,
        startIndex: Int,
    ): String = extractJSBlockContent(lines, startIndex)

    private fun extractJSBlockContent(
        lines: List<String>,
        startIndex: Int,
    ): String {
        var braceCount = 0
        var inBlock = false
        val endIndex =
            run {
                for (i in startIndex until lines.size) {
                    val line = lines[i]
                    for (char in line) {
                        when (char) {
                            '{' -> {
                                braceCount++
                                inBlock = true
                            }

                            '}' -> {
                                braceCount--
                                if (inBlock && braceCount == 0) {
                                    return@run i
                                }
                            }
                        }
                    }
                }
                lines.size - 1
            }
        return lines.subList(startIndex, minOf(endIndex + 1, lines.size)).joinToString("\n")
    }

    private fun extractJSParameters(line: String): List<String> = extractParameters(line)

    private fun extractPythonClassContent(
        lines: List<String>,
        startIndex: Int,
    ): String {
        val endIndex = findPythonBlockEnd(lines, startIndex)
        return lines.subList(startIndex, minOf(endIndex + 1, lines.size)).joinToString("\n")
    }

    private fun extractPythonFunctionContent(
        lines: List<String>,
        startIndex: Int,
    ): String {
        val endIndex = findPythonBlockEnd(lines, startIndex)
        return lines.subList(startIndex, minOf(endIndex + 1, lines.size)).joinToString("\n")
    }

    private fun extractPythonParameters(line: String): List<String> = extractParameters(line)

    /**
     * Validates stepBack context to ensure previous steps provided usable information.
     * Returns error ToolResult with alternative suggestions when context is insufficient.
     * Returns null when context is valid and extraction can proceed.
     */
    private fun validateStepBackContext(stepContext: String, taskDescription: String): ToolResult? {
        if (stepContext.isBlank()) {
            // No stepContext provided - this is fine for stepBack=0 steps
            return null
        }

        val contextLower = stepContext.lowercase()
        
        // Check for indicators of failed or empty previous steps
        val failureIndicators = listOf(
            "no relevant results",
            "no results found", 
            "no matching",
            "not found",
            "failed to",
            "error:",
            "exception:",
            "empty result"
        )

        val hasFailureIndicators = failureIndicators.any { indicator -> 
            contextLower.contains(indicator)
        }

        if (hasFailureIndicators) {
            return ToolResult.error("CODE_EXTRACTOR_INSUFFICIENT_CONTEXT")
        }

        // Check if stepContext contains actionable information (file paths, class names, method names)
        val actionablePatterns = listOf(
            Regex("\\w+\\.\\w+"),  // file.extension
            Regex("class\\s+\\w+", RegexOption.IGNORE_CASE),
            Regex("method\\s+\\w+", RegexOption.IGNORE_CASE),
            Regex("function\\s+\\w+", RegexOption.IGNORE_CASE),
            Regex("/\\w+"),  // file paths
            Regex("\\w+Controller", RegexOption.IGNORE_CASE),
            Regex("\\w+Service", RegexOption.IGNORE_CASE),
            Regex("\\w+Repository", RegexOption.IGNORE_CASE),
            Regex("├──|└──"),  // file tree structure from FILE_LISTING
            Regex("PROJECT FILE TREE", RegexOption.IGNORE_CASE),
            Regex("Root:"),  // file listing root indicator
            Regex("\\w+/\\w+"),  // directory paths
            Regex("\\.\\w{2,4}\\s"),  // file extensions followed by space
            Regex("\\w+\\.kt|\\w+\\.java|\\w+\\.js|\\w+\\.py", RegexOption.IGNORE_CASE)  // common source files
        )

        val hasActionableContent = actionablePatterns.any { pattern ->
            pattern.containsMatchIn(stepContext)
        }

        // Only reject if context is very short AND has no actionable content AND appears to be an error message
        if (!hasActionableContent && stepContext.length < 20 && contextLower.contains("error")) {
            return ToolResult.error("CODE_EXTRACTOR_INSUFFICIENT_CONTEXT")
        }

        // Context validation passed
        return null
    }
}
