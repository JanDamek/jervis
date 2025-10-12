package com.jervis.service.analysis

import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Service for extracting text content from files based on line ranges.
 * Handles edge cases like single-line extractions where lineStart equals lineEnd.
 */
@Service
class FileTextExtractor {
    private val logger = KotlinLogging.logger {}

    /**
     * Extract text content from a file based on line range.
     *
     * @param filePath Path to the file
     * @param lineStart Starting line number (1-based)
     * @param lineEnd Ending line number (1-based, inclusive)
     * @return Extracted text content or null if extraction fails
     */
    fun extractTextFromFile(
        filePath: Path,
        lineStart: Int,
        lineEnd: Int,
    ): String? {
        return try {
            if (!filePath.exists()) {
                logger.warn { "File does not exist: $filePath" }
                return null
            }

            if (lineStart < 0 || lineEnd < 1) {
                logger.warn { "Invalid line numbers: lineStart=$lineStart, lineEnd=$lineEnd for file $filePath" }
                null
            }

            if (lineStart > lineEnd) {
                logger.warn { "Invalid range: lineStart ($lineStart) > lineEnd ($lineEnd) for file $filePath" }
                return null
            }

            val allLines = Files.readAllLines(filePath)

            if (allLines.isEmpty()) {
                logger.debug { "File is empty: $filePath" }
                return null
            }

            // Validate line range against file content
            if (lineStart > allLines.size) {
                // Enhanced diagnostic logging for line range validation errors
                val fileLastModified =
                    try {
                        java.time.Instant.ofEpochMilli(filePath.toFile().lastModified())
                    } catch (e: Exception) {
                        "unknown"
                    }

                val diagnosticInfo =
                    buildString {
                        appendLine("DIAGNOSTIC: lineStart exceeds file length in FileTextExtractor")
                        appendLine("  File: $filePath")
                        appendLine("  Requested range: $lineStart-$lineEnd")
                        appendLine("  Actual file length: ${allLines.size} lines")
                        appendLine("  File last modified: $fileLastModified")
                        appendLine("  File exists: ${filePath.exists()}")

                        // Add stack trace to see call path
                        appendLine("  Call stack:")
                        Thread.currentThread().stackTrace.take(10).forEach { frame ->
                            appendLine("    ${frame.className}.${frame.methodName}:${frame.lineNumber}")
                        }
                    }

                logger.warn { diagnosticInfo }
                return null
            }

            // Adjust lineEnd to not exceed file length
            val actualLineEnd = minOf(lineEnd, allLines.size)

            // Extract lines (convert from 1-based to 0-based indexing)
            val startIndex = if (lineStart > 0) lineStart - 1 else lineStart
            val endIndex = actualLineEnd // subList is exclusive on end

            val extractedLines = allLines.subList(startIndex, endIndex).toList()

            extractedLines.joinToString("\n")
        } catch (e: Exception) {
            logger.error(e) { "Failed to extract text from file $filePath, lines $lineStart-$lineEnd" }
            null
        }
    }

    /**
     * Extract text content from a file using relative path and project root.
     *
     * @param projectPath Root path of the project
     * @param relativePath Relative path to the file within the project
     * @param lineStart Starting line number (1-based)
     * @param lineEnd Ending line number (1-based, inclusive)
     * @return Extracted text content or null if extraction fails
     */
    fun extractTextFromProjectFile(
        projectPath: Path,
        relativePath: String,
        lineStart: Int,
        lineEnd: Int,
    ): String? {
        val fullPath = projectPath.resolve(relativePath)
        return extractTextFromFile(fullPath, lineStart, lineEnd)
    }
}
