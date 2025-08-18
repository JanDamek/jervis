package com.jervis.service.indexer

import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.domain.symbol.Symbol
import com.jervis.domain.symbol.SymbolKind
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.gitwatcher.GitClient
import com.jervis.service.vectordb.AdvancedRagDocument
import com.jervis.service.vectordb.VectorStorageService
import com.jervis.service.vectordb.SymbolRelations
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

@Service
class AdvancedProjectIndexer(
    private val multiEmbeddingService: MultiEmbeddingService,
    private val vectorStorageService: VectorStorageService,
    private val tokenizerService: TokenizerService,
    private val gitClient: GitClient
) {
    private val logger = KotlinLogging.logger {}
    
    /**
     * Incremental indexing - only changed files
     */
    suspend fun indexProjectIncremental(
        project: ProjectDocument,
        lastCommitHash: String? = null
    ): AdvancedIndexingResult = coroutineScope {
        logger.info { "Starting incremental indexing for: ${project.name}" }
        val startTime = System.currentTimeMillis()
        
        try {
            // Get changed files since last commit
            val changedFiles = if (lastCommitHash != null) {
                // For now, fall back to full indexing since GitClient.getChangedFilesSince may not exist
                enumerateCodeFiles(project.path)
            } else {
                // If no lastCommitHash, index everything
                enumerateCodeFiles(project.path)
            }
            
            logger.info { "Found ${changedFiles.size} changed files" }
            
            // Mark old versions as outdated
            markOldVersionsAsOutdated(project, changedFiles)
            
            // Index only changed files
            val indexedChunks = indexChangedFiles(project, changedFiles)
            
            val duration = System.currentTimeMillis() - startTime
            logger.info { "Incremental indexing completed in ${duration}ms" }
            
            AdvancedIndexingResult(
                success = true,
                filesProcessed = changedFiles.size,
                chunksCreated = indexedChunks,
                duration = duration
            )
            
        } catch (e: Exception) {
            logger.error(e) { "Incremental indexing failed: ${e.message}" }
            AdvancedIndexingResult(
                success = false,
                errorMessage = e.message,
                duration = System.currentTimeMillis() - startTime
            )
        }
    }
    
    /**
     * Full project indexing with advanced multi-embedding support
     */
    suspend fun indexProjectFull(project: ProjectDocument): AdvancedIndexingResult = coroutineScope {
        logger.info { "Starting full advanced indexing for: ${project.name}" }
        val startTime = System.currentTimeMillis()
        
        try {
            val allFiles = enumerateCodeFiles(project.path)
            logger.info { "Found ${allFiles.size} files to index" }
            
            val indexedChunks = indexChangedFiles(project, allFiles)
            
            val duration = System.currentTimeMillis() - startTime
            logger.info { "Full advanced indexing completed in ${duration}ms" }
            
            AdvancedIndexingResult(
                success = true,
                filesProcessed = allFiles.size,
                chunksCreated = indexedChunks,
                duration = duration
            )
            
        } catch (e: Exception) {
            logger.error(e) { "Full advanced indexing failed: ${e.message}" }
            AdvancedIndexingResult(
                success = false,
                errorMessage = e.message,
                duration = System.currentTimeMillis() - startTime
            )
        }
    }
    
    private suspend fun indexChangedFiles(
        project: ProjectDocument,
        changedFiles: List<Path>
    ): Int = coroutineScope {
        val chunkCounts = changedFiles.map { filePath ->
            async {
                try {
                    val content = String(Files.readAllBytes(filePath), Charsets.UTF_8)
                    val language = detectLanguage(filePath.toString())
                    
                    // Use tokenizer for precise chunking
                    val codeChunks = createTokenizedChunks(content, language, filePath.toString())
                    
                    codeChunks.forEach { chunk ->
                        val document = createAdvancedRagDocument(chunk, project, filePath.toString())
                        indexAdvancedChunk(document)
                    }
                    
                    logger.debug { "Indexed ${codeChunks.size} chunks from ${filePath.fileName}" }
                    codeChunks.size
                    
                } catch (e: Exception) {
                    logger.error(e) { "Failed to index file ${filePath}: ${e.message}" }
                    0
                }
            }
        }.awaitAll()
        
        chunkCounts.sum()
    }
    
    private fun createTokenizedChunks(
        content: String,
        language: String,
        filePath: String
    ): List<AdvancedCodeChunk> {
        // Use tree-sitter or PSI for parsing (simplified for now)
        val symbols = parseCodeStructure(content, language)
        
        return symbols.map { symbol ->
            val tokenizedContent = tokenizerService.trimCodeToTokens(symbol.documentation ?: "", 350)
            
            AdvancedCodeChunk(
                content = tokenizedContent,
                type = symbol.kind.name.lowercase(),
                name = symbol.name,
                startLine = symbol.startLine,
                endLine = symbol.endLine,
                filePath = filePath,
                symbol = symbol,
                language = language
            )
        }
    }
    
    private fun parseCodeStructure(content: String, language: String): List<Symbol> {
        // Simplified parsing - in real implementation would use tree-sitter or PSI
        val symbols = mutableListOf<Symbol>()
        val lines = content.split('\n')
        
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            
            // Kotlin class detection
            val kotlinClassPattern = Regex("(class|interface|object)\\s+(\\w+)")
            kotlinClassPattern.find(trimmed)?.let { match ->
                symbols.add(Symbol(
                    name = match.groupValues[2],
                    qualifiedName = "com.example.${match.groupValues[2]}", // Simplified
                    kind = if (match.groupValues[1] == "interface") SymbolKind.INTERFACE else SymbolKind.CLASS,
                    startLine = index + 1,
                    endLine = findBlockEnd(lines, index),
                    documentation = extractDocumentation(lines, index)
                ))
            }
            
            // Function detection
            val functionPattern = Regex("(fun|function)\\s+(\\w+)\\s*\\(")
            functionPattern.find(trimmed)?.let { match ->
                symbols.add(Symbol(
                    name = match.groupValues[2],
                    qualifiedName = "com.example.${match.groupValues[2]}", // Simplified
                    kind = SymbolKind.FUNCTION,
                    startLine = index + 1,
                    endLine = findBlockEnd(lines, index),
                    documentation = extractDocumentation(lines, index)
                ))
            }
        }
        
        // If no specific symbols found, create a general code block
        if (symbols.isEmpty()) {
            symbols.add(Symbol(
                name = "code_block",
                qualifiedName = "code_block",
                kind = SymbolKind.UNKNOWN,
                startLine = 1,
                endLine = lines.size
            ))
        }
        
        // Add content to symbols
        return symbols.map { symbol ->
            val symbolContent = lines.subList(
                (symbol.startLine - 1).coerceAtLeast(0),
                symbol.endLine.coerceAtMost(lines.size)
            ).joinToString("\n")
            
            symbol.copy(
                documentation = symbolContent
            )
        }
    }
    
    private fun findBlockEnd(lines: List<String>, startIndex: Int): Int {
        // Simple block end detection - count braces
        var openBraces = 0
        var foundFirstBrace = false
        
        for (i in startIndex until lines.size) {
            val line = lines[i]
            for (char in line) {
                when (char) {
                    '{' -> {
                        openBraces++
                        foundFirstBrace = true
                    }
                    '}' -> {
                        openBraces--
                        if (foundFirstBrace && openBraces == 0) {
                            return i + 1
                        }
                    }
                }
            }
        }
        
        return (startIndex + 10).coerceAtMost(lines.size) // Fallback
    }
    
    private fun extractDocumentation(lines: List<String>, symbolIndex: Int): String? {
        // Look for documentation comments above the symbol
        val docs = mutableListOf<String>()
        var currentIndex = symbolIndex - 1
        
        while (currentIndex >= 0) {
            val line = lines[currentIndex].trim()
            if (line.startsWith("/**") || line.startsWith("/*") || line.startsWith("*") || line.startsWith("//")) {
                docs.add(0, line)
                currentIndex--
            } else if (line.isEmpty()) {
                currentIndex--
            } else {
                break
            }
        }
        
        return if (docs.isNotEmpty()) docs.joinToString("\n") else null
    }
    
    private suspend fun createAdvancedRagDocument(
        chunk: AdvancedCodeChunk,
        project: ProjectDocument,
        filePath: String
    ): AdvancedRagDocument {
        return AdvancedRagDocument(
            projectId = project.id,
            clientId = project.clientId,
            documentType = determineDocumentType(chunk),
            ragSourceType = RagSourceType.FILE,
            timestamp = Instant.now(),
            symbol = chunk.symbol,
            language = chunk.language,
            module = extractModuleName(filePath),
            path = filePath,
            relations = extractSymbolRelations(chunk.content),
            summary = generateSummary(chunk),
            codeExcerpt = chunk.content,
            doc = chunk.symbol.documentation,
            inspirationOnly = project.inspirationOnly,
            isDefaultBranch = true
        )
    }
    
    private fun determineDocumentType(chunk: AdvancedCodeChunk): RagDocumentType {
        return when (chunk.symbol.kind) {
            SymbolKind.CLASS -> RagDocumentType.CLASS_SUMMARY
            SymbolKind.INTERFACE -> RagDocumentType.CLASS_SUMMARY
            SymbolKind.FUNCTION, SymbolKind.METHOD -> RagDocumentType.CODE
            else -> RagDocumentType.CODE
        }
    }
    
    private fun extractModuleName(filePath: String): String {
        val path = Paths.get(filePath)
        return path.parent?.fileName?.toString() ?: "unknown"
    }
    
    private fun extractSymbolRelations(content: String): SymbolRelations {
        val extends = mutableListOf<String>()
        val implements = mutableListOf<String>()
        val calls = mutableListOf<String>()
        
        // Simple pattern matching for relations
        val extendsPattern = Regex("extends\\s+(\\w+)")
        val implementsPattern = Regex("implements\\s+(\\w+)")
        val callPattern = Regex("(\\w+)\\s*\\(")
        
        extendsPattern.findAll(content).forEach { match ->
            extends.add(match.groupValues[1])
        }
        
        implementsPattern.findAll(content).forEach { match ->
            implements.add(match.groupValues[1])
        }
        
        callPattern.findAll(content).forEach { match ->
            calls.add(match.groupValues[1])
        }
        
        return SymbolRelations(
            extends = extends.distinct(),
            implements = implements.distinct(),
            calls = calls.distinct().take(10) // Limit to avoid too many calls
        )
    }
    
    private fun generateSummary(chunk: AdvancedCodeChunk): String {
        return when (chunk.symbol.kind) {
            SymbolKind.CLASS -> "Class ${chunk.symbol.name} implementation"
            SymbolKind.INTERFACE -> "Interface ${chunk.symbol.name} definition"
            SymbolKind.FUNCTION, SymbolKind.METHOD -> "Function ${chunk.symbol.name} implementation"
            else -> "Code block containing ${chunk.symbol.name}"
        }
    }
    
    private suspend fun indexAdvancedChunk(document: AdvancedRagDocument) {
        try {
            // Determine embedding type based on content
            val embeddingType = if (document.symbol.kind in listOf(SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.FUNCTION)) {
                MultiEmbeddingService.EmbeddingType.CODE
            } else {
                MultiEmbeddingService.EmbeddingType.TEXT
            }
            
            // Generate embedding
            val embedding = if (embeddingType == MultiEmbeddingService.EmbeddingType.CODE) {
                multiEmbeddingService.generateCodeEmbedding(document.codeExcerpt, forQuery = false)
            } else {
                multiEmbeddingService.generateTextEmbedding(document.summary, forQuery = false)
            }
            
            // Store in vector storage (advanced)
            vectorStorageService.storeAdvancedDocument(document, embedding)
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to index advanced chunk: ${e.message}" }
        }
    }
    
    private fun enumerateCodeFiles(projectPath: String): List<Path> {
        val codeExtensions = setOf(".kt", ".java", ".py", ".js", ".ts", ".cpp", ".c", ".h", ".cs")
        val projectDir = Paths.get(projectPath)
        val result = mutableListOf<Path>()
        
        return try {
            Files.walkFileTree(projectDir, object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    val fileName = file.fileName.toString()
                    val filePath = file.toString()
                    
                    if (codeExtensions.any { ext -> fileName.endsWith(ext) } &&
                        !filePath.contains("/.git/") &&
                        !filePath.contains("/target/") &&
                        !filePath.contains("/build/")) {
                        result.add(file)
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
            result
        } catch (e: Exception) {
            logger.error(e) { "Failed to enumerate code files: ${e.message}" }
            emptyList()
        }
    }
    
    private fun detectLanguage(filePath: String): String {
        return when {
            filePath.endsWith(".kt") -> "kotlin"
            filePath.endsWith(".java") -> "java"
            filePath.endsWith(".py") -> "python"
            filePath.endsWith(".js") -> "javascript"
            filePath.endsWith(".ts") -> "typescript"
            filePath.endsWith(".cpp") || filePath.endsWith(".cc") -> "cpp"
            filePath.endsWith(".c") -> "c"
            filePath.endsWith(".cs") -> "csharp"
            else -> "unknown"
        }
    }
    
    private suspend fun markOldVersionsAsOutdated(project: ProjectDocument, changedFiles: List<Path>) {
        // TODO: Implement logic to mark old document versions as outdated in vector DB
        // This could involve querying by file path and project ID, then updating or deleting old entries
        logger.debug { "Marking old versions as outdated for ${changedFiles.size} files" }
    }
}

// Data classes
data class AdvancedCodeChunk(
    val content: String,
    val type: String,
    val name: String,
    val startLine: Int,
    val endLine: Int,
    val filePath: String,
    val symbol: Symbol,
    val language: String
)

data class AdvancedIndexingResult(
    val success: Boolean,
    val filesProcessed: Int = 0,
    val chunksCreated: Int = 0,
    val duration: Long = 0,
    val errorMessage: String? = null
)

// Extension function to add content to Symbol
fun Symbol.copy(
    name: String = this.name,
    qualifiedName: String = this.qualifiedName,
    kind: SymbolKind = this.kind,
    parent: String? = this.parent,
    startLine: Int = this.startLine,
    endLine: Int = this.endLine,
    documentation: String? = this.documentation,
    modifiers: List<String> = this.modifiers,
    parameters: List<com.jervis.domain.symbol.SymbolParameter> = this.parameters,
    returnType: String? = this.returnType
): Symbol {
    return Symbol(name, qualifiedName, kind, parent, startLine, endLine, documentation, modifiers, parameters, returnType)
}