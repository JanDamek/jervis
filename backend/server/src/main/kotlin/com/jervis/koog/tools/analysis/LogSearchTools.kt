package com.jervis.koog.tools.analysis

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Log search tools for analyzing application runtime logs.
 */
@LLMDescription("Tools for searching and analyzing application runtime logs")
class LogSearchTools(
    private val task: TaskDocument,
    private val logDirectory: Path
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription("Search through log files in the application's log directory.")
    suspend fun searchLogs(
        @LLMDescription("Regex pattern to search for")
        pattern: String,
        @LLMDescription("Optional filename glob to narrow down the search (e.g. 'server.*.log')")
        fileGlob: String? = null,
        @LLMDescription("Number of context lines before and after match")
        contextLines: Int = 2,
        @LLMDescription("Max number of matches to return")
        limit: Int = 50
    ): LogSearchResult {
        return try {
            if (!Files.exists(logDirectory)) {
                return LogSearchResult(success = false, error = "Log directory not found: $logDirectory")
            }

            val regex = pattern.toRegex(RegexOption.IGNORE_CASE)
            val matches = mutableListOf<LogMatch>()
            var matchCount = 0

            val files = if (fileGlob != null) {
                Files.newDirectoryStream(logDirectory, fileGlob).use { it.toList() }
            } else {
                Files.newDirectoryStream(logDirectory).use { it.toList() }
            }

            for (path in files) {
                if (!Files.isRegularFile(path)) continue
                
                val lines = Files.readAllLines(path)
                lines.forEachIndexed { index, line ->
                    if (matchCount >= limit) return@forEachIndexed
                    
                    if (regex.containsMatchIn(line)) {
                        val start = (index - contextLines).coerceAtLeast(0)
                        val end = (index + contextLines).coerceAtMost(lines.size - 1)
                        val context = lines.subList(start, end + 1).joinToString("\n")
                        
                        matches.add(LogMatch(
                            fileName = path.fileName.toString(),
                            lineNumber = index + 1,
                            lineContent = line,
                            context = context
                        ))
                        matchCount++
                    }
                }
                if (matchCount >= limit) break
            }

            LogSearchResult(success = true, matches = matches)
        } catch (e: Exception) {
            logger.error(e) { "Log search failed" }
            LogSearchResult(success = false, error = e.message)
        }
    }

    @Tool
    @LLMDescription("Get the last N lines from a specific log file.")
    suspend fun tailLog(
        @LLMDescription("Log filename")
        fileName: String,
        @LLMDescription("Number of lines to return")
        linesCount: Int = 100
    ): LogTailResult {
        return try {
            val path = logDirectory.resolve(fileName)
            if (!Files.exists(path)) {
                return LogTailResult(success = false, error = "Log file not found: $fileName")
            }

            val lines = Files.readAllLines(path)
            val tail = lines.takeLast(linesCount).joinToString("\n")
            
            LogTailResult(success = true, content = tail)
        } catch (e: Exception) {
            logger.error(e) { "Log tail failed" }
            LogTailResult(success = false, error = e.message)
        }
    }

    @Serializable
    data class LogMatch(
        val fileName: String,
        val lineNumber: Int,
        val lineContent: String,
        val context: String
    )

    @Serializable
    data class LogSearchResult(
        val success: Boolean,
        val matches: List<LogMatch> = emptyList(),
        val error: String? = null
    )

    @Serializable
    data class LogTailResult(
        val success: Boolean,
        val content: String? = null,
        val error: String? = null
    )
}
