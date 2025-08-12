package com.jervis.service.indexer

import com.jervis.service.indexer.chunking.ChunkStrategyFactory
import mu.KotlinLogging
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.regex.Pattern

/**
 * Service for advanced chunking of code and text files.
 * This service provides methods for splitting content into meaningful chunks
 * based on the structure of the document.
 */
@Service
class ChunkingService(
    private val chunkStrategyFactory: ChunkStrategyFactory,
    @Value("\${chunking.text.max-chunk-size:1024}") private val textMaxChunkSize: Int,
    @Value("\${chunking.text.overlap-size:200}") private val textOverlapSize: Int,
    @Value("\${chunking.code.max-chunk-size:1024}") private val codeMaxChunkSize: Int,
    @Value("\${chunking.code.overlap-size:100}") private val codeOverlapSize: Int,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Detect programming language from content using simple heuristics
     *
     * @param content The code content to analyze
     * @return The detected programming language
     */
    private fun detectLanguageFromContent(content: String): String {
        return when {
            content.contains("package ") && content.contains("fun ") -> "kotlin"
            content.contains("package ") && content.contains("public class") -> "java"
            content.contains("import ") && content.contains("def ") -> "python"
            content.contains("function ") || content.contains("const ") || content.contains("let ") -> "javascript"
            content.contains("#include") || content.contains("int main") -> "cpp"
            content.contains("using namespace") || content.contains("std::") -> "cpp"
            content.contains("fn ") && content.contains("let ") -> "rust"
            content.contains("func ") && content.contains("package ") -> "go"
            else -> "generic"
        }
    }

    /**
     * Create chunks from code content based on the programming language
     *
     * @param content The code content to chunk
     * @return A list of code chunks
     */
    fun createCodeChunks(content: String): List<CodeChunk> {
        logger.debug { "Creating code chunks - language will be determined from content" }

        // Determine language from content - for now use a simple heuristic
        val language = detectLanguageFromContent(content)
        
        // Use the new chunking strategy
        val strategy = chunkStrategyFactory.getStrategy(language)
        val chunks = strategy.splitContent(content, emptyMap(), codeMaxChunkSize, codeOverlapSize)

        // Convert the new Chunk objects to the old CodeChunk format for backward compatibility
        return chunks.map { chunk ->
            CodeChunk(
                content = chunk.content,
                type = chunk.metadata["type"]?.toString() ?: "code_segment",
                name = chunk.metadata["name"]?.toString() ?: "Unknown",
                startLine = chunk.metadata["start_line"]?.toString()?.toIntOrNull() ?: 1,
                endLine = chunk.metadata["end_line"]?.toString()?.toIntOrNull() ?: 1,
                parentName = chunk.metadata["parent_name"]?.toString(),
            )
        }
    }

    /**
     * Create chunks from text content based on the format
     *
     * @param content The text content to chunk
     * @param format The format of the text (md, html, etc.)
     * @return A list of text chunks
     */
    fun createTextChunks(
        content: String,
        format: String,
    ): List<TextChunk> {
        logger.debug { "Creating text chunks for format: $format" }

        // Use the new chunking strategy
        val strategy = chunkStrategyFactory.getStrategy(format)
        val metadata = mapOf("format" to format)
        val chunks = strategy.splitContent(content, metadata, textMaxChunkSize, textOverlapSize)

        // Convert the new Chunk objects to the old TextChunk format for backward compatibility
        return chunks.map { chunk ->
            TextChunk(
                content = chunk.content,
                type = chunk.metadata["type"]?.toString() ?: "text",
                heading = chunk.metadata["heading"]?.toString() ?: "Unknown",
                level = chunk.metadata["level"]?.toString()?.toIntOrNull() ?: 0,
                startLine = chunk.metadata["start_line"]?.toString()?.toIntOrNull() ?: 1,
                endLine = chunk.metadata["end_line"]?.toString()?.toIntOrNull() ?: 1,
            )
        }
    }

    /**
     * Chunk Kotlin code using pattern matching
     *
     * @param content The Kotlin code to chunk
     * @return A list of code chunks
     */
    fun chunkKotlinCode(content: String): List<CodeChunk> {
        logger.debug { "Chunking Kotlin code" }
        val chunks = mutableListOf<CodeChunk>()
        val lines = content.lines()

        // Patterns for Kotlin code structures
        Pattern.compile("^\\s*package\\s+(\\S+)")
        Pattern.compile("^\\s*import\\s+(\\S+)")
        val classPattern = Pattern.compile("^\\s*(class|interface|object|enum class)\\s+(\\w+)")
        val functionPattern = Pattern.compile("^\\s*fun\\s+(\\w+)\\s*\\(")

        var currentChunk = StringBuilder()
        var currentType: String? = null
        var currentName: String? = null
        var currentParent: String? = null
        var startLine = 1
        var lineNumber = 1
        var inComment = false

        // First, extract package and imports
        val headerEndLine =
            lines.indexOfFirst {
                !it.trim().startsWith("package") &&
                    !it.trim().startsWith("import") &&
                    it.trim().isNotEmpty()
            }

        if (headerEndLine > 0) {
            val headerContent = lines.subList(0, headerEndLine).joinToString("\n")
            chunks.add(
                CodeChunk(
                    content = headerContent,
                    type = "header",
                    name = "File Header",
                    startLine = 1,
                    endLine = headerEndLine,
                    parentName = null,
                ),
            )
            lineNumber = headerEndLine + 1
        }

        // Process the rest of the file
        for (i in lineNumber - 1 until lines.size) {
            val line = lines[i]

            // Handle multi-line comments
            if (line.contains("/*") && !line.contains("*/")) {
                inComment = true
            }
            if (line.contains("*/")) {
                inComment = false
            }

            // Skip empty lines and comments
            if (line.trim().isEmpty() || line.trim().startsWith("//") || inComment) {
                if (currentChunk.isNotEmpty()) {
                    currentChunk.append(line).append("\n")
                }
                lineNumber++
                continue
            }

            // Check for class/interface/object declaration
            val classMatcher = classPattern.matcher(line)
            if (classMatcher.find()) {
                // Save previous chunk if exists
                if (currentChunk.isNotEmpty() && currentType != null) {
                    chunks.add(
                        CodeChunk(
                            content = currentChunk.toString(),
                            type = currentType,
                            name = currentName ?: "Unknown",
                            startLine = startLine,
                            endLine = lineNumber - 1,
                            parentName = currentParent,
                        ),
                    )
                }

                // Start new class chunk
                currentChunk = StringBuilder(line).append("\n")
                currentType = classMatcher.group(1) ?: "class"
                currentName = classMatcher.group(2)
                currentParent = null
                startLine = lineNumber
            }
            // Check for function declaration
            else if (functionPattern.matcher(line).find()) {
                val matcher = functionPattern.matcher(line)
                matcher.find()

                // Save previous chunk if exists
                if (currentChunk.isNotEmpty() && currentType != null) {
                    chunks.add(
                        CodeChunk(
                            content = currentChunk.toString(),
                            type = currentType,
                            name = currentName ?: "Unknown",
                            startLine = startLine,
                            endLine = lineNumber - 1,
                            parentName = currentParent,
                        ),
                    )
                }

                // Start new function chunk
                currentChunk = StringBuilder(line).append("\n")
                currentType = "function"
                currentName = matcher.group(1)

                // If we're inside a class, set the parent
                val indentation = line.indexOfFirst { !it.isWhitespace() }
                if (indentation > 0) {
                    // This is likely a method inside a class
                    currentParent =
                        chunks
                            .lastOrNull {
                                it.type == "class" ||
                                    it.type == "interface" ||
                                    it.type == "object" ||
                                    it.type == "enum class"
                            }?.name
                    currentType = "method"
                } else {
                    currentParent = null
                }

                startLine = lineNumber
            }
            // Continue current chunk
            else {
                if (currentChunk.isEmpty()) {
                    // Start a new generic chunk
                    currentChunk = StringBuilder(line).append("\n")
                    currentType = "code_segment"
                    currentName = "Segment_$lineNumber"
                    currentParent = null
                    startLine = lineNumber
                } else {
                    currentChunk.append(line).append("\n")
                }
            }

            lineNumber++
        }

        // Add the last chunk
        if (currentChunk.isNotEmpty() && currentType != null) {
            chunks.add(
                CodeChunk(
                    content = currentChunk.toString(),
                    type = currentType,
                    name = currentName ?: "Unknown",
                    startLine = startLine,
                    endLine = lineNumber - 1,
                    parentName = currentParent,
                ),
            )
        }

        return chunks
    }

    /**
     * Chunk Java code using pattern matching
     *
     * @param content The Java code to chunk
     * @return A list of code chunks
     */
    fun chunkJavaCode(content: String): List<CodeChunk> {
        logger.debug { "Chunking Java code" }
        val chunks = mutableListOf<CodeChunk>()
        val lines = content.lines()

        // Patterns for Java code structures
        Pattern.compile("^\\s*package\\s+(\\S+);")
        Pattern.compile("^\\s*import\\s+(\\S+);")
        val classPattern = Pattern.compile("^\\s*(public|private|protected)?\\s*(class|interface|enum)\\s+(\\w+)")
        val methodPattern = Pattern.compile("^\\s*(public|private|protected)?\\s*(?:static)?\\s*\\w+\\s+(\\w+)\\s*\\(")

        var currentChunk = StringBuilder()
        var currentType: String? = null
        var currentName: String? = null
        var currentParent: String? = null
        var startLine = 1
        var lineNumber = 1
        var inComment = false

        // First, extract package and imports
        val headerEndLine =
            lines.indexOfFirst {
                !it.trim().startsWith("package") &&
                    !it.trim().startsWith("import") &&
                    it.trim().isNotEmpty() &&
                    !it.trim().startsWith("//") &&
                    !it.trim().startsWith("/*")
            }

        if (headerEndLine > 0) {
            val headerContent = lines.subList(0, headerEndLine).joinToString("\n")
            chunks.add(
                CodeChunk(
                    content = headerContent,
                    type = "header",
                    name = "File Header",
                    startLine = 1,
                    endLine = headerEndLine,
                    parentName = null,
                ),
            )
            lineNumber = headerEndLine + 1
        }

        // Process the rest of the file
        for (i in lineNumber - 1 until lines.size) {
            val line = lines[i]

            // Handle multi-line comments
            if (line.contains("/*") && !line.contains("*/")) {
                inComment = true
            }
            if (line.contains("*/")) {
                inComment = false
            }

            // Skip empty lines and comments
            if (line.trim().isEmpty() || line.trim().startsWith("//") || inComment) {
                if (currentChunk.isNotEmpty()) {
                    currentChunk.append(line).append("\n")
                }
                lineNumber++
                continue
            }

            // Check for class/interface/enum declaration
            val classMatcher = classPattern.matcher(line)
            if (classMatcher.find()) {
                // Save previous chunk if exists
                if (currentChunk.isNotEmpty() && currentType != null) {
                    chunks.add(
                        CodeChunk(
                            content = currentChunk.toString(),
                            type = currentType,
                            name = currentName ?: "Unknown",
                            startLine = startLine,
                            endLine = lineNumber - 1,
                            parentName = currentParent,
                        ),
                    )
                }

                // Start new class chunk
                currentChunk = StringBuilder(line).append("\n")
                currentType = classMatcher.group(2) ?: "class"
                currentName = classMatcher.group(3)
                currentParent = null
                startLine = lineNumber
            }
            // Check for method declaration
            else if (methodPattern.matcher(line).find()) {
                val matcher = methodPattern.matcher(line)
                matcher.find()

                // Save previous chunk if exists
                if (currentChunk.isNotEmpty() && currentType != null) {
                    chunks.add(
                        CodeChunk(
                            content = currentChunk.toString(),
                            type = currentType,
                            name = currentName ?: "Unknown",
                            startLine = startLine,
                            endLine = lineNumber - 1,
                            parentName = currentParent,
                        ),
                    )
                }

                // Start new method chunk
                currentChunk = StringBuilder(line).append("\n")
                currentType = "method"
                currentName = matcher.group(2)

                // If we're inside a class, set the parent
                currentParent =
                    chunks
                        .lastOrNull {
                            it.type == "class" || it.type == "interface" || it.type == "enum"
                        }?.name

                startLine = lineNumber
            }
            // Continue current chunk
            else {
                if (currentChunk.isEmpty()) {
                    // Start a new generic chunk
                    currentChunk = StringBuilder(line).append("\n")
                    currentType = "code_segment"
                    currentName = "Segment_$lineNumber"
                    currentParent = null
                    startLine = lineNumber
                } else {
                    currentChunk.append(line).append("\n")
                }
            }

            lineNumber++
        }

        // Add the last chunk
        if (currentChunk.isNotEmpty() && currentType != null) {
            chunks.add(
                CodeChunk(
                    content = currentChunk.toString(),
                    type = currentType,
                    name = currentName ?: "Unknown",
                    startLine = startLine,
                    endLine = lineNumber - 1,
                    parentName = currentParent,
                ),
            )
        }

        return chunks
    }

    /**
     * Chunk generic code using patterns and indentation
     *
     * @param content The code to chunk
     * @param language The programming language of the code
     * @return A list of code chunks
     */
    fun chunkGenericCode(
        content: String,
        language: String,
    ): List<CodeChunk> {
        logger.debug { "Chunking generic code for language: $language" }
        val chunks = mutableListOf<CodeChunk>()
        val lines = content.lines()

        // Patterns for common code structures
        val classPattern = Pattern.compile("^\\s*(class|interface|enum|struct|type)\\s+(\\w+)")
        val methodPattern = Pattern.compile("^\\s*(function|def|func|sub|procedure|method)\\s+(\\w+)\\s*\\(")
        val functionPattern = Pattern.compile("^\\s*(?:function|def|func|sub|procedure|method)?\\s*(\\w+)\\s*\\(")

        var currentChunk = StringBuilder()
        var currentType: String? = null
        var currentName: String? = null
        var currentParent: String? = null
        var startLine = 1
        var lineNumber = 1
        var indentLevel = -1

        for (line in lines) {
            val currentIndent = line.indexOfFirst { !it.isWhitespace() }

            // Skip empty lines
            if (currentIndent == -1) {
                if (currentChunk.isNotEmpty()) {
                    currentChunk.append(line).append("\n")
                }
                lineNumber++
                continue
            }

            // Check for class/interface/enum declaration
            val classMatcher = classPattern.matcher(line)
            if (classMatcher.find()) {
                // Save previous chunk if exists
                if (currentChunk.isNotEmpty() && currentType != null) {
                    chunks.add(
                        CodeChunk(
                            content = currentChunk.toString(),
                            type = currentType,
                            name = currentName ?: "Unknown",
                            startLine = startLine,
                            endLine = lineNumber - 1,
                            parentName = currentParent,
                        ),
                    )
                }

                // Start new class chunk
                currentChunk = StringBuilder(line).append("\n")
                currentType = classMatcher.group(1) ?: "class"
                currentName = classMatcher.group(2)
                currentParent = null
                startLine = lineNumber
                indentLevel = currentIndent
            }
            // Check for method declaration
            else if (methodPattern.matcher(line).find()) {
                val matcher = methodPattern.matcher(line)
                matcher.find()

                // Save previous chunk if exists
                if (currentChunk.isNotEmpty() && currentType != null) {
                    chunks.add(
                        CodeChunk(
                            content = currentChunk.toString(),
                            type = currentType,
                            name = currentName ?: "Unknown",
                            startLine = startLine,
                            endLine = lineNumber - 1,
                            parentName = currentParent,
                        ),
                    )
                }

                // Start new method chunk
                currentChunk = StringBuilder(line).append("\n")
                currentType = "method"
                currentName = matcher.group(2)
                // If we're inside a class, set the parent
                currentParent =
                    chunks
                        .lastOrNull {
                            it.type == "class" ||
                                it.type == "interface" ||
                                it.type == "enum" ||
                                it.type == "struct" ||
                                it.type == "type"
                        }?.name
                startLine = lineNumber
                indentLevel = currentIndent
            }
            // Check for function declaration
            else if (functionPattern.matcher(line).find() && !line.contains("=")) {
                val matcher = functionPattern.matcher(line)
                matcher.find()

                // Save previous chunk if exists
                if (currentChunk.isNotEmpty() && currentType != null) {
                    chunks.add(
                        CodeChunk(
                            content = currentChunk.toString(),
                            type = currentType,
                            name = currentName ?: "Unknown",
                            startLine = startLine,
                            endLine = lineNumber - 1,
                            parentName = currentParent,
                        ),
                    )
                }

                // Start new function chunk
                currentChunk = StringBuilder(line).append("\n")
                currentType = "function"
                currentName = matcher.group(1)
                currentParent = null
                startLine = lineNumber
                indentLevel = currentIndent
            }
            // Continue current chunk or start a new one based on indentation
            else {
                if (indentLevel == -1 || currentChunk.isEmpty()) {
                    // Start a new generic chunk
                    currentChunk = StringBuilder(line).append("\n")
                    currentType = "code_segment"
                    currentName = "Segment_$lineNumber"
                    currentParent = null
                    startLine = lineNumber
                    indentLevel = currentIndent
                } else if (currentIndent <= indentLevel && currentType != "code_segment") {
                    // Save previous chunk if exists
                    if (currentChunk.isNotEmpty() && currentType != null) {
                        chunks.add(
                            CodeChunk(
                                content = currentChunk.toString(),
                                type = currentType,
                                name = currentName ?: "Unknown",
                                startLine = startLine,
                                endLine = lineNumber - 1,
                                parentName = currentParent,
                            ),
                        )
                    }

                    // Start new generic chunk
                    currentChunk = StringBuilder(line).append("\n")
                    currentType = "code_segment"
                    currentName = "Segment_$lineNumber"
                    currentParent = null
                    startLine = lineNumber
                    indentLevel = currentIndent
                } else {
                    // Continue current chunk
                    currentChunk.append(line).append("\n")
                }
            }

            lineNumber++
        }

        // Add the last chunk
        if (currentChunk.isNotEmpty() && currentType != null) {
            chunks.add(
                CodeChunk(
                    content = currentChunk.toString(),
                    type = currentType,
                    name = currentName ?: "Unknown",
                    startLine = startLine,
                    endLine = lineNumber - 1,
                    parentName = currentParent,
                ),
            )
        }

        return chunks
    }

    /**
     * Chunk Markdown text by headings
     *
     * @param content The Markdown text to chunk
     * @return A list of text chunks
     */
    fun chunkMarkdownText(content: String): List<TextChunk> {
        logger.debug { "Chunking Markdown text" }
        val chunks = mutableListOf<TextChunk>()
        val lines = content.lines()

        var currentChunk = StringBuilder()
        var currentHeading: String? = null
        var currentLevel = 0
        var startLine = 1
        var lineNumber = 1

        for (line in lines) {
            // Check for heading
            if (line.startsWith("#")) {
                // Count the number of # to determine heading level
                val level = line.takeWhile { it == '#' }.length
                val heading = line.substring(level).trim()

                // Save previous chunk if exists
                if (currentChunk.isNotEmpty()) {
                    chunks.add(
                        TextChunk(
                            content = currentChunk.toString(),
                            type = if (currentHeading != null) "section" else "text",
                            heading = currentHeading ?: "Introduction",
                            level = currentLevel,
                            startLine = startLine,
                            endLine = lineNumber - 1,
                        ),
                    )
                }

                // Start new chunk
                currentChunk = StringBuilder()
                currentHeading = heading
                currentLevel = level
                startLine = lineNumber
            } else {
                // Add line to current chunk
                currentChunk.append(line).append("\n")
            }

            lineNumber++
        }

        // Add the last chunk
        if (currentChunk.isNotEmpty()) {
            chunks.add(
                TextChunk(
                    content = currentChunk.toString(),
                    type = if (currentHeading != null) "section" else "text",
                    heading = currentHeading ?: "Introduction",
                    level = currentLevel,
                    startLine = startLine,
                    endLine = lineNumber - 1,
                ),
            )
        }

        return chunks
    }

    /**
     * Chunk HTML text using JSoup
     *
     * @param content The HTML text to chunk
     * @return A list of text chunks
     */
    fun chunkHtmlText(content: String): List<TextChunk> {
        logger.debug { "Chunking HTML text" }
        val chunks = mutableListOf<TextChunk>()

        try {
            val doc = Jsoup.parse(content)
            val title = doc.title()

            // Add title as first chunk if available
            if (title.isNotEmpty()) {
                chunks.add(
                    TextChunk(
                        content = title,
                        type = "title",
                        heading = title,
                        level = 0,
                        startLine = 1,
                        endLine = 1,
                    ),
                )
            }

            // Find all headings (h1-h6)
            val headings = doc.select("h1, h2, h3, h4, h5, h6")

            if (headings.isNotEmpty()) {
                // Process each heading and its content
                for (i in 0 until headings.size) {
                    val heading = headings[i]
                    val nextHeading = if (i < headings.size - 1) headings[i + 1] else null

                    val headingText = heading.text()
                    val level = heading.tagName().substring(1).toInt()

                    // Get content between this heading and the next
                    val sectionContent = StringBuilder()
                    var element = heading.nextElementSibling()

                    while (element != null && element != nextHeading) {
                        sectionContent.append(element.text()).append("\n")
                        element = element.nextElementSibling()
                    }

                    chunks.add(
                        TextChunk(
                            content = sectionContent.toString(),
                            type = "section",
                            heading = headingText,
                            level = level,
                            startLine = -1, // HTML doesn't have line numbers easily accessible
                            endLine = -1,
                        ),
                    )
                }
            } else {
                // If no headings, chunk by paragraphs
                val paragraphs = doc.select("p")

                if (paragraphs.isNotEmpty()) {
                    paragraphs.forEachIndexed { index, paragraph ->
                        chunks.add(
                            TextChunk(
                                content = paragraph.text(),
                                type = "paragraph",
                                heading = "Paragraph ${index + 1}",
                                level = 0,
                                startLine = -1,
                                endLine = -1,
                            ),
                        )
                    }
                } else {
                    // If no paragraphs either, use the whole body
                    chunks.add(
                        TextChunk(
                            content = doc.body().text(),
                            type = "text",
                            heading = title.ifEmpty { "Content" },
                            level = 0,
                            startLine = -1,
                            endLine = -1,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error chunking HTML text: ${e.message}" }
            // Fallback to generic text chunking
            return chunkGenericText(content)
        }

        return chunks
    }

    /**
     * Chunk generic text by paragraphs and potential headings
     *
     * @param content The text to chunk
     * @return A list of text chunks
     */
    fun chunkGenericText(content: String): List<TextChunk> {
        logger.debug { "Chunking generic text" }
        val chunks = mutableListOf<TextChunk>()

        // Split by double newlines (paragraphs)
        val paragraphs = content.split("\n\n")
        var currentLine = 1

        paragraphs.forEach { paragraph ->
            if (paragraph.isBlank()) {
                currentLine += paragraph.count { it == '\n' } + 2
                return@forEach
            }

            val lines = paragraph.lines()
            val startLine = currentLine
            val endLine = startLine + lines.size - 1

            // Check if this paragraph might be a heading
            val isHeading =
                lines.size <= 2 &&
                    (lines[0].length < 100) &&
                    (
                        lines[0].uppercase() == lines[0] ||
                            lines[0].endsWith(":") ||
                            !lines[0].contains(" ")
                    )

            val type = if (isHeading) "heading" else "paragraph"
            val heading = if (isHeading) lines[0].trim() else "Paragraph ${chunks.size + 1}"
            val level = if (isHeading) 1 else 0

            chunks.add(
                TextChunk(
                    content = paragraph,
                    type = type,
                    heading = heading,
                    level = level,
                    startLine = startLine,
                    endLine = endLine,
                ),
            )

            currentLine = endLine + 2 // +2 for the paragraph separator
        }

        return chunks
    }

    /**
     * Data class representing a chunk of code
     */
    data class CodeChunk(
        val content: String,
        val type: String,
        val name: String,
        val startLine: Int,
        val endLine: Int,
        val parentName: String?,
    )

    /**
     * Data class representing a chunk of text
     */
    data class TextChunk(
        val content: String,
        val type: String,
        val heading: String,
        val level: Int,
        val startLine: Int,
        val endLine: Int,
    )
}
