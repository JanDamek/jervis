package com.jervis.service.analysis

import com.jervis.service.indexing.pipeline.JoernSymbol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Dedicated service for parsing Joern analysis results.
 * Handles extraction and parsing of JSON objects from Joern script output.
 */
@Service
class JoernResultParser(
    private val fileTextExtractor: FileTextExtractor,
) {
    private val logger = KotlinLogging.logger {}

    // Memory-aware LRU cache for file contents with automatic eviction
    private val maxCacheSize = 50 // Max number of files to keep in cache
    private val fileContentCache =
        object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean {
                val shouldRemove = size > maxCacheSize
                if (shouldRemove && eldest != null) {
                    logger.debug { "LRU cache evicting file: ${eldest.key} (cache size: $size)" }
                }
                return shouldRemove
            }
        }

    // Track cache memory usage
    @Volatile
    private var approximateCacheSize = 0L
    private val maxCacheSizeBytes = 100 * 1024 * 1024 // 100MB limit

    /**
     * Parse Joern symbol results from script output
     * Flow-based version that progressively emits symbols as they are parsed
     */
    fun parseJoernSymbolResults(
        output: String,
        projectPath: Path,
        language: String,
    ): Flow<JoernSymbol> =
        flow {
            if (output.isBlank()) {
                logger.debug { "JOERN_PARSER: No output to parse" }
                return@flow
            }

            var parsedCount = 0
            var totalJsonObjects = 0

            // Extract and process JSON objects progressively
            extractJsonObjectsFromOutput(output).collect { jsonObject ->
                totalJsonObjects++
                try {
                    val symbol = parseSymbolLine(jsonObject.trim(), projectPath, language)
                    if (symbol != null) {
                        emit(symbol)
                        parsedCount++
                        logger.debug { "JOERN_PARSER: Parsed symbol: ${symbol.type} ${symbol.name}" }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "JOERN_PARSER: Error parsing JSON object: ${jsonObject.take(200)}" }
                }
            }

            logger.info { "JOERN_PARSER: Parsed $parsedCount symbols from $totalJsonObjects JSON objects" }
        }

    /**
     * Extract all JSON objects from mixed output containing logs and JSON
     * Uses brace counting to properly identify complete JSON objects
     * Flow-based version that progressively emits JSON objects as they are found
     */
    private fun extractJsonObjectsFromOutput(output: String): Flow<String> =
        flow {
            val cleanOutput = removeAnsiColorCodes(output)
            val state = JsonParsingState()

            for (char in cleanOutput) {
                processCharacter(char, state)

                if (state.isJsonObjectComplete()) {
                    val jsonString = state.getCurrentJson().trim()
                    if (jsonString.isNotEmpty() && isValidJsonObject(jsonString)) {
                        emit(jsonString)
                    }
                    state.resetForNextObject()
                }
            }
        }

    /**
     * Process a single character during JSON parsing
     */
    private fun processCharacter(
        char: Char,
        state: JsonParsingState,
    ) {
        when {
            state.escapeNext -> handleEscapedCharacter(char, state)
            char == '\\' -> handleBackslash(char, state)
            char == '"' -> handleQuote(char, state)
            char == '{' && !state.inString -> handleOpenBrace(char, state)
            char == '}' && !state.inString && state.inJsonObject -> handleCloseBrace(char, state)
            state.inJsonObject -> state.appendToJson(char)
        }
    }

    /**
     * Handle escaped characters
     */
    private fun handleEscapedCharacter(
        char: Char,
        state: JsonParsingState,
    ) {
        if (state.inJsonObject) state.appendToJson(char)
        state.escapeNext = false
    }

    /**
     * Handle backslash character
     */
    private fun handleBackslash(
        char: Char,
        state: JsonParsingState,
    ) {
        if (state.inJsonObject) state.appendToJson(char)
        state.escapeNext = true
    }

    /**
     * Handle quote character
     */
    private fun handleQuote(
        char: Char,
        state: JsonParsingState,
    ) {
        if (state.inJsonObject) state.appendToJson(char)
        state.inString = !state.inString
    }

    /**
     * Handle opening brace
     */
    private fun handleOpenBrace(
        char: Char,
        state: JsonParsingState,
    ) {
        if (!state.inJsonObject) {
            state.startNewJsonObject()
        }
        state.appendToJson(char)
        state.braceCount++
    }

    /**
     * Handle closing brace
     */
    private fun handleCloseBrace(
        char: Char,
        state: JsonParsingState,
    ) {
        state.appendToJson(char)
        state.braceCount--
    }

    /**
     * State holder for JSON parsing
     */
    private class JsonParsingState {
        var currentJson = StringBuilder()
        var braceCount = 0
        var inJsonObject = false
        var inString = false
        var escapeNext = false

        fun appendToJson(char: Char) {
            currentJson.append(char)
        }

        fun startNewJsonObject() {
            inJsonObject = true
            currentJson = StringBuilder()
        }

        fun isJsonObjectComplete(): Boolean = inJsonObject && braceCount == 0

        fun getCurrentJson(): String = currentJson.toString()

        fun resetForNextObject() {
            inJsonObject = false
            currentJson.clear()
        }
    }

    /**
     * Basic validation that string looks like a JSON object
     */
    private fun isValidJsonObject(jsonString: String): Boolean =
        jsonString.trim().let {
            it.startsWith("{") &&
                it.endsWith("}") &&
                it.contains("\"type\"") &&
                it.contains("\"name\"")
        }

    /**
     * Parse individual symbol line from JSON output
     */
    private fun parseSymbolLine(
        line: String,
        projectPath: Path,
        language: String,
    ): JoernSymbol? =
        try {
            val json = Json { ignoreUnknownKeys = true }
            val jsonResult: JoernSymbol = json.decodeFromString(line)

            // Make file path relative to project
            val relativePath =
                try {
                    projectPath.relativize(Path.of(jsonResult.filePath)).pathString
                } catch (_: Exception) {
                    jsonResult.filePath
                }

            // Extract code content using cached file lines - pass original JSON for debugging
            val extractedCode =
                extractCodeFromCache(
                    projectPath,
                    relativePath,
                    jsonResult.lineStart,
                    jsonResult.lineEnd,
                    jsonResult,
                    line,
                )

            jsonResult.filePath = relativePath
            jsonResult.language = language
            jsonResult.code = extractedCode
            jsonResult
        } catch (e: Exception) {
            logger.warn(e) { "JOERN_PARSER: Error parsing symbol JSON: ${line.take(200)}" }
            null
        }

    /**
     * Extract code content from cached file lines to avoid redundant file reads
     */
    private fun extractCodeFromCache(
        projectPath: Path,
        relativePath: String,
        lineStart: Int,
        lineEnd: Int,
        symbol: JoernSymbol? = null,
        originalJson: String? = null,
    ): String? {
        return try {
            if (lineStart < 1 || lineEnd < 1 || lineStart > lineEnd) {
                logger.warn { "Invalid line range: $lineStart-$lineEnd for file $relativePath" }
                return null
            }

            // Get file content from cache or read it if not cached
            val fileLines =
                synchronized(fileContentCache) {
                    fileContentCache.getOrPut(relativePath) {
                        val fullPath = projectPath.resolve(relativePath)
                        if (!fullPath.toFile().exists()) {
                            logger.warn { "File does not exist: $fullPath" }
                            return null
                        }

                        try {
                            val lines = fullPath.toFile().readLines()
                            val fileSizeBytes = lines.sumOf { it.length * 2 } // Approximate char size in bytes
                            approximateCacheSize += fileSizeBytes

                            // Check if we exceed memory limit and force cleanup if needed
                            if (approximateCacheSize > maxCacheSizeBytes) {
                                logger.debug { "Cache memory limit exceeded (${approximateCacheSize / 1024 / 1024}MB), forcing cleanup" }
                                performMemoryBasedEviction()
                            }

                            logger.debug { "Cached file: $relativePath (${lines.size} lines, ~${fileSizeBytes / 1024}KB)" }
                            lines
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to read file: $fullPath" }
                            return null
                        }
                    }
                }

            if (fileLines.isEmpty()) {
                logger.debug { "File is empty: $relativePath" }
                return null
            }

            // Validate line range against file content
            if (lineStart > fileLines.size) {
                // Enhanced diagnostic logging for line range validation errors
                val fullPath = projectPath.resolve(relativePath)
                val fileLastModified =
                    try {
                        java.time.Instant.ofEpochMilli(fullPath.toFile().lastModified())
                    } catch (e: Exception) {
                        "unknown"
                    }

                val diagnosticInfo =
                    buildString {
                        appendLine("DIAGNOSTIC: lineStart exceeds file length")
                        appendLine("  File: $relativePath")
                        appendLine("  Full path: $fullPath")
                        appendLine("  Requested range: $lineStart-$lineEnd")
                        appendLine("  Actual file length: ${fileLines.size} lines")
                        appendLine("  File last modified: $fileLastModified")

                        // Find and compare CPG creation time
                        symbol?.language?.let { language ->
                            val joernDir = projectPath.resolve(".joern")
                            val cpgPath = joernDir.resolve("cpg_$language.bin")
                            if (cpgPath.toFile().exists()) {
                                val cpgCreationTime = java.time.Instant.ofEpochMilli(cpgPath.toFile().lastModified())
                                appendLine("  CPG file: ${cpgPath.pathString}")
                                appendLine("  CPG created: $cpgCreationTime")

                                // Compare timestamps if both are available
                                if (fileLastModified != "unknown") {
                                    try {
                                        val fileTime = java.time.Instant.parse(fileLastModified.toString())
                                        val timeDiff = java.time.Duration.between(cpgCreationTime, fileTime)
                                        appendLine("  Time difference (file vs CPG): ${timeDiff.toMillis()}ms")
                                        if (timeDiff.isPositive) {
                                            appendLine("  ⚠️  FILE WAS MODIFIED AFTER CPG CREATION - This is likely the root cause!")
                                        }
                                    } catch (e: Exception) {
                                        appendLine("  Could not compare timestamps: ${e.message}")
                                    }
                                }
                            } else {
                                appendLine("  CPG file not found: ${cpgPath.pathString}")
                            }
                        }

                        symbol?.let { sym ->
                            appendLine("  Joern symbol info:")
                            appendLine("    Type: ${sym.type}")
                            appendLine("    Name: ${sym.name}")
                            appendLine("    Full name: ${sym.fullName}")
                            appendLine("    Language: ${sym.language}")
                            appendLine("    Node ID: ${sym.nodeId}")
                            sym.parentClass?.let { appendLine("    Parent class: $it") }
                        }

                        originalJson?.let { json ->
                            appendLine("  Original Joern JSON: ${json.take(500)}")
                        }

                        // Add stack trace to see call path
                        appendLine("  Call stack:")
                        Thread.currentThread().stackTrace.take(10).forEach { frame ->
                            appendLine("    ${frame.className}.${frame.methodName}:${frame.lineNumber}")
                        }
                    }

                logger.warn { diagnosticInfo }

                // Return symbol without code content rather than null to allow processing to continue
                // This provides graceful degradation instead of complete failure
                logger.info { "Continuing processing without code content for symbol: ${symbol?.name} in $relativePath" }
                return ""
            }

            // Adjust lineEnd to not exceed file length
            val actualLineEnd = minOf(lineEnd, fileLines.size)

            // Extract lines (convert from 1-based to 0-based indexing)
            val startIndex = lineStart - 1
            val endIndex = actualLineEnd // subList is exclusive on end

            fileLines.subList(startIndex, endIndex).joinToString("\n")
        } catch (e: Exception) {
            logger.error(e) { "Failed to extract code from cache for file $relativePath, lines $lineStart-$lineEnd" }
            null
        }
    }

    /**
     * Perform aggressive memory-based cache eviction when memory limits are exceeded
     */
    private fun performMemoryBasedEviction() {
        val originalSize = fileContentCache.size
        val targetSize = maxCacheSize / 2 // Reduce to half capacity

        // Remove oldest entries until we reach target size
        val iterator = fileContentCache.entries.iterator()
        var removedCount = 0
        var freedMemory = 0L

        while (iterator.hasNext() && fileContentCache.size > targetSize) {
            val entry = iterator.next()
            val fileSizeBytes = entry.value.sumOf { it.length * 2 }
            iterator.remove()
            freedMemory += fileSizeBytes
            removedCount++
        }

        approximateCacheSize -= freedMemory
        logger.info {
            "Memory-based cache eviction: removed $removedCount files, freed ${freedMemory / 1024 / 1024}MB (cache size: $originalSize -> ${fileContentCache.size})"
        }
    }

    /**
     * Clear file content cache to free memory
     */
    fun clearCache() {
        synchronized(fileContentCache) {
            fileContentCache.clear()
            approximateCacheSize = 0L
            logger.debug { "File content cache cleared completely" }
        }
    }

    /**
     * Get current cache statistics for monitoring
     */
    fun getCacheStats(): CacheStats =
        synchronized(fileContentCache) {
            CacheStats(
                fileCount = fileContentCache.size,
                approximateSizeMB = approximateCacheSize / 1024 / 1024,
                maxFilesLimit = maxCacheSize,
                maxMemoryLimitMB = maxCacheSizeBytes.toLong() / 1024 / 1024,
            )
        }

    /**
     * Proactively check and clean cache if needed
     */
    fun maintainCache() {
        synchronized(fileContentCache) {
            if (approximateCacheSize > maxCacheSizeBytes * 0.8) { // 80% threshold
                logger.debug { "Proactive cache maintenance triggered (${approximateCacheSize / 1024 / 1024}MB)" }
                performMemoryBasedEviction()
            }
        }
    }

    /**
     * Cache statistics data class
     */
    data class CacheStats(
        val fileCount: Int,
        val approximateSizeMB: Long,
        val maxFilesLimit: Int,
        val maxMemoryLimitMB: Long,
    )

    /**
     * Remove ANSI color codes from text
     */
    private fun removeAnsiColorCodes(text: String): String = text.replace(Regex("\u001B\\[[;\\d]*m"), "")
}
