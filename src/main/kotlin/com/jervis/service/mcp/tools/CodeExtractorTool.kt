package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

@Service
class CodeExtractorTool(
    private val promptRepository: PromptRepository,
) : McpTool {
    override val name: String = "code.extractor"

    override val description: String
        get() = promptRepository.getMcpToolDescription(PromptTypeEnum.CODE_EXTRACTOR)

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
    ): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val params = parseTaskDescription(taskDescription)
                val projectPath = Path.of(context.projectDocument.path)

                if (!Files.exists(projectPath)) {
                    return@withContext ToolResult.error("Project path does not exist: ${context.projectDocument.path}")
                }

                val results =
                    when {
                        params.filePath != null -> {
                            // Parse specific file
                            val filePath = resolveFilePath(projectPath, params.filePath)
                            if (!Files.exists(filePath)) {
                                return@withContext ToolResult.error("File does not exist: ${params.filePath}")
                            }
                            extractFromFile(filePath, params)
                        }

                        params.className != null && params.methodName != null -> {
                            // Search for specific method in specific class
                            searchMethodInClass(projectPath, params.className, params.methodName, params)
                        }

                        params.className != null -> {
                            // Search for class by name
                            searchClassByName(projectPath, params.className, params)
                        }

                        params.methodName != null -> {
                            // Search for method by name
                            searchMethodByName(projectPath, params.methodName, params)
                        }

                        params.searchPattern != null -> {
                            // Search by pattern
                            searchByPattern(projectPath, params.searchPattern, params)
                        }

                        else -> {
                            return@withContext ToolResult.error("Must specify either filePath, className, methodName, or searchPattern")
                        }
                    }

                if (results.isEmpty()) {
                    ToolResult.ok("No code fragments found matching the criteria")
                } else {
                    val jsonResults =
                        Json.encodeToString(
                            kotlinx.serialization.serializer<List<CodeFragmentResult>>(),
                            results,
                        )
                    val output =
                        buildString {
                            appendLine("Found ${results.size} code fragment(s):")
                            appendLine()
                            results.forEachIndexed { index, result ->
                                appendLine("=".repeat(80))
                                appendLine("Fragment ${index + 1}: ${result.title}")
                                appendLine("File: ${result.file}")
                                appendLine("Language: ${result.language}")
                                if (result.className != null) appendLine("Class: ${result.className}")
                                if (result.methodName != null) appendLine("Method: ${result.methodName}")
                                if (result.packageName != null) appendLine("Package: ${result.packageName}")
                                if (result.returnType != null) appendLine("Return Type: ${result.returnType}")
                                if (result.parameters.isNotEmpty()) {
                                    appendLine(
                                        "Parameters: ${
                                            result.parameters.joinToString(
                                                ", ",
                                            )
                                        }",
                                    )
                                }
                                if (result.comment != null) appendLine("Comment: ${result.comment}")
                                appendLine("Tags: ${result.tags.joinToString(", ")}")
                                appendLine("=".repeat(80))
                                appendLine(result.content)
                                appendLine()
                            }
                            appendLine("JSON Data:")
                            appendLine(jsonResults)
                        }
                    ToolResult.ok(output)
                }
            } catch (e: SecurityException) {
                ToolResult.error(
                    "CODE_EXTRACTOR_ACCESS_DENIED: Cannot access file or directory. Check file permissions and project path configuration. Error: ${e.message}",
                )
            } catch (e: java.nio.file.NoSuchFileException) {
                ToolResult.error(
                    "CODE_EXTRACTOR_FILE_NOT_FOUND: Specified file does not exist: ${e.file}. Please verify the file path relative to project root or use searchPattern to find files.",
                )
            } catch (e: java.nio.file.AccessDeniedException) {
                ToolResult.error("CODE_EXTRACTOR_ACCESS_DENIED: Permission denied accessing file: ${e.file}. Check file permissions.")
            } catch (e: java.io.IOException) {
                ToolResult.error("CODE_EXTRACTOR_IO_ERROR: File system error occurred: ${e.message}. Check if file is locked or corrupted.")
            } catch (e: kotlinx.serialization.SerializationException) {
                ToolResult.error(
                    "CODE_EXTRACTOR_PARSING_ERROR: Failed to parse task parameters. Expected JSON format with fields: filePath, className, methodName, searchPattern, includeImports, includeComments, signatureOnly, languageHint. Error: ${e.message}",
                )
            } catch (e: IllegalArgumentException) {
                ToolResult.error(
                    "CODE_EXTRACTOR_INVALID_PARAMS: Invalid parameter values provided: ${e.message}. Please check parameter format and values.",
                )
            } catch (e: Exception) {
                ToolResult.error(
                    "CODE_EXTRACTOR_UNKNOWN_ERROR: Unexpected error during code extraction: ${e.javaClass.simpleName} - ${e.message}. Please report this error with the task parameters used.",
                )
            }
        }

    private fun parseTaskDescription(taskDescription: String): CodeExtractorParams {
        val cleanedInput =
            taskDescription
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

        return Json.decodeFromString<CodeExtractorParams>(cleanedInput)
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
        val results = mutableListOf<CodeFragmentResult>()
        Files
            .walk(projectPath)
            .filter { it.toFile().isFile && isSupportedFile(it) }
            .forEach { file ->
                try {
                    val fragments = extractFromFile(file, params)
                    results.addAll(
                        fragments.filter { fragment ->
                            fragment.content.contains(pattern, ignoreCase = true) ||
                                fragment.className?.contains(pattern, ignoreCase = true) == true ||
                                fragment.methodName?.contains(pattern, ignoreCase = true) == true ||
                                fragment.tags.any { it.contains(pattern, ignoreCase = true) }
                        },
                    )
                } catch (e: Exception) {
                    // Skip files that can't be processed
                }
            }
        return results
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
}
