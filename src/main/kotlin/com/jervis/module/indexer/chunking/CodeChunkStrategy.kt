package com.jervis.module.indexer.chunking

import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.regex.Pattern

/**
 * Strategy for chunking code content with structure awareness.
 * This strategy attempts to split code at logical boundaries like classes, methods, and functions.
 */
@Component
class CodeChunkStrategy(
    @Value("\${chunking.code.max-chunk-size:1024}") private val defaultMaxChunkSize: Int,
    @Value("\${chunking.code.overlap-size:100}") private val defaultOverlapSize: Int,
) : ChunkStrategy {
    private val logger = KotlinLogging.logger {}

    private val codeLanguages =
        setOf(
            // JVM languages
            "kt",
            "kts",
            "java",
            "groovy",
            "scala",
            "clj",
            // Web languages
            "js",
            "ts",
            "jsx",
            "tsx",
            "html",
            "css",
            "scss",
            "sass",
            "less",
            "vue",
            "svelte",
            // Scripting languages
            "py",
            "rb",
            "php",
            "sh",
            "bash",
            "zsh",
            "ps1",
            "bat",
            "cmd",
            // Systems languages
            "c",
            "cpp",
            "h",
            "hpp",
            "cs",
            "go",
            "rs",
            "swift",
            // Data formats
            "json",
            "yaml",
            "yml",
            "xml",
            "toml",
            "ini",
            "properties",
            "sql",
        )

    override fun splitContent(
        content: String,
        metadata: Map<String, String>,
        maxChunkSize: Int,
        overlapSize: Int,
    ): List<Chunk> {
        val language = metadata["language"] ?: ""
        val actualMaxChunkSize = if (maxChunkSize > 0) maxChunkSize else defaultMaxChunkSize
        val actualOverlapSize = if (overlapSize > 0) overlapSize else defaultOverlapSize

        logger.debug { "Chunking code with language: $language, maxChunkSize: $actualMaxChunkSize, overlapSize: $actualOverlapSize" }

        // Special handling for different languages
        return when (language.lowercase()) {
            "kt", "kts" -> chunkKotlinCode(content, actualMaxChunkSize, actualOverlapSize)
            "java" -> chunkJavaCode(content, actualMaxChunkSize, actualOverlapSize)
            else -> chunkGenericCode(content, language, actualMaxChunkSize, actualOverlapSize)
        }
    }

    override fun canHandle(contentType: String): Boolean = contentType.lowercase() in codeLanguages

    /**
     * Chunk Kotlin code using pattern matching for classes, functions, etc.
     */
    private fun chunkKotlinCode(
        content: String,
        maxChunkSize: Int,
        overlapSize: Int,
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val lines = content.lines()

        // Patterns for Kotlin code structures
        Pattern.compile("^\\s*package\\s+(\\S+)")
        Pattern.compile("^\\s*import\\s+(\\S+)")
        val classPattern = Pattern.compile("^\\s*(class|interface|object|enum class)\\s+(\\w+)")
        val functionPattern = Pattern.compile("^\\s*fun\\s+(\\w+)\\s*\\(")

        // Extract header (package and imports)
        val headerEndLine =
            lines.indexOfFirst {
                !it.trim().startsWith("package") &&
                    !it.trim().startsWith("import") &&
                    it.trim().isNotEmpty()
            }

        if (headerEndLine > 0) {
            val headerContent = lines.subList(0, headerEndLine).joinToString("\n")
            chunks.add(
                Chunk(
                    content = headerContent,
                    metadata =
                        mapOf(
                            "type" to "header",
                            "name" to "File Header",
                            "start_line" to 1,
                            "end_line" to headerEndLine,
                        ),
                ),
            )
        }

        // Process the rest of the file
        var currentChunk = StringBuilder()
        var currentType: String? = null
        var currentName: String? = null
        var currentParent: String? = null
        var startLine = headerEndLine + 1
        var lineNumber = headerEndLine + 1
        var inComment = false

        for (i in lineNumber - 1 until lines.size) {
            val line = lines[i]

            // Handle multi-line comments
            if (line.contains("/*") && !line.contains("*/")) {
                inComment = true
            }
            if (line.contains("*/")) {
                inComment = false
            }

            // Skip empty lines and comments for pattern matching
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
                    addCodeChunk(
                        chunks,
                        currentChunk.toString(),
                        currentType,
                        currentName,
                        startLine,
                        lineNumber - 1,
                        currentParent,
                        maxChunkSize,
                        overlapSize,
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
                    addCodeChunk(
                        chunks,
                        currentChunk.toString(),
                        currentType,
                        currentName,
                        startLine,
                        lineNumber - 1,
                        currentParent,
                        maxChunkSize,
                        overlapSize,
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
                                it.metadata["type"] == "class" ||
                                    it.metadata["type"] == "interface" ||
                                    it.metadata["type"] == "object" ||
                                    it.metadata["type"] == "enum class"
                            }?.metadata
                            ?.get("name")
                            ?.toString()
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
            addCodeChunk(
                chunks,
                currentChunk.toString(),
                currentType,
                currentName,
                startLine,
                lineNumber - 1,
                currentParent,
                maxChunkSize,
                overlapSize,
            )
        }

        return chunks
    }

    /**
     * Chunk Java code using pattern matching for classes, methods, etc.
     */
    private fun chunkJavaCode(
        content: String,
        maxChunkSize: Int,
        overlapSize: Int,
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val lines = content.lines()

        // Patterns for Java code structures
        Pattern.compile("^\\s*package\\s+(\\S+);")
        Pattern.compile("^\\s*import\\s+(\\S+);")
        val classPattern = Pattern.compile("^\\s*(public|private|protected)?\\s*(class|interface|enum)\\s+(\\w+)")
        val methodPattern = Pattern.compile("^\\s*(public|private|protected)?\\s*(?:static)?\\s*\\w+\\s+(\\w+)\\s*\\(")

        // Extract header (package and imports)
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
                Chunk(
                    content = headerContent,
                    metadata =
                        mapOf(
                            "type" to "header",
                            "name" to "File Header",
                            "start_line" to 1,
                            "end_line" to headerEndLine,
                        ),
                ),
            )
        }

        // Process the rest of the file
        var currentChunk = StringBuilder()
        var currentType: String? = null
        var currentName: String? = null
        var currentParent: String? = null
        var startLine = headerEndLine + 1
        var lineNumber = headerEndLine + 1
        var inComment = false

        for (i in lineNumber - 1 until lines.size) {
            val line = lines[i]

            // Handle multi-line comments
            if (line.contains("/*") && !line.contains("*/")) {
                inComment = true
            }
            if (line.contains("*/")) {
                inComment = false
            }

            // Skip empty lines and comments for pattern matching
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
                    addCodeChunk(
                        chunks,
                        currentChunk.toString(),
                        currentType,
                        currentName,
                        startLine,
                        lineNumber - 1,
                        currentParent,
                        maxChunkSize,
                        overlapSize,
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
                    addCodeChunk(
                        chunks,
                        currentChunk.toString(),
                        currentType,
                        currentName,
                        startLine,
                        lineNumber - 1,
                        currentParent,
                        maxChunkSize,
                        overlapSize,
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
                            it.metadata["type"] == "class" || it.metadata["type"] == "interface" || it.metadata["type"] == "enum"
                        }?.metadata
                        ?.get("name")
                        ?.toString()

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
            addCodeChunk(
                chunks,
                currentChunk.toString(),
                currentType,
                currentName,
                startLine,
                lineNumber - 1,
                currentParent,
                maxChunkSize,
                overlapSize,
            )
        }

        return chunks
    }

    /**
     * Chunk generic code using patterns and indentation
     */
    private fun chunkGenericCode(
        content: String,
        language: String,
        maxChunkSize: Int,
        overlapSize: Int,
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
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
                    addCodeChunk(
                        chunks,
                        currentChunk.toString(),
                        currentType,
                        currentName,
                        startLine,
                        lineNumber - 1,
                        currentParent,
                        maxChunkSize,
                        overlapSize,
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
                    addCodeChunk(
                        chunks,
                        currentChunk.toString(),
                        currentType,
                        currentName,
                        startLine,
                        lineNumber - 1,
                        currentParent,
                        maxChunkSize,
                        overlapSize,
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
                            it.metadata["type"] == "class" ||
                                it.metadata["type"] == "interface" ||
                                it.metadata["type"] == "enum" ||
                                it.metadata["type"] == "struct" ||
                                it.metadata["type"] == "type"
                        }?.metadata
                        ?.get("name")
                        ?.toString()
                startLine = lineNumber
                indentLevel = currentIndent
            }
            // Check for function declaration
            else if (functionPattern.matcher(line).find() && !line.contains("=")) {
                val matcher = functionPattern.matcher(line)
                matcher.find()

                // Save previous chunk if exists
                if (currentChunk.isNotEmpty() && currentType != null) {
                    addCodeChunk(
                        chunks,
                        currentChunk.toString(),
                        currentType,
                        currentName,
                        startLine,
                        lineNumber - 1,
                        currentParent,
                        maxChunkSize,
                        overlapSize,
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
                        addCodeChunk(
                            chunks,
                            currentChunk.toString(),
                            currentType,
                            currentName,
                            startLine,
                            lineNumber - 1,
                            currentParent,
                            maxChunkSize,
                            overlapSize,
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
            addCodeChunk(
                chunks,
                currentChunk.toString(),
                currentType,
                currentName,
                startLine,
                lineNumber - 1,
                currentParent,
                maxChunkSize,
                overlapSize,
            )
        }

        return chunks
    }

    /**
     * Add a code chunk, splitting if necessary based on line count
     */
    private fun addCodeChunk(
        chunks: MutableList<Chunk>,
        content: String,
        type: String,
        name: String?,
        startLine: Int,
        endLine: Int,
        parentName: String?,
        maxChunkSize: Int,
        overlapSize: Int,
    ) {
        // If the content is small enough, add it as a single chunk
        val lineCount = content.lines().size
        if (lineCount <= maxChunkSize) {
            chunks.add(
                Chunk(
                    content = content,
                    metadata =
                        mapOf(
                            "type" to type,
                            "name" to (name ?: "Unknown"),
                            "start_line" to startLine,
                            "end_line" to endLine,
                            "parent_name" to (parentName ?: ""),
                        ),
                ),
            )
            return
        }

        // Otherwise, split the chunk by lines
        val lines = content.lines()
        var currentChunk = StringBuilder()
        var currentLineCount = 0
        var chunkStartLine = startLine
        var chunkIndex = 0

        for (i in lines.indices) {
            val line = lines[i]
            
            // If adding this line would exceed the max chunk size, create a new chunk
            if (currentLineCount + 1 > maxChunkSize && currentChunk.isNotEmpty()) {
                chunks.add(
                    Chunk(
                        content = currentChunk.toString(),
                        metadata =
                            mapOf(
                                "type" to type,
                                "name" to "${name ?: "Unknown"}_part$chunkIndex",
                                "start_line" to chunkStartLine,
                                "end_line" to (startLine + i - 1),
                                "parent_name" to (parentName ?: ""),
                                "is_partial" to true,
                                "chunk_index" to chunkIndex,
                            ),
                    ),
                )

                // Start a new chunk with overlap
                val overlapLines = getOverlapLines(currentChunk.toString(), overlapSize)
                currentChunk = StringBuilder(overlapLines)
                currentLineCount = overlapLines.count { it == '\n' } + 1
                chunkStartLine = startLine + i - currentLineCount + 1
                chunkIndex++
            }

            // Add line to current chunk
            currentChunk.append(line).append("\n")
            currentLineCount++
        }

        // Add the last chunk
        if (currentChunk.isNotEmpty()) {
            chunks.add(
                Chunk(
                    content = currentChunk.toString(),
                    metadata =
                        mapOf(
                            "type" to type,
                            "name" to
                                if (chunkIndex > 0) {
                                    "${name ?: "Unknown"}_part$chunkIndex"
                                } else {
                                    (
                                        name
                                            ?: "Unknown"
                                    )
                                },
                            "start_line" to chunkStartLine,
                            "end_line" to endLine,
                            "parent_name" to (parentName ?: ""),
                            "is_partial" to (chunkIndex > 0),
                            "chunk_index" to chunkIndex,
                        ),
                ),
            )
        }
    }

    /**
     * Get overlap lines from the end of a chunk
     */
    private fun getOverlapLines(
        content: String,
        overlapSize: Int,
    ): String {
        if (content.isBlank() || overlapSize <= 0) return ""

        val lines = content.lines()
        val overlapBuilder = StringBuilder()
        var overlapLineCount = 0

        // Start from the end and work backwards
        for (i in lines.size - 1 downTo 0) {
            val line = lines[i]

            if (overlapLineCount < overlapSize || overlapBuilder.isEmpty()) {
                overlapBuilder.insert(0, line + "\n")
                overlapLineCount++
            } else {
                break
            }
        }

        return overlapBuilder.toString()
    }
}